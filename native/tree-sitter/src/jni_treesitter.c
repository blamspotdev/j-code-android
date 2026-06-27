#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <dlfcn.h>
#include <android/log.h>
#include <tree_sitter/api.h>

#define LOG_TAG "TreeSitter"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Language function type - each grammar exports a function like tree_sitter_c()
typedef TSLanguage* (*ts_language_func_t)(void);

// ============================================================================
// TsParser JNI
// ============================================================================

JNIEXPORT jlong JNICALL
Java_dev_jcode_core_treesitter_TsParser_nativeCreate(JNIEnv* env, jobject thiz) {
    TSParser* parser = ts_parser_new();
    if (!parser) {
        LOGE("Failed to create TSParser");
        return 0;
    }
    return (jlong)parser;
}

JNIEXPORT void JNICALL
Java_dev_jcode_core_treesitter_TsParser_nativeClose(JNIEnv* env, jobject thiz, jlong handle) {
    if (handle) {
        ts_parser_delete((TSParser*)handle);
    }
}

JNIEXPORT void JNICALL
Java_dev_jcode_core_treesitter_TsParser_00024Companion_nativeCloseByHandle(JNIEnv* env, jobject thiz, jlong handle) {
    if (handle) {
        ts_parser_delete((TSParser*)handle);
    }
}

JNIEXPORT jboolean JNICALL
Java_dev_jcode_core_treesitter_TsParser_nativeSetLanguage(JNIEnv* env, jobject thiz, jlong handle, jlong langHandle) {
    if (!handle || !langHandle) return JNI_FALSE;
    TSParser* parser = (TSParser*)handle;
    const TSLanguage* lang = (const TSLanguage*)langHandle;
    return ts_parser_set_language(parser, lang) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL
Java_dev_jcode_core_treesitter_TsParser_nativeParseString(JNIEnv* env, jobject thiz, jlong handle, jlong oldTreeHandle, jstring source) {
    if (!handle) return 0;
    TSParser* parser = (TSParser*)handle;
    const TSTree* oldTree = oldTreeHandle ? (const TSTree*)oldTreeHandle : NULL;
    
    const char* src = (*env)->GetStringUTFChars(env, source, NULL);
    jsize len = (*env)->GetStringUTFLength(env, source);
    
    TSTree* tree = ts_parser_parse_string(parser, oldTree, src, len);
    
    (*env)->ReleaseStringUTFChars(env, source, src);
    
    return (jlong)tree;
}

JNIEXPORT jlong JNICALL
Java_dev_jcode_core_treesitter_TsParser_nativeParseBytes(JNIEnv* env, jobject thiz, jlong handle, jlong oldTreeHandle, jbyteArray source) {
    if (!handle) return 0;
    TSParser* parser = (TSParser*)handle;
    const TSTree* oldTree = oldTreeHandle ? (const TSTree*)oldTreeHandle : NULL;
    
    jsize len = (*env)->GetArrayLength(env, source);
    jbyte* bytes = (*env)->GetByteArrayElements(env, source, NULL);
    
    TSTree* tree = ts_parser_parse_string(parser, oldTree, (const char*)bytes, len);
    
    (*env)->ReleaseByteArrayElements(env, source, bytes, JNI_ABORT);
    
    return (jlong)tree;
}

JNIEXPORT void JNICALL
Java_dev_jcode_core_treesitter_TsParser_nativeReset(JNIEnv* env, jobject thiz, jlong handle) {
    if (handle) {
        ts_parser_reset((TSParser*)handle);
    }
}

// ============================================================================
// TsTree JNI
// ============================================================================

JNIEXPORT jlong JNICALL
Java_dev_jcode_core_treesitter_TsTree_nativeCopy(JNIEnv* env, jobject thiz, jlong handle) {
    if (!handle) return 0;
    return (jlong)ts_tree_copy((const TSTree*)handle);
}

JNIEXPORT void JNICALL
Java_dev_jcode_core_treesitter_TsTree_nativeClose(JNIEnv* env, jobject thiz, jlong handle) {
    if (handle) {
        ts_tree_delete((TSTree*)handle);
    }
}

JNIEXPORT void JNICALL
Java_dev_jcode_core_treesitter_TsTree_00024Companion_nativeCloseByHandle(JNIEnv* env, jobject thiz, jlong handle) {
    if (handle) {
        ts_tree_delete((TSTree*)handle);
    }
}

JNIEXPORT jlong JNICALL
Java_dev_jcode_core_treesitter_TsTree_nativeRootNode(JNIEnv* env, jobject thiz, jlong handle) {
    if (!handle) return 0;
    TSNode node = ts_tree_root_node((const TSTree*)handle);
    TSNode* nodePtr = (TSNode*)malloc(sizeof(TSNode));
    if (!nodePtr) return 0;
    *nodePtr = node;
    return (jlong)nodePtr;
}

JNIEXPORT void JNICALL
Java_dev_jcode_core_treesitter_TsTree_nativeEdit(JNIEnv* env, jobject thiz, jlong handle,
    jint startByte, jint oldEndByte, jint newEndByte,
    jint startRow, jint startCol, jint oldEndRow, jint oldEndCol, jint newEndRow, jint newEndCol) {
    if (!handle) return;
    TSInputEdit edit = {
        .start_byte = (uint32_t)startByte,
        .old_end_byte = (uint32_t)oldEndByte,
        .new_end_byte = (uint32_t)newEndByte,
        .start_point = { (uint32_t)startRow, (uint32_t)startCol },
        .old_end_point = { (uint32_t)oldEndRow, (uint32_t)oldEndCol },
        .new_end_point = { (uint32_t)newEndRow, (uint32_t)newEndCol },
    };
    ts_tree_edit((TSTree*)handle, &edit);
}

// ============================================================================
// TsNode JNI
// ============================================================================

JNIEXPORT void JNICALL
Java_dev_jcode_core_treesitter_TsNode_nativeClose(JNIEnv* env, jobject thiz, jlong handle) {
    if (handle) {
        free((TSNode*)handle);
    }
}

JNIEXPORT jstring JNICALL
Java_dev_jcode_core_treesitter_TsNode_nativeType(JNIEnv* env, jobject thiz, jlong handle) {
    if (!handle) return (*env)->NewStringUTF(env, "");
    TSNode* node = (TSNode*)handle;
    const char* type = ts_node_type(*node);
    return (*env)->NewStringUTF(env, type ? type : "");
}

JNIEXPORT jint JNICALL
Java_dev_jcode_core_treesitter_TsNode_nativeStartByte(JNIEnv* env, jobject thiz, jlong handle) {
    if (!handle) return 0;
    return (jint)ts_node_start_byte(*(TSNode*)handle);
}

JNIEXPORT jint JNICALL
Java_dev_jcode_core_treesitter_TsNode_nativeEndByte(JNIEnv* env, jobject thiz, jlong handle) {
    if (!handle) return 0;
    return (jint)ts_node_end_byte(*(TSNode*)handle);
}

JNIEXPORT jint JNICALL
Java_dev_jcode_core_treesitter_TsNode_nativeStartRow(JNIEnv* env, jobject thiz, jlong handle) {
    if (!handle) return 0;
    return (jint)ts_node_start_point(*(TSNode*)handle).row;
}

JNIEXPORT jint JNICALL
Java_dev_jcode_core_treesitter_TsNode_nativeStartColumn(JNIEnv* env, jobject thiz, jlong handle) {
    if (!handle) return 0;
    return (jint)ts_node_start_point(*(TSNode*)handle).column;
}

JNIEXPORT jint JNICALL
Java_dev_jcode_core_treesitter_TsNode_nativeEndRow(JNIEnv* env, jobject thiz, jlong handle) {
    if (!handle) return 0;
    return (jint)ts_node_end_point(*(TSNode*)handle).row;
}

JNIEXPORT jint JNICALL
Java_dev_jcode_core_treesitter_TsNode_nativeEndColumn(JNIEnv* env, jobject thiz, jlong handle) {
    if (!handle) return 0;
    return (jint)ts_node_end_point(*(TSNode*)handle).column;
}

JNIEXPORT jint JNICALL
Java_dev_jcode_core_treesitter_TsNode_nativeChildCount(JNIEnv* env, jobject thiz, jlong handle) {
    if (!handle) return 0;
    return (jint)ts_node_child_count(*(TSNode*)handle);
}

JNIEXPORT jint JNICALL
Java_dev_jcode_core_treesitter_TsNode_nativeNamedChildCount(JNIEnv* env, jobject thiz, jlong handle) {
    if (!handle) return 0;
    return (jint)ts_node_named_child_count(*(TSNode*)handle);
}

JNIEXPORT jboolean JNICALL
Java_dev_jcode_core_treesitter_TsNode_nativeIsNamed(JNIEnv* env, jobject thiz, jlong handle) {
    if (!handle) return JNI_FALSE;
    return ts_node_is_named(*(TSNode*)handle) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_dev_jcode_core_treesitter_TsNode_nativeIsError(JNIEnv* env, jobject thiz, jlong handle) {
    if (!handle) return JNI_FALSE;
    return ts_node_is_error(*(TSNode*)handle) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_dev_jcode_core_treesitter_TsNode_nativeIsMissing(JNIEnv* env, jobject thiz, jlong handle) {
    if (!handle) return JNI_FALSE;
    return ts_node_is_missing(*(TSNode*)handle) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL
Java_dev_jcode_core_treesitter_TsNode_nativeChild(JNIEnv* env, jobject thiz, jlong handle, jint index) {
    if (!handle) return 0;
    TSNode child = ts_node_child(*(TSNode*)handle, (uint32_t)index);
    if (ts_node_is_null(child)) return 0;
    TSNode* childPtr = (TSNode*)malloc(sizeof(TSNode));
    if (!childPtr) return 0;
    *childPtr = child;
    return (jlong)childPtr;
}

JNIEXPORT jlong JNICALL
Java_dev_jcode_core_treesitter_TsNode_nativeNamedChild(JNIEnv* env, jobject thiz, jlong handle, jint index) {
    if (!handle) return 0;
    TSNode child = ts_node_named_child(*(TSNode*)handle, (uint32_t)index);
    if (ts_node_is_null(child)) return 0;
    TSNode* childPtr = (TSNode*)malloc(sizeof(TSNode));
    if (!childPtr) return 0;
    *childPtr = child;
    return (jlong)childPtr;
}

JNIEXPORT jlong JNICALL
Java_dev_jcode_core_treesitter_TsNode_nativeChildByFieldName(JNIEnv* env, jobject thiz, jlong handle, jstring name) {
    if (!handle) return 0;
    const char* fieldName = (*env)->GetStringUTFChars(env, name, NULL);
    TSNode child = ts_node_child_by_field_name(*(TSNode*)handle, fieldName, strlen(fieldName));
    (*env)->ReleaseStringUTFChars(env, name, fieldName);
    if (ts_node_is_null(child)) return 0;
    TSNode* childPtr = (TSNode*)malloc(sizeof(TSNode));
    if (!childPtr) return 0;
    *childPtr = child;
    return (jlong)childPtr;
}

JNIEXPORT jlong JNICALL
Java_dev_jcode_core_treesitter_TsNode_nativeParent(JNIEnv* env, jobject thiz, jlong handle) {
    if (!handle) return 0;
    TSNode parent = ts_node_parent(*(TSNode*)handle);
    if (ts_node_is_null(parent)) return 0;
    TSNode* parentPtr = (TSNode*)malloc(sizeof(TSNode));
    if (!parentPtr) return 0;
    *parentPtr = parent;
    return (jlong)parentPtr;
}

JNIEXPORT jlong JNICALL
Java_dev_jcode_core_treesitter_TsNode_nativeNextSibling(JNIEnv* env, jobject thiz, jlong handle) {
    if (!handle) return 0;
    TSNode sibling = ts_node_next_sibling(*(TSNode*)handle);
    if (ts_node_is_null(sibling)) return 0;
    TSNode* sibPtr = (TSNode*)malloc(sizeof(TSNode));
    if (!sibPtr) return 0;
    *sibPtr = sibling;
    return (jlong)sibPtr;
}

JNIEXPORT jlong JNICALL
Java_dev_jcode_core_treesitter_TsNode_nativePrevSibling(JNIEnv* env, jobject thiz, jlong handle) {
    if (!handle) return 0;
    TSNode sibling = ts_node_prev_sibling(*(TSNode*)handle);
    if (ts_node_is_null(sibling)) return 0;
    TSNode* sibPtr = (TSNode*)malloc(sizeof(TSNode));
    if (!sibPtr) return 0;
    *sibPtr = sibling;
    return (jlong)sibPtr;
}

JNIEXPORT jlong JNICALL
Java_dev_jcode_core_treesitter_TsNode_nativeNextNamedSibling(JNIEnv* env, jobject thiz, jlong handle) {
    if (!handle) return 0;
    TSNode sibling = ts_node_next_named_sibling(*(TSNode*)handle);
    if (ts_node_is_null(sibling)) return 0;
    TSNode* sibPtr = (TSNode*)malloc(sizeof(TSNode));
    if (!sibPtr) return 0;
    *sibPtr = sibling;
    return (jlong)sibPtr;
}

JNIEXPORT jlong JNICALL
Java_dev_jcode_core_treesitter_TsNode_nativePrevNamedSibling(JNIEnv* env, jobject thiz, jlong handle) {
    if (!handle) return 0;
    TSNode sibling = ts_node_prev_named_sibling(*(TSNode*)handle);
    if (ts_node_is_null(sibling)) return 0;
    TSNode* sibPtr = (TSNode*)malloc(sizeof(TSNode));
    if (!sibPtr) return 0;
    *sibPtr = sibling;
    return (jlong)sibPtr;
}

JNIEXPORT jlong JNICALL
Java_dev_jcode_core_treesitter_TsNode_nativeWalk(JNIEnv* env, jobject thiz, jlong handle) {
    if (!handle) return 0;
    TSTreeCursor* cursor = (TSTreeCursor*)malloc(sizeof(TSTreeCursor));
    if (!cursor) return 0;
    *cursor = ts_tree_cursor_new(*(TSNode*)handle);
    return (jlong)cursor;
}

// ============================================================================
// TsCursor JNI (wraps TSTreeCursor)
// ============================================================================

JNIEXPORT void JNICALL
Java_dev_jcode_core_treesitter_TsCursor_nativeClose(JNIEnv* env, jobject thiz, jlong handle) {
    if (handle) {
        ts_tree_cursor_delete((TSTreeCursor*)handle);
        free((TSTreeCursor*)handle);
    }
}

JNIEXPORT jboolean JNICALL
Java_dev_jcode_core_treesitter_TsCursor_nativeGoToFirstChild(JNIEnv* env, jobject thiz, jlong handle) {
    if (!handle) return JNI_FALSE;
    return ts_tree_cursor_goto_first_child((TSTreeCursor*)handle) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_dev_jcode_core_treesitter_TsCursor_nativeGoToNextSibling(JNIEnv* env, jobject thiz, jlong handle) {
    if (!handle) return JNI_FALSE;
    return ts_tree_cursor_goto_next_sibling((TSTreeCursor*)handle) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_dev_jcode_core_treesitter_TsCursor_nativeGoToParent(JNIEnv* env, jobject thiz, jlong handle) {
    if (!handle) return JNI_FALSE;
    return ts_tree_cursor_goto_parent((TSTreeCursor*)handle) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL
Java_dev_jcode_core_treesitter_TsCursor_nativeCurrentNode(JNIEnv* env, jobject thiz, jlong handle) {
    if (!handle) return 0;
    TSNode node = ts_tree_cursor_current_node((const TSTreeCursor*)handle);
    if (ts_node_is_null(node)) return 0;
    TSNode* nodePtr = (TSNode*)malloc(sizeof(TSNode));
    if (!nodePtr) return 0;
    *nodePtr = node;
    return (jlong)nodePtr;
}

JNIEXPORT jstring JNICALL
Java_dev_jcode_core_treesitter_TsCursor_nativeCurrentFieldName(JNIEnv* env, jobject thiz, jlong handle) {
    if (!handle) return NULL;
    const char* name = ts_tree_cursor_current_field_name((const TSTreeCursor*)handle);
    return name ? (*env)->NewStringUTF(env, name) : NULL;
}

JNIEXPORT void JNICALL
Java_dev_jcode_core_treesitter_TsCursor_nativeReset(JNIEnv* env, jobject thiz, jlong handle, jlong nodeHandle) {
    if (!handle || !nodeHandle) return;
    ts_tree_cursor_reset((TSTreeCursor*)handle, *(TSNode*)nodeHandle);
}

// ============================================================================
// TsQuery JNI
// ============================================================================

JNIEXPORT jlong JNICALL
Java_dev_jcode_core_treesitter_TsQuery_nativeCreate(JNIEnv* env, jobject thiz, jlong langHandle, jstring source) {
    if (!langHandle) return 0;
    const TSLanguage* lang = (const TSLanguage*)langHandle;
    const char* src = (*env)->GetStringUTFChars(env, source, NULL);
    jsize len = (*env)->GetStringUTFLength(env, source);
    
    uint32_t errorOffset = 0;
    TSQueryError errorType = TSQueryErrorNone;
    
    TSQuery* query = ts_query_new(lang, src, len, &errorOffset, &errorType);
    
    (*env)->ReleaseStringUTFChars(env, source, src);
    
    if (!query) {
        LOGE("Failed to create query: error type %d at offset %d", errorType, errorOffset);
        return 0;
    }
    
    return (jlong)query;
}

JNIEXPORT void JNICALL
Java_dev_jcode_core_treesitter_TsQuery_nativeClose(JNIEnv* env, jobject thiz, jlong handle) {
    if (handle) {
        ts_query_delete((TSQuery*)handle);
    }
}

JNIEXPORT jint JNICALL
Java_dev_jcode_core_treesitter_TsQuery_nativePatternCount(JNIEnv* env, jobject thiz, jlong handle) {
    if (!handle) return 0;
    return (jint)ts_query_pattern_count((const TSQuery*)handle);
}

JNIEXPORT jint JNICALL
Java_dev_jcode_core_treesitter_TsQuery_nativeCaptureCount(JNIEnv* env, jobject thiz, jlong handle) {
    if (!handle) return 0;
    return (jint)ts_query_capture_count((const TSQuery*)handle);
}

JNIEXPORT jstring JNICALL
Java_dev_jcode_core_treesitter_TsQuery_nativeCaptureNameForId(JNIEnv* env, jobject thiz, jlong handle, jint id) {
    if (!handle) return NULL;
    uint32_t len = 0;
    const char* name = ts_query_capture_name_for_id((const TSQuery*)handle, (uint32_t)id, &len);
    if (!name) return NULL;
    return (*env)->NewStringUTF(env, name);
}

// Query cursor for executing queries
JNIEXPORT jlong JNICALL
Java_dev_jcode_core_treesitter_TsQuery_nativeCreateCursor(JNIEnv* env, jobject thiz) {
    TSQueryCursor* cursor = ts_query_cursor_new();
    return (jlong)cursor;
}

JNIEXPORT void JNICALL
Java_dev_jcode_core_treesitter_TsQuery_nativeDeleteCursor(JNIEnv* env, jobject thiz, jlong cursorHandle) {
    if (cursorHandle) {
        ts_query_cursor_delete((TSQueryCursor*)cursorHandle);
    }
}

JNIEXPORT void JNICALL
Java_dev_jcode_core_treesitter_TsQuery_nativeExec(JNIEnv* env, jobject thiz, jlong cursorHandle, jlong queryHandle, jlong nodeHandle) {
    if (!cursorHandle || !queryHandle || !nodeHandle) return;
    ts_query_cursor_exec((TSQueryCursor*)cursorHandle, (const TSQuery*)queryHandle, *(TSNode*)nodeHandle);
}

JNIEXPORT void JNICALL
Java_dev_jcode_core_treesitter_TsQuery_nativeSetByteRange(JNIEnv* env, jobject thiz, jlong cursorHandle, jint start, jint end) {
    if (!cursorHandle) return;
    ts_query_cursor_set_byte_range((TSQueryCursor*)cursorHandle, (uint32_t)start, (uint32_t)end);
}

// Returns a match as a flat array: [patternIndex, captureCount, captureIndex1, nodeStartByte1, nodeEndByte1, ...]
// Caller must iterate by calling this repeatedly until it returns null
JNIEXPORT jintArray JNICALL
Java_dev_jcode_core_treesitter_TsQuery_nativeNextMatch(JNIEnv* env, jobject thiz, jlong cursorHandle, jlong queryHandle) {
    if (!cursorHandle || !queryHandle) return NULL;
    
    TSQueryMatch match;
    if (!ts_query_cursor_next_match((TSQueryCursor*)cursorHandle, &match)) {
        return NULL;
    }
    
    // Build result: [patternIndex, captureCount, captureIndex1, startByte1, endByte1, startRow1, startCol1, endRow1, endCol1, ...]
    int resultSize = 2 + match.capture_count * 6;
    jint* result = (jint*)malloc(sizeof(jint) * resultSize);
    if (!result) return NULL;
    
    result[0] = (jint)match.pattern_index;
    result[1] = (jint)match.capture_count;
    
    for (uint32_t i = 0; i < match.capture_count; i++) {
        int base = 2 + i * 6;
        result[base + 0] = (jint)match.captures[i].index;
        result[base + 1] = (jint)ts_node_start_byte(match.captures[i].node);
        result[base + 2] = (jint)ts_node_end_byte(match.captures[i].node);
        result[base + 3] = (jint)ts_node_start_point(match.captures[i].node).row;
        result[base + 4] = (jint)ts_node_start_point(match.captures[i].node).column;
        result[base + 5] = (jint)ts_node_end_point(match.captures[i].node).row;
    }
    
    jintArray array = (*env)->NewIntArray(env, resultSize);
    (*env)->SetIntArrayRegion(env, array, 0, resultSize, result);
    free(result);
    
    return array;
}

// ============================================================================
// Grammar loading JNI
// ============================================================================

JNIEXPORT jlong JNICALL
Java_dev_jcode_core_treesitter_TsLanguage_nativeLoad(JNIEnv* env, jobject thiz, jstring libName, jstring funcName) {
    const char* lib = (*env)->GetStringUTFChars(env, libName, NULL);
    const char* func = (*env)->GetStringUTFChars(env, funcName, NULL);
    
    // Use dlopen to load the grammar library
    void* handle = dlopen(lib, RTLD_NOW);
    if (!handle) {
        LOGE("Failed to load grammar library %s: %s", lib, dlerror());
        (*env)->ReleaseStringUTFChars(env, libName, lib);
        (*env)->ReleaseStringUTFChars(env, funcName, func);
        return 0;
    }
    
    // Get the language function
    ts_language_func_t langFunc = (ts_language_func_t)dlsym(handle, func);
    if (!langFunc) {
        LOGE("Failed to find function %s in %s: %s", func, lib, dlerror());
        dlclose(handle);
        (*env)->ReleaseStringUTFChars(env, libName, lib);
        (*env)->ReleaseStringUTFChars(env, funcName, func);
        return 0;
    }
    
    TSLanguage* language = langFunc();
    
    (*env)->ReleaseStringUTFChars(env, libName, lib);
    (*env)->ReleaseStringUTFChars(env, funcName, func);
    
    return (jlong)language;
}

JNIEXPORT jint JNICALL
Java_dev_jcode_core_treesitter_TsLanguage_nativeVersion(JNIEnv* env, jobject thiz, jlong handle) {
    if (!handle) return 0;
    return (jint)ts_language_version((const TSLanguage*)handle);
}

JNIEXPORT jint JNICALL
Java_dev_jcode_core_treesitter_TsLanguage_nativeFieldCount(JNIEnv* env, jobject thiz, jlong handle) {
    if (!handle) return 0;
    return (jint)ts_language_field_count((const TSLanguage*)handle);
}

JNIEXPORT jint JNICALL
Java_dev_jcode_native_treesitter_TreeSitterNativeModule_nativeInit(JNIEnv* env, jobject thiz) {
    LOGI("Tree-sitter JNI initialized (version %d)", TREE_SITTER_LANGUAGE_VERSION);
    return TREE_SITTER_LANGUAGE_VERSION;
}
