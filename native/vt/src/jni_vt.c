#include "vt_parser.h"
#include <jni.h>
#include <android/log.h>
#include <stdlib.h>
#include <stdio.h>
#include <string.h>

#define LOG_TAG "VtParser"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Helper to get native pointer from Java object. Only the rarely-called instance natives
// (resize/reset/dirty bookkeeping) pay this reflective lookup; hot-path natives are STATIC and take
// the handle long directly, so the per-call GetObjectClass+GetFieldID never runs at PTY-drain rates.
static VtParser* get_parser(JNIEnv* env, jobject thiz) {
    jclass clazz = (*env)->GetObjectClass(env, thiz);
    jfieldID fid = (*env)->GetFieldID(env, clazz, "nativeHandle", "J");
    return (VtParser*)(*env)->GetLongField(env, thiz, fid);
}

JNIEXPORT jlong JNICALL
Java_dev_jcode_core_term_VtParser_nativeCreate(JNIEnv* env, jobject thiz, jint rows, jint cols) {
    (void)env; (void)thiz;
    VtParser* parser = vt_parser_create(rows, cols);
    if (!parser) {
        LOGE("Failed to create VT parser");
        return 0;
    }
    LOGI("VT parser created: %dx%d", rows, cols);
    return (jlong)parser;
}

// Static destroy-by-handle: invoked by the Kotlin Cleaner with only the primitive handle, so cleanup
// never needs (or retains) the VtParser object. Replaces the previous instance-based nativeDestroy.
JNIEXPORT void JNICALL
Java_dev_jcode_core_term_VtParser_nativeCloseByHandle(JNIEnv* env, jclass clazz, jlong handle) {
    (void)env; (void)clazz;
    if (handle == 0) return;
    vt_parser_destroy((VtParser*)handle);
    LOGI("VT parser destroyed");
}

// Length-aware zero-copy feed: the Kotlin reader passes its reused read buffer plus the byte count,
// so no per-chunk copyOf is needed. Critical access pins the array; no JNI calls happen between
// get and release, and JNI_ABORT skips the (pointless) write-back copy.
JNIEXPORT void JNICALL
Java_dev_jcode_core_term_VtParser_nativeFeed(JNIEnv* env, jclass clazz, jlong handle, jbyteArray data, jint length) {
    (void)clazz;
    VtParser* parser = (VtParser*)handle;
    if (!parser || !data || length <= 0) return;

    jsize capacity = (*env)->GetArrayLength(env, data);
    if (length > capacity) length = capacity;

    jbyte* bytes = (jbyte*)(*env)->GetPrimitiveArrayCritical(env, data, NULL);
    if (bytes) {
        vt_parser_feed(parser, (const uint8_t*)bytes, (size_t)length);
        (*env)->ReleasePrimitiveArrayCritical(env, data, bytes, JNI_ABORT);
    }
}

// Drains the queued shell-integration OSC events (7711-7713) in one call, clearing the queue.
// Returns NULL when nothing is queued — the common case, so the per-feed poll costs one cheap
// native call. Each element is "<code>;<payload>", split at the FIRST ';' by VtParser.drainOsc
// (payloads may themselves contain ';'). Payload bytes are widened 1:1 to UTF-16 chars (Latin-1),
// matching what the replaced Kotlin OscScanner produced for non-ASCII output.
JNIEXPORT jobjectArray JNICALL
Java_dev_jcode_core_term_VtParser_nativeDrainOsc(JNIEnv* env, jclass clazz, jlong handle) {
    (void)clazz;
    VtParser* parser = (VtParser*)handle;
    if (!parser) return NULL;
    int count = vt_parser_osc_event_count(parser);
    if (count == 0) return NULL;

    jclass string_class = (*env)->FindClass(env, "java/lang/String");
    jobjectArray result = string_class ? (*env)->NewObjectArray(env, count, string_class, NULL) : NULL;
    if (!result) {
        vt_parser_osc_clear(parser);
        return NULL;
    }

    // One scratch buffer reused for every event, sized to the largest payload — the OSC
    // accumulator grows past VT_OSC_BUFFER_CAP now (clipboard writes), so a fixed size would
    // truncate what the parser preserved.
    size_t scratch = 64;
    for (int idx = 0; idx < count; idx++) {
        int code = 0;
        const char* payload = "";
        if (vt_parser_osc_event_at(parser, idx, &code, &payload)) {
            size_t need = strlen(payload) + 16;
            if (need > scratch) scratch = need;
        }
    }
    jchar* chars = (jchar*)malloc(scratch * sizeof(jchar));
    if (!chars) {
        vt_parser_osc_clear(parser);
        return NULL;
    }
    for (int idx = 0; idx < count; idx++) {
        int code = 0;
        const char* payload = "";
        if (!vt_parser_osc_event_at(parser, idx, &code, &payload)) break;
        char prefix[16];
        int n = snprintf(prefix, sizeof(prefix), "%d;", code);
        if (n < 0) n = 0;
        if (n > (int)sizeof(prefix) - 1) n = (int)sizeof(prefix) - 1;
        int total = 0;
        for (int k = 0; k < n; k++) chars[total++] = (jchar)prefix[k];
        for (const char* p = payload; *p && total < (int)scratch; p++) {
            chars[total++] = (jchar)(uint8_t)*p;
        }
        jstring s = (*env)->NewString(env, chars, total);
        if (!s) break;  // OOM: the pending exception surfaces when this native returns
        (*env)->SetObjectArrayElement(env, result, idx, s);
        (*env)->DeleteLocalRef(env, s);
    }
    free(chars);
    vt_parser_osc_clear(parser);
    return result;
}

// Drains the pending answerback bytes (DA/DSR/CPR/DECRQM/OSC-color replies) queued during feed.
// Returns NULL when nothing is pending — the common case. The reader loop writes the returned
// bytes straight to the PTY, completing the query round-trip terminal programs expect.
JNIEXPORT jbyteArray JNICALL
Java_dev_jcode_core_term_VtParser_nativeTakeResponses(JNIEnv* env, jclass clazz, jlong handle) {
    (void)clazz;
    VtParser* parser = (VtParser*)handle;
    if (!parser || parser->response_len == 0) return NULL;
    uint8_t buf[VT_RESPONSE_CAP];
    int n = vt_parser_take_responses(parser, buf, (int)sizeof(buf));
    if (n <= 0) return NULL;
    jbyteArray out = (*env)->NewByteArray(env, n);
    if (!out) return NULL;
    (*env)->SetByteArrayRegion(env, out, 0, n, (const jbyte*)buf);
    return out;
}

// Packed VT_MODE_* snapshot of the input-affecting DEC private modes (see vt_parser.h / VtParser.kt).
JNIEXPORT jint JNICALL
Java_dev_jcode_core_term_VtParser_nativeGetInputModes(JNIEnv* env, jclass clazz, jlong handle) {
    (void)env; (void)clazz;
    return vt_parser_input_modes((VtParser*)handle);
}

JNIEXPORT void JNICALL
Java_dev_jcode_core_term_VtParser_nativeResize(JNIEnv* env, jobject thiz, jint rows, jint cols) {
    VtParser* parser = get_parser(env, thiz);
    if (parser) {
        vt_parser_resize(parser, rows, cols);
    }
}

JNIEXPORT void JNICALL
Java_dev_jcode_core_term_VtParser_nativeReset(JNIEnv* env, jobject thiz) {
    VtParser* parser = get_parser(env, thiz);
    if (parser) {
        vt_parser_reset(parser);
    }
}

JNIEXPORT jint JNICALL
Java_dev_jcode_core_term_VtParser_nativeGetRows(JNIEnv* env, jclass clazz, jlong handle) {
    (void)env; (void)clazz;
    const VtScreen* screen = vt_parser_get_screen((VtParser*)handle);
    return screen ? screen->rows : 0;
}

JNIEXPORT jint JNICALL
Java_dev_jcode_core_term_VtParser_nativeGetCols(JNIEnv* env, jclass clazz, jlong handle) {
    (void)env; (void)clazz;
    const VtScreen* screen = vt_parser_get_screen((VtParser*)handle);
    return screen ? screen->cols : 0;
}

JNIEXPORT jint JNICALL
Java_dev_jcode_core_term_VtParser_nativeGetCursorRow(JNIEnv* env, jclass clazz, jlong handle) {
    (void)env; (void)clazz;
    const VtScreen* screen = vt_parser_get_screen((VtParser*)handle);
    return screen ? screen->cursor_row : 0;
}

JNIEXPORT jint JNICALL
Java_dev_jcode_core_term_VtParser_nativeGetCursorCol(JNIEnv* env, jclass clazz, jlong handle) {
    (void)env; (void)clazz;
    const VtScreen* screen = vt_parser_get_screen((VtParser*)handle);
    return screen ? screen->cursor_col : 0;
}

JNIEXPORT jboolean JNICALL
Java_dev_jcode_core_term_VtParser_nativeIsCursorVisible(JNIEnv* env, jclass clazz, jlong handle) {
    (void)env; (void)clazz;
    const VtScreen* screen = vt_parser_get_screen((VtParser*)handle);
    return screen && screen->cursor_visible ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_dev_jcode_core_term_VtParser_nativeIsAlternateScreen(JNIEnv* env, jobject thiz) {
    VtParser* parser = get_parser(env, thiz);
    return parser && vt_parser_is_alternate_screen(parser) ? JNI_TRUE : JNI_FALSE;
}

// row >= 0 addresses the live screen; row < 0 addresses scrollback (-1 = newest scrolled-off line).

JNIEXPORT jint JNICALL
Java_dev_jcode_core_term_VtParser_nativeGetCellChar(JNIEnv* env, jclass clazz, jlong handle, jint row, jint col) {
    (void)env; (void)clazz;
    const VtCell* cell = vt_parser_cell_at((VtParser*)handle, row, col);
    return cell ? (jint)cell->ch : ' ';
}

// Packs a whole row of cells into `out` in one JNI crossing — 4 jints per cell:
// [codepoint, fg, bg, fgMode | bgMode<<2 | attrs<<4]. fg/bg use the same encoding as the old
// per-cell getters (-1 default, 0-255 indexed, packed RGB truecolor). Returns cells written.
JNIEXPORT jint JNICALL
Java_dev_jcode_core_term_VtParser_nativeReadRow(JNIEnv* env, jclass clazz, jlong handle, jint row, jintArray out) {
    (void)clazz;
    VtParser* parser = (VtParser*)handle;
    if (!parser || !out) return 0;
    const VtScreen* screen = vt_parser_get_screen(parser);
    if (!screen) return 0;

    int cols = screen->cols;
    jint capacity = (*env)->GetArrayLength(env, out);
    if (cols * 4 > capacity) cols = capacity / 4;
    if (cols <= 0) return 0;

    // Resolve the row base once — the per-cell vt_parser_cell_at re-ran the live/scrollback
    // resolution (bounds checks + ring modulo) for every one of the ~5k cells packed per frame.
    const VtCell* base = vt_parser_row_ptr(parser, row);
    int avail = 0;
    if (base) {
        avail = vt_parser_row_cols(parser, row);
        if (avail > cols) avail = cols;
    }

    jint* buf = (*env)->GetIntArrayElements(env, out, NULL);
    if (!buf) return 0;
    for (int col = 0; col < cols; col++) {
        const VtCell* cell = col < avail ? base + col : NULL;
        jint* dst = buf + col * 4;
        if (!cell) {
            dst[0] = ' ';
            dst[1] = -1;
            dst[2] = -1;
            dst[3] = 0;
            continue;
        }
        dst[0] = (jint)cell->ch;
        dst[1] = cell->fg.mode == 1 ? cell->fg.index
               : cell->fg.mode == 2 ? ((cell->fg.r << 16) | (cell->fg.g << 8) | cell->fg.b)
               : -1;
        dst[2] = cell->bg.mode == 1 ? cell->bg.index
               : cell->bg.mode == 2 ? ((cell->bg.r << 16) | (cell->bg.g << 8) | cell->bg.b)
               : -1;
        dst[3] = (cell->fg.mode & 0x3) | ((cell->bg.mode & 0x3) << 2) | ((jint)cell->attrs << 4);
    }
    (*env)->ReleaseIntArrayElements(env, out, buf, 0);
    return cols;
}

// Packs `rowCount` whole rows starting at logical `topRow` (negative = scrollback) in ONE JNI
// crossing — the renderer previously paid one nativeReadRow crossing per visible row per frame.
// Same 4-int cell encoding as nativeReadRow; out-of-range rows pack as blanks. Returns the number
// of rows packed. Critical section: no JNI calls may happen inside (see nativeFeed).
JNIEXPORT jint JNICALL
Java_dev_jcode_core_term_VtParser_nativeReadScreen(JNIEnv* env, jclass clazz, jlong handle, jint topRow, jint rowCount, jintArray out) {
    (void)clazz;
    VtParser* parser = (VtParser*)handle;
    if (!parser || !out || rowCount <= 0) return 0;
    const VtScreen* screen = vt_parser_get_screen(parser);
    if (!screen || screen->cols <= 0) return 0;

    int cols = screen->cols;
    jsize capacity = (*env)->GetArrayLength(env, out);
    // 64-bit product: a huge rowCount would overflow jint, bypass this clamp, and run the pack
    // loop off the end of the array inside the critical section.
    if ((jlong)rowCount * cols * 4 > (jlong)capacity) rowCount = (jint)(capacity / ((jlong)cols * 4));
    if (rowCount <= 0) return 0;

    jint* buf = (*env)->GetPrimitiveArrayCritical(env, out, NULL);
    if (!buf) return 0;
    for (int r = 0; r < rowCount; r++) {
        int row = topRow + r;
        const VtCell* base = vt_parser_row_ptr(parser, row);
        int avail = 0;
        if (base) {
            avail = vt_parser_row_cols(parser, row);
            if (avail > cols) avail = cols;
        }
        jint* dst = buf + (size_t)r * cols * 4;
        for (int col = 0; col < cols; col++) {
            jint* cell_out = dst + col * 4;
            if (col >= avail) {
                cell_out[0] = ' ';
                cell_out[1] = -1;
                cell_out[2] = -1;
                cell_out[3] = 0;
                continue;
            }
            const VtCell* cell = base + col;
            cell_out[0] = (jint)cell->ch;
            cell_out[1] = cell->fg.mode == 1 ? cell->fg.index
                        : cell->fg.mode == 2 ? ((cell->fg.r << 16) | (cell->fg.g << 8) | cell->fg.b)
                        : -1;
            cell_out[2] = cell->bg.mode == 1 ? cell->bg.index
                        : cell->bg.mode == 2 ? ((cell->bg.r << 16) | (cell->bg.g << 8) | cell->bg.b)
                        : -1;
            cell_out[3] = (cell->fg.mode & 0x3) | ((cell->bg.mode & 0x3) << 2) | ((jint)cell->attrs << 4);
        }
    }
    (*env)->ReleasePrimitiveArrayCritical(env, out, buf, 0);
    return rowCount;
}

JNIEXPORT jint JNICALL
Java_dev_jcode_core_term_VtParser_nativeGetScrollbackSize(JNIEnv* env, jclass clazz, jlong handle) {
    (void)env; (void)clazz;
    return vt_parser_scrollback_count((VtParser*)handle);
}

// Monotonic count of lines ever pushed into scrollback — unlike scrollback size, it keeps growing
// once the ring is full, so a scrolled-back view can tell "content shifted under me" from "nothing
// I can see changed" (the render-skip + anchor logic in TerminalView.onUpdate).
JNIEXPORT jlong JNICALL
Java_dev_jcode_core_term_VtParser_nativeGetScrollbackPushed(JNIEnv* env, jclass clazz, jlong handle) {
    (void)env; (void)clazz;
    const VtParser* parser = (const VtParser*)handle;
    return parser ? (jlong)parser->scrollback_pushed : 0;
}

JNIEXPORT jint JNICALL
Java_dev_jcode_native_vt_VtNativeModule_nativeInit(JNIEnv* env, jobject thiz) {
    (void)env; (void)thiz;
    LOGI("VT parser JNI initialized");
    return 1;
}
