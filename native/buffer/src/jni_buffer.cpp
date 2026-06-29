#include <jni.h>
#include "piece_tree.h"
#include <android/log.h>

#define LOG_TAG "JCodeBuffer"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

using namespace jcode;

// Helper to get native handle from Java object
static PieceTreeBuffer* getBuffer(JNIEnv* env, jobject thiz) {
    jclass clazz = env->GetObjectClass(thiz);
    jfieldID fid = env->GetFieldID(clazz, "nativeHandle", "J");
    return reinterpret_cast<PieceTreeBuffer*>(env->GetLongField(thiz, fid));
}

static Snapshot* getSnapshot(JNIEnv* env, jobject thiz) {
    jclass clazz = env->GetObjectClass(thiz);
    jfieldID fid = env->GetFieldID(clazz, "nativeHandle", "J");
    return reinterpret_cast<Snapshot*>(env->GetLongField(thiz, fid));
}

extern "C" {

// Buffer JNI methods

JNIEXPORT jlong JNICALL
Java_dev_jcode_core_buffer_Buffer_nativeOpenFromBytes(JNIEnv* env, jobject thiz, jbyteArray data) {
    jsize length = env->GetArrayLength(data);
    jbyte* bytes = env->GetByteArrayElements(data, nullptr);

    PieceTreeBuffer* buffer = PieceTreeBuffer::openFromBytes(
        reinterpret_cast<const uint8_t*>(bytes), length);

    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);

    if (!buffer) {
        LOGE("Failed to open buffer from bytes");
        return 0;
    }

    return reinterpret_cast<jlong>(buffer);
}

JNIEXPORT jlong JNICALL
Java_dev_jcode_core_buffer_Buffer_nativeOpenFromFd(JNIEnv* env, jobject thiz, jint fd) {
    PieceTreeBuffer* buffer = PieceTreeBuffer::openFromFd(fd);
    if (!buffer) {
        LOGE("Failed to open buffer from fd %d", fd);
        return 0;
    }
    return reinterpret_cast<jlong>(buffer);
}

// Static close-by-handle: invoked by the Kotlin Cleaner with only the primitive handle, so the
// cleanup never needs (and never retains) the Buffer object. Mirrors PtyProcess.nativeCloseByHandle.
JNIEXPORT void JNICALL
Java_dev_jcode_core_buffer_Buffer_nativeCloseByHandle(JNIEnv* env, jclass clazz, jlong handle) {
    if (handle == 0) return;
    PieceTreeBuffer* buffer = reinterpret_cast<PieceTreeBuffer*>(handle);
    buffer->close();
    delete buffer;
}

JNIEXPORT jlong JNICALL
Java_dev_jcode_core_buffer_Buffer_nativeSnapshot(JNIEnv* env, jobject thiz) {
    PieceTreeBuffer* buffer = getBuffer(env, thiz);
    if (!buffer) return 0;

    Snapshot* snapshot = buffer->snapshot();
    return reinterpret_cast<jlong>(snapshot);
}

JNIEXPORT jlong JNICALL
Java_dev_jcode_core_buffer_Buffer_nativeApplyEdits(JNIEnv* env, jobject thiz,
                                                    jobjectArray editOps) {
    PieceTreeBuffer* buffer = getBuffer(env, thiz);
    if (!buffer) return 0;

    std::vector<EditOp> ops;
    jsize count = env->GetArrayLength(editOps);

    for (jsize i = 0; i < count; ++i) {
        jobject editObj = env->GetObjectArrayElement(editOps, i);
        jclass editClass = env->GetObjectClass(editObj);

        jfieldID typeField = env->GetFieldID(editClass, "type", "I");
        jfieldID offsetField = env->GetFieldID(editClass, "offset", "J");
        jfieldID lengthField = env->GetFieldID(editClass, "length", "J");
        jfieldID dataField = env->GetFieldID(editClass, "data", "[B");

        EditOp op;
        op.type = static_cast<EditOp::Type>(env->GetIntField(editObj, typeField));
        op.offset = env->GetLongField(editObj, offsetField);
        op.length = env->GetLongField(editObj, lengthField);

        if (op.type == EditOp::INSERT) {
            jbyteArray dataArray = (jbyteArray)env->GetObjectField(editObj, dataField);
            if (dataArray) {
                jsize dataLen = env->GetArrayLength(dataArray);
                jbyte* dataBytes = env->GetByteArrayElements(dataArray, nullptr);
                op.data.assign(dataBytes, dataBytes + dataLen);
                env->ReleaseByteArrayElements(dataArray, dataBytes, JNI_ABORT);
            }
        }

        ops.push_back(std::move(op));
        env->DeleteLocalRef(editObj);
    }

    Snapshot* snapshot = buffer->applyEdits(ops);
    return reinterpret_cast<jlong>(snapshot);
}

// Snapshot JNI methods

// Static decRef-by-handle for the Kotlin Cleaner (see Buffer.nativeCloseByHandle).
JNIEXPORT void JNICALL
Java_dev_jcode_core_buffer_Snapshot_nativeCloseByHandle(JNIEnv* env, jclass clazz, jlong handle) {
    if (handle == 0) return;
    Snapshot* snapshot = reinterpret_cast<Snapshot*>(handle);
    snapshot->decRef();
}

JNIEXPORT jlong JNICALL
Java_dev_jcode_core_buffer_Snapshot_nativeByteLength(JNIEnv* env, jobject thiz) {
    Snapshot* snapshot = getSnapshot(env, thiz);
    return snapshot ? snapshot->byteLength() : 0;
}

JNIEXPORT jlong JNICALL
Java_dev_jcode_core_buffer_Snapshot_nativeLineCount(JNIEnv* env, jobject thiz) {
    Snapshot* snapshot = getSnapshot(env, thiz);
    return snapshot ? snapshot->lineCount() : 0;
}

JNIEXPORT jint JNICALL
Java_dev_jcode_core_buffer_Snapshot_nativeReadRange(JNIEnv* env, jobject thiz,
                                                     jlong start, jlong end,
                                                     jbyteArray out) {
    Snapshot* snapshot = getSnapshot(env, thiz);
    if (!snapshot) return 0;

    jsize outLen = env->GetArrayLength(out);
    jsize toRead = std::min(static_cast<jsize>(end - start), outLen);

    jbyte* outBytes = env->GetByteArrayElements(out, nullptr);
    snapshot->readRange(start, start + toRead, reinterpret_cast<uint8_t*>(outBytes));
    env->ReleaseByteArrayElements(out, outBytes, 0);

    return toRead;
}

JNIEXPORT jlong JNICALL
Java_dev_jcode_core_buffer_Snapshot_nativeOffsetToLine(JNIEnv* env, jobject thiz, jlong offset) {
    Snapshot* snapshot = getSnapshot(env, thiz);
    if (!snapshot) return 0;
    auto [line, col] = snapshot->offsetToLineColumn(offset);
    return line;
}

JNIEXPORT jlong JNICALL
Java_dev_jcode_core_buffer_Snapshot_nativeOffsetToColumn(JNIEnv* env, jobject thiz, jlong offset) {
    Snapshot* snapshot = getSnapshot(env, thiz);
    if (!snapshot) return 0;
    auto [line, col] = snapshot->offsetToLineColumn(offset);
    return col;
}

JNIEXPORT jlong JNICALL
Java_dev_jcode_core_buffer_Snapshot_nativeLineColumnToOffset(JNIEnv* env, jobject thiz,
                                                              jlong line, jlong column) {
    Snapshot* snapshot = getSnapshot(env, thiz);
    if (!snapshot) return 0;
    return snapshot->lineColumnToOffset(line, column);
}

JNIEXPORT jlong JNICALL
Java_dev_jcode_core_buffer_Snapshot_nativeLineStart(JNIEnv* env, jobject thiz, jlong line) {
    Snapshot* snapshot = getSnapshot(env, thiz);
    if (!snapshot) return 0;
    auto [start, end] = snapshot->lineAt(line);
    return start;
}

JNIEXPORT jlong JNICALL
Java_dev_jcode_core_buffer_Snapshot_nativeLineEnd(JNIEnv* env, jobject thiz, jlong line) {
    Snapshot* snapshot = getSnapshot(env, thiz);
    if (!snapshot) return 0;
    auto [start, end] = snapshot->lineAt(line);
    return end;
}

JNIEXPORT jint JNICALL
Java_dev_jcode_native_buffer_BufferNativeModule_nativeInit(JNIEnv* env, jobject thiz) {
    LOGI("Native buffer module initialized");
    return 1;
}

}  // extern "C"
