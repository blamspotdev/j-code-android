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

    // One scratch buffer reused for every event: "<code>;" prefix + payload.
    jchar* chars = (jchar*)malloc((VT_OSC_BUFFER_CAP + 16) * sizeof(jchar));
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
        for (const char* p = payload; *p && total < VT_OSC_BUFFER_CAP + 16; p++) {
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

    jint* buf = (*env)->GetIntArrayElements(env, out, NULL);
    if (!buf) return 0;
    for (int col = 0; col < cols; col++) {
        const VtCell* cell = vt_parser_cell_at(parser, row, col);
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

JNIEXPORT jint JNICALL
Java_dev_jcode_core_term_VtParser_nativeGetScrollbackSize(JNIEnv* env, jclass clazz, jlong handle) {
    (void)env; (void)clazz;
    return vt_parser_scrollback_count((VtParser*)handle);
}

JNIEXPORT void JNICALL
Java_dev_jcode_core_term_VtParser_nativeClearDirty(JNIEnv* env, jobject thiz) {
    VtParser* parser = get_parser(env, thiz);
    if (parser) {
        vt_parser_clear_dirty(parser);
    }
}

JNIEXPORT jboolean JNICALL
Java_dev_jcode_core_term_VtParser_nativeIsRowDirty(JNIEnv* env, jobject thiz, jint row) {
    VtParser* parser = get_parser(env, thiz);
    if (!parser || !parser->dirty_rows) return JNI_FALSE;
    const VtScreen* screen = vt_parser_get_screen(parser);
    if (!screen) return JNI_FALSE;
    
    if (row < 0 || row >= screen->rows) return JNI_FALSE;
    return parser->dirty_rows[row] ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_dev_jcode_core_term_VtParser_nativeNeedsFullRefresh(JNIEnv* env, jobject thiz) {
    VtParser* parser = get_parser(env, thiz);
    return parser && parser->full_refresh ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_dev_jcode_native_vt_VtNativeModule_nativeInit(JNIEnv* env, jobject thiz) {
    (void)env; (void)thiz;
    LOGI("VT parser JNI initialized");
    return 1;
}
