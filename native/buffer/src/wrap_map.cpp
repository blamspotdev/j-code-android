#include "wrap_map.h"

#include <algorithm>

namespace jcode {

namespace {

constexpr char16_t kReplacement = 0xFFFD;

bool isLowSurrogate(uint32_t c) { return (c & 0xFC00u) == 0xDC00u; }

bool isContinuation(uint8_t b) { return (b & 0xC0u) == 0x80u; }

/**
 * Decodes UTF-8 into UTF-16 the way `String(bytes, UTF_8)` does, because the resulting code-unit
 * count is what the wrap columns index. Malformed input follows the JDK's CharsetDecoder REPLACE
 * behaviour: one U+FFFD per malformed sequence, skipping that sequence's maximal valid subpart —
 * so a corrupt byte shifts the layout by the same amount on both sides of the differential test.
 */
void decodeUtf16(const uint8_t* data, size_t n, std::vector<char16_t>* out) {
    out->clear();
    size_t i = 0;
    while (i < n) {
        const uint8_t b0 = data[i];
        if (b0 < 0x80) {
            out->push_back(static_cast<char16_t>(b0));
            i += 1;
            continue;
        }

        // Per-lead-byte continuation ranges; the tightened second-byte bounds are what reject
        // overlong encodings, UTF-16 surrogates and anything past U+10FFFF.
        size_t need = 0;
        uint8_t lo = 0x80;
        uint8_t hi = 0xBF;
        uint32_t cp = 0;
        if (b0 >= 0xC2 && b0 <= 0xDF) {
            need = 1;
            cp = b0 & 0x1Fu;
        } else if (b0 >= 0xE0 && b0 <= 0xEF) {
            need = 2;
            cp = b0 & 0x0Fu;
            if (b0 == 0xE0) lo = 0xA0;
            if (b0 == 0xED) hi = 0x9F;
        } else if (b0 >= 0xF0 && b0 <= 0xF4) {
            need = 3;
            cp = b0 & 0x07u;
            if (b0 == 0xF0) lo = 0x90;
            if (b0 == 0xF4) hi = 0x8F;
        } else {
            // 0x80-0xBF (stray continuation), 0xC0/0xC1 (overlong), 0xF5-0xFF (out of range).
            out->push_back(kReplacement);
            i += 1;
            continue;
        }

        // Consume continuations, stopping at the first byte that breaks the sequence so the
        // replacement skips exactly the maximal valid subpart.
        size_t k = 0;
        bool ok = true;
        while (k < need) {
            if (i + 1 + k >= n) { ok = false; break; }
            const uint8_t b = data[i + 1 + k];
            const bool in_range = k == 0 ? (b >= lo && b <= hi) : isContinuation(b);
            if (!in_range) { ok = false; break; }
            cp = (cp << 6) | (b & 0x3Fu);
            k++;
        }
        if (!ok) {
            out->push_back(kReplacement);
            i += 1 + k;  // lead + the continuations that were valid
            continue;
        }

        if (cp >= 0x10000u) {
            const uint32_t v = cp - 0x10000u;
            out->push_back(static_cast<char16_t>(0xD800u + (v >> 10)));
            out->push_back(static_cast<char16_t>(0xDC00u + (v & 0x3FFu)));
        } else {
            out->push_back(static_cast<char16_t>(cp));
        }
        i += 1 + need;
    }
}

/**
 * Row-break scan for one line, appending each row's start column to [starts]. A 1:1 port of
 * WrapMap.computeRowStarts: break after the last space/tab that fits, else hard-break at the
 * width, never between a surrogate pair, and always advance.
 *
 * Templated over the unit so an all-ASCII line can run straight off the raw bytes (where a byte
 * index *is* the UTF-16 column) without materialising a decoded copy.
 */
template <typename Unit>
size_t computeRowStarts(const Unit* text, size_t len, int32_t cpr, std::vector<int32_t>* starts) {
    if (cpr <= 0 || len <= static_cast<size_t>(cpr)) {
        starts->push_back(0);
        return 1;
    }
    const size_t width = static_cast<size_t>(cpr);
    size_t rows = 1;
    starts->push_back(0);
    size_t start = 0;
    while (start + width < len) {
        size_t hard_end = start + width;
        if (isLowSurrogate(static_cast<uint32_t>(text[hard_end]))) hard_end--;
        size_t brk = 0;
        bool found = false;
        size_t j = hard_end;
        while (j > start) {
            const Unit c = text[j - 1];
            if (c == static_cast<Unit>(' ') || c == static_cast<Unit>('\t')) {
                brk = j;
                found = true;
                break;
            }
            j--;
        }
        size_t next = (found && brk > start) ? brk : hard_end;
        if (next <= start) next = start + width;
        start = next;
        starts->push_back(static_cast<int32_t>(start));
        rows++;
    }
    return rows;
}

}  // namespace

void buildWrapLayout(const Snapshot& snapshot, int32_t chars_per_row, WrapLayout* out) {
    const size_t line_count = std::max<size_t>(1, snapshot.lineCount());
    out->line_count = line_count;
    out->cum_rows.clear();
    out->line_len.clear();
    out->row_starts.clear();
    out->cum_rows.reserve(line_count + 1);
    out->line_len.reserve(line_count);
    out->row_starts.reserve(line_count);

    // Both scratch buffers are reused across lines: the Kotlin build allocated a String and an
    // IntArray per line, which is the bulk of what this port removes.
    std::vector<uint8_t> bytes;
    std::vector<char16_t> utf16;

    size_t rows = 0;
    for (size_t line = 0; line < line_count; ++line) {
        out->cum_rows.push_back(static_cast<int32_t>(rows));

        const auto range = snapshot.lineAt(static_cast<int64_t>(line));
        const size_t byte_len = range.second > range.first ? range.second - range.first : 0;
        if (byte_len > bytes.size()) bytes.resize(byte_len);
        if (byte_len > 0) {
            snapshot.readRange(static_cast<int64_t>(range.first), static_cast<int64_t>(range.second),
                               bytes.data(), byte_len);
        }

        const bool ascii =
            std::none_of(bytes.begin(), bytes.begin() + static_cast<long>(byte_len),
                         [](uint8_t b) { return b >= 0x80; });
        if (ascii) {
            out->line_len.push_back(static_cast<int32_t>(byte_len));
            rows += computeRowStarts(bytes.data(), byte_len, chars_per_row, &out->row_starts);
        } else {
            decodeUtf16(bytes.data(), byte_len, &utf16);
            out->line_len.push_back(static_cast<int32_t>(utf16.size()));
            rows += computeRowStarts(utf16.data(), utf16.size(), chars_per_row, &out->row_starts);
        }
    }
    out->cum_rows.push_back(static_cast<int32_t>(rows));
    out->total_rows = rows;
}

}  // namespace jcode
