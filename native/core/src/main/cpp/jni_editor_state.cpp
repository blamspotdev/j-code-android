#include <jni.h>
#include "editor_state.h"
#include "undo_manager.h"
#include "jni_bridge.h"
#include <android/log.h>

#define LOG_TAG "JCodeEditor"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

using namespace jcode;

// Helper to get native handle from Java object
static EditorState* getEditorState(JNIEnv* env, jobject thiz) {
    jclass clazz = env->GetObjectClass(thiz);
    jfieldID fid = env->GetFieldID(clazz, "nativeHandle", "J");
    return reinterpret_cast<EditorState*>(env->GetLongField(thiz, fid));
}

static void setEditorState(JNIEnv* env, jobject thiz, EditorState* state) {
    jclass clazz = env->GetObjectClass(thiz);
    jfieldID fid = env->GetFieldID(clazz, "nativeHandle", "J");
    env->SetLongField(thiz, fid, reinterpret_cast<jlong>(state));
}

static UndoManager* getUndoManager(JNIEnv* env, jobject thiz) {
    jclass clazz = env->GetObjectClass(thiz);
    jfieldID fid = env->GetFieldID(clazz, "nativeHandle", "J");
    return reinterpret_cast<UndoManager*>(env->GetLongField(thiz, fid));
}

static void setUndoManager(JNIEnv* env, jobject thiz, UndoManager* manager) {
    jclass clazz = env->GetObjectClass(thiz);
    jfieldID fid = env->GetFieldID(clazz, "nativeHandle", "J");
    env->SetLongField(thiz, fid, reinterpret_cast<jlong>(manager));
}

extern "C" {

// EditorState JNI methods

JNIEXPORT jlong JNICALL
Java_dev_jcode_core_editor_EditorState_nativeCreate(JNIEnv* env, jobject thiz) {
    EditorState* state = new EditorState();
    return reinterpret_cast<jlong>(state);
}

JNIEXPORT void JNICALL
Java_dev_jcode_core_editor_EditorState_nativeDestroy(JNIEnv* env, jobject thiz) {
    EditorState* state = getEditorState(env, thiz);
    if (state) {
        delete state;
        setEditorState(env, thiz, nullptr);
    }
}

JNIEXPORT void JNICALL
Java_dev_jcode_core_editor_EditorState_nativeSetCarets(JNIEnv* env, jobject thiz,
                                                         jobjectArray carets) {
    EditorState* state = getEditorState(env, thiz);
    if (!state) return;

    std::vector<Caret> caret_vec;
    jsize count = env->GetArrayLength(carets);
    
    for (jsize i = 0; i < count; ++i) {
        jobject caret_obj = env->GetObjectArrayElement(carets, i);
        jclass caret_class = env->GetObjectClass(caret_obj);
        
        jfieldID anchor_field = env->GetFieldID(caret_class, "anchor", "I");
        jfieldID head_field = env->GetFieldID(caret_class, "head", "I");
        jfieldID col_field = env->GetFieldID(caret_class, "preferredColumn", "I");
        
        int32_t anchor = env->GetIntField(caret_obj, anchor_field);
        int32_t head = env->GetIntField(caret_obj, head_field);
        int32_t col = env->GetIntField(caret_obj, col_field);
        
        caret_vec.emplace_back(anchor, head, col);
        env->DeleteLocalRef(caret_obj);
    }
    
    state->set_carets(caret_vec);
}

JNIEXPORT jobjectArray JNICALL
Java_dev_jcode_core_editor_EditorState_nativeGetCarets(JNIEnv* env, jobject thiz) {
    EditorState* state = getEditorState(env, thiz);
    if (!state) return nullptr;

    auto carets = state->get_carets();
    
    jclass caret_class = env->FindClass("dev/jcode/core/editor/Caret");
    jmethodID constructor = env->GetMethodID(caret_class, "<init>", "(III)V");
    
    jobjectArray result = env->NewObjectArray(carets.size(), caret_class, nullptr);
    
    for (size_t i = 0; i < carets.size(); ++i) {
        jobject caret_obj = env->NewObject(caret_class, constructor,
                                           carets[i].anchor,
                                           carets[i].head,
                                           carets[i].preferred_column);
        env->SetObjectArrayElement(result, i, caret_obj);
        env->DeleteLocalRef(caret_obj);
    }
    
    return result;
}

JNIEXPORT void JNICALL
Java_dev_jcode_core_editor_EditorState_nativeSetViewport(JNIEnv* env, jobject thiz,
                                                           jint scroll_y, jint scroll_x,
                                                           jint width_px, jint height_px,
                                                           jint line_height_px) {
    EditorState* state = getEditorState(env, thiz);
    if (!state) return;

    Viewport viewport;
    viewport.scroll_y = scroll_y;
    viewport.scroll_x = scroll_x;
    viewport.width_px = width_px;
    viewport.height_px = height_px;
    viewport.line_height_px = line_height_px;
    
    state->set_viewport(viewport);
}

JNIEXPORT void JNICALL
Java_dev_jcode_core_editor_EditorState_nativeScrollTo(JNIEnv* env, jobject thiz,
                                                        jint line, jint column,
                                                        jint line_height_px) {
    EditorState* state = getEditorState(env, thiz);
    if (!state) return;
    
    state->scroll_to(line, column, line_height_px);
}

JNIEXPORT void JNICALL
Java_dev_jcode_core_editor_EditorState_nativeAddFold(JNIEnv* env, jobject thiz,
                                                        jint start_line, jint end_line,
                                                        jstring summary) {
    EditorState* state = getEditorState(env, thiz);
    if (!state) return;

    const char* summary_chars = summary ? env->GetStringUTFChars(summary, nullptr) : "";
    std::string summary_str(summary_chars);
    if (summary) env->ReleaseStringUTFChars(summary, summary_chars);
    
    FoldRange fold(start_line, end_line, summary_str);
    state->add_fold(fold);
}

JNIEXPORT void JNICALL
Java_dev_jcode_core_editor_EditorState_nativeRemoveFold(JNIEnv* env, jobject thiz,
                                                          jint start_line, jint end_line) {
    EditorState* state = getEditorState(env, thiz);
    if (!state) return;
    
    FoldRange fold(start_line, end_line);
    state->remove_fold(fold);
}

JNIEXPORT void JNICALL
Java_dev_jcode_core_editor_EditorState_nativeToggleFold(JNIEnv* env, jobject thiz,
                                                          jint start_line, jint end_line) {
    EditorState* state = getEditorState(env, thiz);
    if (!state) return;
    
    FoldRange fold(start_line, end_line);
    state->toggle_fold(fold);
}

JNIEXPORT void JNICALL
Java_dev_jcode_core_editor_EditorState_nativeSetReadOnly(JNIEnv* env, jobject thiz,
                                                           jboolean read_only) {
    EditorState* state = getEditorState(env, thiz);
    if (!state) return;
    
    state->set_read_only(read_only);
}

JNIEXPORT jboolean JNICALL
Java_dev_jcode_core_editor_EditorState_nativeIsReadOnly(JNIEnv* env, jobject thiz) {
    EditorState* state = getEditorState(env, thiz);
    if (!state) return false;
    
    return state->is_read_only();
}

// UndoManager JNI methods

JNIEXPORT jlong JNICALL
Java_dev_jcode_core_editor_UndoManager_nativeCreate(JNIEnv* env, jobject thiz,
                                                      jint max_groups,
                                                      jint max_inverted_bytes) {
    UndoManager* manager = new UndoManager(max_groups, max_inverted_bytes);
    return reinterpret_cast<jlong>(manager);
}

JNIEXPORT void JNICALL
Java_dev_jcode_core_editor_UndoManager_nativeDestroy(JNIEnv* env, jobject thiz) {
    UndoManager* manager = getUndoManager(env, thiz);
    if (manager) {
        delete manager;
        setUndoManager(env, thiz, nullptr);
    }
}

JNIEXPORT void JNICALL
Java_dev_jcode_core_editor_UndoManager_nativeBeginComposing(JNIEnv* env, jobject thiz) {
    UndoManager* manager = getUndoManager(env, thiz);
    if (manager) {
        manager->begin_composing();
    }
}

JNIEXPORT void JNICALL
Java_dev_jcode_core_editor_UndoManager_nativeEndComposing(JNIEnv* env, jobject thiz) {
    UndoManager* manager = getUndoManager(env, thiz);
    if (manager) {
        manager->end_composing();
    }
}

JNIEXPORT void JNICALL
Java_dev_jcode_core_editor_UndoManager_nativeFlushGroup(JNIEnv* env, jobject thiz) {
    UndoManager* manager = getUndoManager(env, thiz);
    if (manager) {
        manager->flush_group();
    }
}

JNIEXPORT jboolean JNICALL
Java_dev_jcode_core_editor_UndoManager_nativeCanUndo(JNIEnv* env, jobject thiz) {
    UndoManager* manager = getUndoManager(env, thiz);
    if (!manager) return false;
    
    return manager->can_undo();
}

JNIEXPORT jboolean JNICALL
Java_dev_jcode_core_editor_UndoManager_nativeCanRedo(JNIEnv* env, jobject thiz) {
    UndoManager* manager = getUndoManager(env, thiz);
    if (!manager) return false;
    
    return manager->can_redo();
}

JNIEXPORT void JNICALL
Java_dev_jcode_core_editor_UndoManager_nativeClear(JNIEnv* env, jobject thiz) {
    UndoManager* manager = getUndoManager(env, thiz);
    if (manager) {
        manager->clear();
    }
}

}  // extern "C"
