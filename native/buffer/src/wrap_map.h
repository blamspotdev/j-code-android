#pragma once

#include <cstddef>
#include <cstdint>
#include <vector>

#include "piece_tree.h"

namespace jcode {

/**
 * Soft word-wrap layout, the native mirror of core/editor WrapMap.kt — it must reproduce that
 * implementation's row breaks exactly (WrapMapDifferentialTest fuzzes the two against each other).
 *
 * Columns are **UTF-16 code-unit indices**, not bytes and not codepoints: they index the Java line
 * String the renderer measures and draws, so the walk counts the way `String.length` does and a
 * codepoint above the BMP occupies two columns. The layout is computed straight from a Snapshot's
 * bytes — no line text ever crosses the JNI boundary.
 *
 * The three arrays are flat and parallel to the Kotlin side's packed representation: row k of
 * logical line l starts at column `row_starts[cum_rows[l] + k]`, and the line's last row ends at
 * `line_len[l]`.
 */
struct WrapLayout {
    size_t line_count = 0;
    size_t total_rows = 0;
    std::vector<int32_t> cum_rows;    // line_count + 1; cum_rows[line_count] == total_rows
    std::vector<int32_t> line_len;    // line_count, in UTF-16 code units
    std::vector<int32_t> row_starts;  // total_rows
};

/**
 * Builds the wrap layout for [snapshot] at [chars_per_row] columns. A non-positive
 * [chars_per_row] yields one row per logical line (matching the Kotlin guard).
 */
void buildWrapLayout(const Snapshot& snapshot, int32_t chars_per_row, WrapLayout* out);

}  // namespace jcode
