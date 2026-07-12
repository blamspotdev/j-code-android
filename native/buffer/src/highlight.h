#pragma once

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace jcode {

/**
 * Native port of app/src/main/java/dev/jcode/editor/SyntaxHighlighter.kt — it must reproduce
 * that implementation's span output exactly (asserted by SyntaxHighlighterDifferentialTest on
 * device). Input is the buffer's UTF-8 bytes; offsets are emitted directly in byte space, so the
 * UTF-8 -> UTF-16 -> byte-offset mapping the Kotlin version pays per pass disappears.
 *
 * Unicode note: the Kotlin tokenizers classify with Character.isLetter/isLetterOrDigit. This port
 * decodes codepoints and classifies ASCII exactly; for non-ASCII it recognizes the common letter
 * blocks (Latin-1/extended, Greek, Cyrillic, Armenian, Hebrew, Arabic core, CJK, kana, Hangul).
 * Exotic scripts outside those blocks may color as plain text where Kotlin would call them
 * letters — a cosmetic approximation, pinned by the differential test's corpus.
 */

/** Palette slot order matches TokenPalette's constructor order (see NativeHighlighter.kt). */
struct HighlightPalette {
    int32_t keyword;
    int32_t type;
    int32_t string_;
    int32_t comment;
    int32_t number;
    int32_t function;
    int32_t variable;
    int32_t constant;
    int32_t property;
    int32_t operator_;
    int32_t annotation;
};

/** Language configuration marshalled once per pack (or once for the built-in generic set). */
struct HighlightProfile {
    std::vector<std::string> line_comments;   // non-empty entries only
    std::string block_start;                  // empty = none
    std::string block_end;                    // empty = none
    std::vector<std::string> delimiters;
    std::vector<std::string> keywords;        // sorted; doubles as boolWords for key-value mode
    std::vector<std::string> types;           // sorted
    uint8_t sep = ':';                        // key-value separator (':' or '=')
    bool sections = false;                    // color [section] headers (TOML/INI)
};

enum HighlightMode : int32_t {
    HIGHLIGHT_TOKENIZE = 0,
    HIGHLIGHT_MARKDOWN = 1,
    HIGHLIGHT_MARKUP = 2,
    HIGHLIGHT_KEYVALUE = 3,
    HIGHLIGHT_JSON = 4,
};

/**
 * Run [mode] over [text] and append [startByte, endByte, colorArgb, styleFlags] quadruples to
 * [out] in scan order (byte-sorted, matching the renderer's sweep). [profile] may be null for
 * the profile-free modes (markdown, markup, json).
 */
void highlight_run(const uint8_t* text, size_t n, HighlightMode mode,
                   const HighlightProfile* profile, const HighlightPalette& palette,
                   std::vector<int32_t>& out);

}  // namespace jcode
