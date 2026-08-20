#include <jni.h>

#include <vector>

#include "piece_tree.h"
#include "wrap_map.h"

using namespace jcode;

extern "C" {

/**
 * Builds the wrap layout for a native Snapshot and returns it as one packed int array:
 *
 *   [0]                       lineCount
 *   [1]                       totalRows
 *   [2 .. 2+lineCount]        cumRows      (lineCount + 1 entries)
 *   [.. + lineCount]          lineLen      (lineCount entries, UTF-16 units)
 *   [.. + totalRows]          rowStarts    (totalRows entries)
 *
 * One array and one crossing for the whole document; the Kotlin side reads the regions in place
 * by base offset rather than slicing them apart. Returns null for a Kotlin-path snapshot, which
 * tells WrapMap to run its own reference build.
 */
JNIEXPORT jintArray JNICALL
Java_dev_blamspot_jcode_core_buffer_NativeWrap_nativeBuild(JNIEnv* env, jobject /*thiz*/,
                                                  jlong snapshotHandle, jint charsPerRow) {
    if (snapshotHandle == 0) return nullptr;
    const Snapshot* snapshot = reinterpret_cast<const Snapshot*>(snapshotHandle);

    WrapLayout layout;
    buildWrapLayout(*snapshot, charsPerRow, &layout);

    const size_t line_count = layout.line_count;
    const size_t total_rows = layout.total_rows;
    const size_t size = 2 + (line_count + 1) + line_count + total_rows;

    jintArray result = env->NewIntArray(static_cast<jsize>(size));
    if (!result) return nullptr;

    jint* out = reinterpret_cast<jint*>(env->GetPrimitiveArrayCritical(result, nullptr));
    if (!out) return nullptr;
    out[0] = static_cast<jint>(line_count);
    out[1] = static_cast<jint>(total_rows);
    size_t w = 2;
    for (size_t i = 0; i <= line_count; ++i) out[w++] = layout.cum_rows[i];
    for (size_t i = 0; i < line_count; ++i) out[w++] = layout.line_len[i];
    for (size_t i = 0; i < total_rows; ++i) out[w++] = layout.row_starts[i];
    env->ReleasePrimitiveArrayCritical(result, out, 0);

    return result;
}

}  // extern "C"
