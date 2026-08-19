#include <jni.h>

#include <algorithm>
#include <string>
#include <vector>

#include "highlight.h"
#include "piece_tree.h"

using namespace jcode;

namespace {

std::vector<std::string> toStrings(JNIEnv* env, jobjectArray arr) {
    std::vector<std::string> out;
    if (!arr) return out;
    const jsize count = env->GetArrayLength(arr);
    out.reserve(static_cast<size_t>(count));
    for (jsize i = 0; i < count; ++i) {
        jstring str = static_cast<jstring>(env->GetObjectArrayElement(arr, i));
        if (str) {
            const char* chars = env->GetStringUTFChars(str, nullptr);
            out.emplace_back(chars);
            env->ReleaseStringUTFChars(str, chars);
            env->DeleteLocalRef(str);
        }
    }
    return out;
}

std::string toString(JNIEnv* env, jstring str) {
    if (!str) return std::string();
    const char* chars = env->GetStringUTFChars(str, nullptr);
    std::string out(chars);
    env->ReleaseStringUTFChars(str, chars);
    return out;
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_dev_jcode_core_buffer_NativeHighlighter_nativeCreateProfile(
    JNIEnv* env, jobject thiz, jobjectArray lineComments, jstring blockStart, jstring blockEnd,
    jobjectArray delimiters, jobjectArray keywords, jobjectArray types, jint sep, jboolean sections) {
    auto* profile = new HighlightProfile();
    for (auto& lc : toStrings(env, lineComments)) {
        if (!lc.empty()) profile->line_comments.push_back(std::move(lc));
    }
    profile->block_start = toString(env, blockStart);
    profile->block_end = toString(env, blockEnd);
    profile->delimiters = toStrings(env, delimiters);
    profile->keywords = toStrings(env, keywords);
    profile->types = toStrings(env, types);
    std::sort(profile->keywords.begin(), profile->keywords.end());
    std::sort(profile->types.begin(), profile->types.end());
    profile->sep = static_cast<uint8_t>(sep);
    profile->sections = sections == JNI_TRUE;
    return reinterpret_cast<jlong>(profile);
}

JNIEXPORT void JNICALL
Java_dev_jcode_core_buffer_NativeHighlighter_nativeDestroyProfile(JNIEnv* env, jclass clazz, jlong handle) {
    delete reinterpret_cast<HighlightProfile*>(handle);
}

JNIEXPORT jintArray JNICALL
Java_dev_jcode_core_buffer_NativeHighlighter_nativeHighlight(
    JNIEnv* env, jobject thiz, jlong snapshotHandle, jlong profileHandle, jint mode, jintArray palette) {
    Snapshot* snapshot = reinterpret_cast<Snapshot*>(snapshotHandle);
    if (!snapshot || !palette || env->GetArrayLength(palette) < 11) return nullptr;

    HighlightPalette pal;
    env->GetIntArrayRegion(palette, 0, 11, reinterpret_cast<jint*>(&pal));

    // Materialize the snapshot's bytes natively — the text never crosses the JNI boundary.
    const size_t len = snapshot->byteLength();
    std::vector<uint8_t> text(len);
    if (len > 0) snapshot->readRange(0, static_cast<int64_t>(len), text.data(), len);

    std::vector<int32_t> out;
    out.reserve(4096);
    highlight_run(text.data(), len, static_cast<HighlightMode>(mode),
                  reinterpret_cast<const HighlightProfile*>(profileHandle), pal, out);

    jintArray result = env->NewIntArray(static_cast<jsize>(out.size()));
    if (!result) return nullptr;
    if (!out.empty()) env->SetIntArrayRegion(result, 0, static_cast<jsize>(out.size()), out.data());
    return result;
}

}  // extern "C"
