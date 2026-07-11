#include <jni.h>
#include "piece_tree.h"
#include <android/log.h>

#define LOG_TAG "JCodeBuffer"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

using namespace jcode;

// Field IDs resolved once in JNI_OnLoad: GetFieldID-by-name per call is measurable overhead on
// the per-keystroke and per-line paths. The Kotlin field names are part of the JNI ABI (see the
// nativeHandle comments in Buffer.kt).
namespace {
jfieldID g_buffer_handle_field = nullptr;
jfieldID g_snapshot_handle_field = nullptr;
jfieldID g_op_type_field = nullptr;
jfieldID g_op_offset_field = nullptr;
jfieldID g_op_length_field = nullptr;
jfieldID g_op_data_field = nullptr;

PieceTreeBuffer* getBuffer(JNIEnv* env, jobject thiz) {
    return reinterpret_cast<PieceTreeBuffer*>(env->GetLongField(thiz, g_buffer_handle_field));
}

Snapshot* getSnapshot(JNIEnv* env, jobject thiz) {
    return reinterpret_cast<Snapshot*>(env->GetLongField(thiz, g_snapshot_handle_field));
}

jlong pack(size_t hi, size_t lo) {
    return static_cast<jlong>((static_cast<uint64_t>(hi) << 32) | static_cast<uint64_t>(lo & 0xFFFFFFFFu));
}
}  // namespace

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;

    jclass buffer = env->FindClass("dev/jcode/core/buffer/Buffer");
    jclass snapshot = env->FindClass("dev/jcode/core/buffer/Snapshot");
    jclass op = env->FindClass("dev/jcode/core/buffer/NativeEditOp");
    if (!buffer || !snapshot || !op) return JNI_ERR;

    g_buffer_handle_field = env->GetFieldID(buffer, "nativeHandle", "J");
    g_snapshot_handle_field = env->GetFieldID(snapshot, "nativeHandle", "J");
    g_op_type_field = env->GetFieldID(op, "type", "I");
    g_op_offset_field = env->GetFieldID(op, "offset", "J");
    g_op_length_field = env->GetFieldID(op, "length", "J");
    g_op_data_field = env->GetFieldID(op, "data", "[B");
    if (!g_buffer_handle_field || !g_snapshot_handle_field || !g_op_type_field ||
        !g_op_offset_field || !g_op_length_field || !g_op_data_field) {
        return JNI_ERR;
    }
    return JNI_VERSION_1_6;
}

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
Java_dev_jcode_core_buffer_Buffer_nativeByteLength(JNIEnv* env, jobject thiz) {
    PieceTreeBuffer* buffer = getBuffer(env, thiz);
    return buffer ? static_cast<jlong>(buffer->byteLength()) : 0;
}

JNIEXPORT jlong JNICALL
Java_dev_jcode_core_buffer_Buffer_nativeLineCount(JNIEnv* env, jobject thiz) {
    PieceTreeBuffer* buffer = getBuffer(env, thiz);
    return buffer ? static_cast<jlong>(buffer->lineCount()) : 1;
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
    ops.reserve(count);

    for (jsize i = 0; i < count; ++i) {
        jobject editObj = env->GetObjectArrayElement(editOps, i);

        EditOp op;
        op.type = static_cast<EditOp::Type>(env->GetIntField(editObj, g_op_type_field));
        op.offset = env->GetLongField(editObj, g_op_offset_field);
        op.length = env->GetLongField(editObj, g_op_length_field);

        if (op.type == EditOp::INSERT) {
            jbyteArray dataArray = (jbyteArray)env->GetObjectField(editObj, g_op_data_field);
            if (dataArray) {
                jsize dataLen = env->GetArrayLength(dataArray);
                jbyte* dataBytes = env->GetByteArrayElements(dataArray, nullptr);
                op.data.assign(dataBytes, dataBytes + dataLen);
                env->ReleaseByteArrayElements(dataArray, dataBytes, JNI_ABORT);
                env->DeleteLocalRef(dataArray);
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
    jbyte* outBytes = reinterpret_cast<jbyte*>(env->GetPrimitiveArrayCritical(out, nullptr));
    if (!outBytes) return 0;
    size_t written = snapshot->readRange(start, end, reinterpret_cast<uint8_t*>(outBytes),
                                         static_cast<size_t>(outLen));
    env->ReleasePrimitiveArrayCritical(out, outBytes, written > 0 ? 0 : JNI_ABORT);

    return static_cast<jint>(written);
}

// Batched viewport read: fills `out` with the concatenated bytes of `count` lines starting at
// `firstLine` (each range excludes its newline, mirroring lineAt), `outStarts[i]` with each
// line's offset inside `out` (plus an end sentinel), and `bufferStarts[i]` with each line's byte
// offset in the buffer. Returns the total bytes required; the caller re-calls with a larger `out`
// when that exceeds its capacity. One crossing replaces 2 JNI calls + 1 allocation per line per
// frame in the editor renderer.
JNIEXPORT jlong JNICALL
Java_dev_jcode_core_buffer_Snapshot_nativeReadLines(JNIEnv* env, jobject thiz,
                                                     jlong firstLine, jint count,
                                                     jbyteArray out, jintArray outStarts,
                                                     jintArray bufferStarts) {
    Snapshot* snapshot = getSnapshot(env, thiz);
    if (!snapshot || count <= 0) return 0;

    std::vector<std::pair<size_t, size_t>> ranges(static_cast<size_t>(count));
    size_t total = 0;
    for (jint i = 0; i < count; ++i) {
        ranges[static_cast<size_t>(i)] = snapshot->lineAt(firstLine + i);
        total += ranges[static_cast<size_t>(i)].second - ranges[static_cast<size_t>(i)].first;
    }
    if (total > static_cast<size_t>(env->GetArrayLength(out))) return static_cast<jlong>(total);

    std::vector<jint> starts(static_cast<size_t>(count) + 1);
    std::vector<jint> bufStarts(static_cast<size_t>(count));
    jbyte* outBytes = reinterpret_cast<jbyte*>(env->GetPrimitiveArrayCritical(out, nullptr));
    if (!outBytes) return static_cast<jlong>(total);
    size_t cursor = 0;
    for (jint i = 0; i < count; ++i) {
        const auto& range = ranges[static_cast<size_t>(i)];
        starts[static_cast<size_t>(i)] = static_cast<jint>(cursor);
        bufStarts[static_cast<size_t>(i)] = static_cast<jint>(range.first);
        const size_t len = range.second - range.first;
        if (len > 0) {
            snapshot->readRange(static_cast<int64_t>(range.first), static_cast<int64_t>(range.second),
                                reinterpret_cast<uint8_t*>(outBytes) + cursor, len);
        }
        cursor += len;
    }
    starts[static_cast<size_t>(count)] = static_cast<jint>(cursor);
    env->ReleasePrimitiveArrayCritical(out, outBytes, 0);
    env->SetIntArrayRegion(outStarts, 0, count + 1, starts.data());
    env->SetIntArrayRegion(bufferStarts, 0, count, bufStarts.data());
    return static_cast<jlong>(total);
}

// (line << 32) | column — one JNI crossing and one computation for the pair.
JNIEXPORT jlong JNICALL
Java_dev_jcode_core_buffer_Snapshot_nativeOffsetToLineColumn(JNIEnv* env, jobject thiz, jlong offset) {
    Snapshot* snapshot = getSnapshot(env, thiz);
    if (!snapshot) return 0;
    auto [line, col] = snapshot->offsetToLineColumn(offset);
    return pack(line, col);
}

JNIEXPORT jlong JNICALL
Java_dev_jcode_core_buffer_Snapshot_nativeLineColumnToOffset(JNIEnv* env, jobject thiz,
                                                              jlong line, jlong column) {
    Snapshot* snapshot = getSnapshot(env, thiz);
    if (!snapshot) return 0;
    return snapshot->lineColumnToOffset(line, column);
}

// (start << 32) | end of the line's byte range — one JNI crossing for lineAt.
JNIEXPORT jlong JNICALL
Java_dev_jcode_core_buffer_Snapshot_nativeLineRange(JNIEnv* env, jobject thiz, jlong line) {
    Snapshot* snapshot = getSnapshot(env, thiz);
    if (!snapshot) return 0;
    auto [start, end] = snapshot->lineAt(line);
    return pack(start, end);
}

JNIEXPORT jint JNICALL
Java_dev_jcode_native_buffer_BufferNativeModule_nativeInit(JNIEnv* env, jobject thiz) {
    LOGI("Native buffer module initialized");
    return 1;
}

}  // extern "C"
