#include "vt_parser.h"
#include <jni.h>
#include <android/log.h>
#include <stdlib.h>

#define LOG_TAG "VtParser"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Helper to get native pointer from Java object
static VtParser* get_parser(JNIEnv* env, jobject thiz) {
    jclass clazz = (*env)->GetObjectClass(env, thiz);
    jfieldID fid = (*env)->GetFieldID(env, clazz, "nativeHandle", "J");
    return (VtParser*)(*env)->GetLongField(env, thiz, fid);
}

JNIEXPORT jlong JNICALL
Java_dev_jcode_core_term_VtParser_nativeCreate(JNIEnv* env, jobject thiz, jint rows, jint cols) {
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
    if (handle == 0) return;
    vt_parser_destroy((VtParser*)handle);
    LOGI("VT parser destroyed");
}

JNIEXPORT void JNICALL
Java_dev_jcode_core_term_VtParser_nativeFeed(JNIEnv* env, jobject thiz, jbyteArray data) {
    VtParser* parser = get_parser(env, thiz);
    if (!parser || !data) return;
    
    jsize len = (*env)->GetArrayLength(env, data);
    jbyte* bytes = (*env)->GetByteArrayElements(env, data, NULL);
    if (bytes) {
        vt_parser_feed(parser, (const uint8_t*)bytes, len);
        (*env)->ReleaseByteArrayElements(env, data, bytes, JNI_ABORT);
    }
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
Java_dev_jcode_core_term_VtParser_nativeGetRows(JNIEnv* env, jobject thiz) {
    VtParser* parser = get_parser(env, thiz);
    if (!parser) return 0;
    const VtScreen* screen = vt_parser_get_screen(parser);
    return screen ? screen->rows : 0;
}

JNIEXPORT jint JNICALL
Java_dev_jcode_core_term_VtParser_nativeGetCols(JNIEnv* env, jobject thiz) {
    VtParser* parser = get_parser(env, thiz);
    if (!parser) return 0;
    const VtScreen* screen = vt_parser_get_screen(parser);
    return screen ? screen->cols : 0;
}

JNIEXPORT jint JNICALL
Java_dev_jcode_core_term_VtParser_nativeGetCursorRow(JNIEnv* env, jobject thiz) {
    VtParser* parser = get_parser(env, thiz);
    if (!parser) return 0;
    const VtScreen* screen = vt_parser_get_screen(parser);
    return screen ? screen->cursor_row : 0;
}

JNIEXPORT jint JNICALL
Java_dev_jcode_core_term_VtParser_nativeGetCursorCol(JNIEnv* env, jobject thiz) {
    VtParser* parser = get_parser(env, thiz);
    if (!parser) return 0;
    const VtScreen* screen = vt_parser_get_screen(parser);
    return screen ? screen->cursor_col : 0;
}

JNIEXPORT jboolean JNICALL
Java_dev_jcode_core_term_VtParser_nativeIsCursorVisible(JNIEnv* env, jobject thiz) {
    VtParser* parser = get_parser(env, thiz);
    if (!parser) return JNI_FALSE;
    const VtScreen* screen = vt_parser_get_screen(parser);
    return screen && screen->cursor_visible ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_dev_jcode_core_term_VtParser_nativeIsAlternateScreen(JNIEnv* env, jobject thiz) {
    VtParser* parser = get_parser(env, thiz);
    return parser && vt_parser_is_alternate_screen(parser) ? JNI_TRUE : JNI_FALSE;
}

// row >= 0 addresses the live screen; row < 0 addresses scrollback (-1 = newest scrolled-off line).

JNIEXPORT jint JNICALL
Java_dev_jcode_core_term_VtParser_nativeGetCellChar(JNIEnv* env, jobject thiz, jint row, jint col) {
    VtParser* parser = get_parser(env, thiz);
    const VtCell* cell = vt_parser_cell_at(parser, row, col);
    return cell ? (jint)cell->ch : ' ';
}

// Packs a whole row of cells into `out` in one JNI crossing — 4 jints per cell:
// [codepoint, fg, bg, fgMode | bgMode<<2 | attrs<<4]. fg/bg use the same encoding as the old
// per-cell getters (-1 default, 0-255 indexed, packed RGB truecolor). Returns cells written.
JNIEXPORT jint JNICALL
Java_dev_jcode_core_term_VtParser_nativeReadRow(JNIEnv* env, jobject thiz, jint row, jintArray out) {
    VtParser* parser = get_parser(env, thiz);
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
Java_dev_jcode_core_term_VtParser_nativeGetScrollbackSize(JNIEnv* env, jobject thiz) {
    VtParser* parser = get_parser(env, thiz);
    return parser ? vt_parser_scrollback_count(parser) : 0;
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
    LOGI("VT parser JNI initialized");
    return 1;
}
