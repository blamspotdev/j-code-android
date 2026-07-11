#include "highlight.h"

#include <algorithm>
#include <cstring>
#include <string_view>

namespace jcode {

namespace {

// ---------------------------------------------------------------------------
// Codepoint decoding + classification (see the Unicode note in highlight.h).

uint32_t decodeAt(const uint8_t* s, size_t n, size_t i, size_t* len) {
    const uint8_t b = s[i];
    if (b < 0x80) { *len = 1; return b; }
    if ((b & 0xE0) == 0xC0 && i + 1 < n) { *len = 2; return (static_cast<uint32_t>(b & 0x1F) << 6) | (s[i + 1] & 0x3F); }
    if ((b & 0xF0) == 0xE0 && i + 2 < n) {
        *len = 3;
        return (static_cast<uint32_t>(b & 0x0F) << 12) | (static_cast<uint32_t>(s[i + 1] & 0x3F) << 6) | (s[i + 2] & 0x3F);
    }
    if ((b & 0xF8) == 0xF0 && i + 3 < n) {
        *len = 4;
        return (static_cast<uint32_t>(b & 0x07) << 18) | (static_cast<uint32_t>(s[i + 1] & 0x3F) << 12) |
               (static_cast<uint32_t>(s[i + 2] & 0x3F) << 6) | (s[i + 3] & 0x3F);
    }
    *len = 1;
    return 0xFFFD;
}

bool cpIsLetter(uint32_t cp) {
    if (cp < 0x80) return (cp >= 'A' && cp <= 'Z') || (cp >= 'a' && cp <= 'z');
    if (cp == 0xAA || cp == 0xB5 || cp == 0xBA) return true;
    if (cp >= 0xC0 && cp <= 0x2AF) return cp != 0xD7 && cp != 0xF7;
    if (cp >= 0x370 && cp <= 0x3FF) return cp != 0x374 && cp != 0x375 && cp != 0x37E && cp != 0x384 && cp != 0x385 && cp != 0x387;
    if (cp >= 0x400 && cp <= 0x52F) return true;
    if (cp >= 0x531 && cp <= 0x58F) return cp != 0x58A && cp != 0x589;
    if (cp >= 0x5D0 && cp <= 0x5EA) return true;
    if (cp >= 0x620 && cp <= 0x64A) return true;
    if (cp >= 0x1E00 && cp <= 0x1FFF) return true;
    if (cp >= 0x3041 && cp <= 0x30FF) return cp != 0x3097 && cp != 0x3098;
    if (cp >= 0x4E00 && cp <= 0x9FFF) return true;
    if (cp >= 0xAC00 && cp <= 0xD7A3) return true;
    return false;
}

bool cpIsDigit(uint32_t cp) { return cp >= '0' && cp <= '9'; }

bool cpIsLetterOrDigit(uint32_t cp) { return cpIsLetter(cp) || cpIsDigit(cp); }

// ---------------------------------------------------------------------------
// Byte-level helpers mirroring the Kotlin String operations.

bool startsWith(const uint8_t* s, size_t n, size_t i, const std::string& prefix) {
    return !prefix.empty() && i + prefix.size() <= n && memcmp(s + i, prefix.data(), prefix.size()) == 0;
}

// Kotlin text.indexOf(str, from): first index >= from, or npos.
size_t indexOf(const uint8_t* s, size_t n, size_t from, const char* needle, size_t needleLen) {
    if (from >= n || needleLen == 0 || needleLen > n - from) return std::string::npos;
    const void* found = memmem(s + from, n - from, needle, needleLen);
    return found ? static_cast<size_t>(static_cast<const uint8_t*>(found) - s) : std::string::npos;
}

size_t indexOfChar(const uint8_t* s, size_t n, size_t from, uint8_t ch) {
    if (from >= n) return std::string::npos;
    const void* found = memchr(s + from, ch, n - from);
    return found ? static_cast<size_t>(static_cast<const uint8_t*>(found) - s) : std::string::npos;
}

bool setContains(const std::vector<std::string>& sorted, std::string_view word) {
    auto it = std::lower_bound(sorted.begin(), sorted.end(), word,
                               [](const std::string& a, std::string_view b) { return std::string_view(a) < b; });
    return it != sorted.end() && std::string_view(*it) == word;
}

bool isSpaceOrTab(uint8_t b) { return b == ' ' || b == '\t'; }

bool isOperatorByte(uint8_t b) {
    switch (b) {
        case '+': case '-': case '*': case '/': case '%': case '=': case '<': case '>':
        case '!': case '&': case '|': case '^': case '~': case '?': case ':':
            return true;
        default:
            return false;
    }
}

// ALL_CAPS identifier (at least one letter; only A-Z, 0-9, _), min length 2.
bool isConstantName(const uint8_t* s, size_t start, size_t end) {
    if (end - start < 2) return false;
    bool hasLetter = false;
    for (size_t i = start; i < end; ++i) {
        const uint8_t c = s[i];
        if (c >= 'A' && c <= 'Z') hasLetter = true;
        else if ((c >= '0' && c <= '9') || c == '_') continue;
        else return false;
    }
    return hasLetter;
}

struct Emitter {
    std::vector<int32_t>& out;
    void add(size_t start, size_t end, int32_t color) {
        if (end > start) {
            out.push_back(static_cast<int32_t>(start));
            out.push_back(static_cast<int32_t>(end));
            out.push_back(color);
            out.push_back(0);
        }
    }
};

// ---------------------------------------------------------------------------
// Mode 0: the shared state-machine tokenizer.

void runTokenize(const uint8_t* s, size_t n, const HighlightProfile& p,
                 const HighlightPalette& pal, Emitter& em) {
    size_t i = 0;
    while (i < n) {
        const uint8_t c = s[i];

        // line comment
        bool lineComment = false;
        for (const auto& lc : p.line_comments) {
            if (startsWith(s, n, i, lc)) { lineComment = true; break; }
        }
        if (lineComment) {
            size_t j = i;
            while (j < n && s[j] != '\n') j++;
            em.add(i, j, pal.comment); i = j; continue;
        }
        // block comment
        if (!p.block_start.empty() && !p.block_end.empty() && startsWith(s, n, i, p.block_start)) {
            const size_t end = indexOf(s, n, i + p.block_start.size(), p.block_end.data(), p.block_end.size());
            const size_t j = end == std::string::npos ? n : end + p.block_end.size();
            em.add(i, j, pal.comment); i = j; continue;
        }
        // string
        const std::string* delim = nullptr;
        for (const auto& d : p.delimiters) {
            if (startsWith(s, n, i, d)) { delim = &d; break; }
        }
        if (delim != nullptr) {
            const bool multiline = *delim == "`";
            size_t j = i + delim->size();
            while (j < n) {
                const uint8_t cj = s[j];
                if (cj == '\\') { j += 2; continue; }
                if (!multiline && cj == '\n') break;
                if (startsWith(s, n, j, *delim)) { j += delim->size(); break; }
                j++;
            }
            if (j > n) j = n;
            em.add(i, j, pal.string_); i = j; continue;
        }
        // annotation / decorator: @name
        if (c == '@' && i + 1 < n) {
            size_t nextLen;
            const uint32_t next = decodeAt(s, n, i + 1, &nextLen);
            if (cpIsLetter(next) || next == '_') {
                size_t j = i + 1;
                while (j < n) {
                    size_t len;
                    const uint32_t cp = decodeAt(s, n, j, &len);
                    if (cpIsLetterOrDigit(cp) || cp == '_') j += len; else break;
                }
                em.add(i, j, pal.annotation); i = j; continue;
            }
        }
        // number
        if (cpIsDigit(c)) {
            size_t j = i;
            while (j < n) {
                size_t len;
                const uint32_t cp = decodeAt(s, n, j, &len);
                if (cpIsLetterOrDigit(cp) || cp == '.' || cp == '_') j += len; else break;
            }
            em.add(i, j, pal.number); i = j; continue;
        }
        // identifier -> keyword / type / function / constant / property / variable
        size_t cLen;
        const uint32_t cp = decodeAt(s, n, i, &cLen);
        if (cpIsLetter(cp) || cp == '_') {
            size_t j = i;
            while (j < n) {
                size_t len;
                const uint32_t wcp = decodeAt(s, n, j, &len);
                if (cpIsLetterOrDigit(wcp) || wcp == '_') j += len; else break;
            }
            const std::string_view word(reinterpret_cast<const char*>(s) + i, j - i);
            size_t k = j;
            while (k < n && isSpaceOrTab(s[k])) k++;
            const bool isCall = k < n && s[k] == '(';
            // Backward scan over ASCII space/tab is byte-safe (they can't be UTF-8 tails).
            size_t pIdx = i;
            while (pIdx > 0 && isSpaceOrTab(s[pIdx - 1])) pIdx--;
            const bool isMember = pIdx > 0 && s[pIdx - 1] == '.';
            int32_t color;
            if (setContains(p.keywords, word)) color = pal.keyword;
            else if (setContains(p.types, word)) color = pal.type;
            else if (isCall) color = pal.function;
            else if (isConstantName(s, i, j)) color = pal.constant;
            else if (isMember) color = pal.property;
            else color = pal.variable;
            em.add(i, j, color); i = j; continue;
        }
        // operators; comments are handled above so '/' is safe here
        if (c < 0x80 && isOperatorByte(c)) {
            size_t j = i;
            while (j < n && s[j] < 0x80 && isOperatorByte(s[j])) j++;
            em.add(i, j, pal.operator_); i = j; continue;
        }
        i += cLen;
    }
}

// ---------------------------------------------------------------------------
// Mode 1: structural Markdown.

bool isHrLine(const uint8_t* s, size_t start, size_t end) {
    if (start >= end) return false;
    const uint8_t ch = s[start];
    if (ch != '-' && ch != '*' && ch != '_') return false;
    size_t count = 0;
    for (size_t i = start; i < end; ++i) {
        const uint8_t c = s[i];
        if (c == ch) count++;
        else if (c != ' ' && c != '\t') return false;
    }
    return count >= 3;
}

size_t listMarkerEnd(const uint8_t* s, size_t start, size_t end) {
    if (start >= end) return start;
    const uint8_t c = s[start];
    if ((c == '-' || c == '*' || c == '+') && start + 1 < end && isSpaceOrTab(s[start + 1])) {
        return start + 1;
    }
    if (cpIsDigit(c)) {
        size_t j = start;
        while (j < end && cpIsDigit(s[j])) j++;
        if (j < end && (s[j] == '.' || s[j] == ')') && j + 1 < end && isSpaceOrTab(s[j + 1])) {
            return j + 1;
        }
    }
    return start;
}

void runMarkdown(const uint8_t* s, size_t n, const HighlightPalette& pal, Emitter& em) {
    // Inline scan over [start, end): code spans, links/images, bold, then italic.
    auto inlineScan = [&](size_t start, size_t end) {
        size_t k = start;
        while (k < end) {
            const uint8_t ch = s[k];
            if (ch == '`') {
                size_t j = k + 1;
                while (j < end && s[j] != '`') j++;
                const size_t close = j < end ? j + 1 : end;
                em.add(k, close, pal.string_); k = close; continue;
            }
            if (ch == '[' || (ch == '!' && k + 1 < end && s[k + 1] == '[')) {
                const size_t open = ch == '!' ? k + 1 : k;
                const size_t closeB = indexOfChar(s, n, open + 1, ']');
                if (closeB != std::string::npos && closeB > open && closeB < end &&
                    closeB + 1 < end && s[closeB + 1] == '(') {
                    const size_t closeP = indexOfChar(s, n, closeB + 2, ')');
                    if (closeP != std::string::npos && closeP >= closeB + 2 && closeP < end) {
                        em.add(k, closeB + 1, pal.type);              // [text] (incl. leading !)
                        em.add(closeB + 1, closeP + 1, pal.string_);  // (url)
                        k = closeP + 1; continue;
                    }
                }
            }
            if ((ch == '*' || ch == '_') && k + 1 < end && s[k + 1] == ch) {
                const char marker[2] = { static_cast<char>(ch), static_cast<char>(ch) };
                const size_t j = indexOf(s, n, k + 2, marker, 2);
                if (j != std::string::npos && j >= k + 2 && j < end) {
                    em.add(k, j + 2, pal.constant); k = j + 2; continue;
                }
            }
            if (ch == '*' || ch == '_') {
                size_t t = k + 1;
                while (t < end && s[t] != ch) t++;
                if (t < end && t > k + 1) { em.add(k, t + 1, pal.property); k = t + 1; continue; }
            }
            k++;
        }
    };

    bool inFence = false;
    uint8_t fenceMarker = 0;
    size_t i = 0;
    while (i < n) {
        const size_t lineStart = i;
        size_t lineEnd = i;
        while (lineEnd < n && s[lineEnd] != '\n') lineEnd++;
        const size_t nextLine = lineEnd < n ? lineEnd + 1 : lineEnd;

        size_t t = lineStart;
        while (t < lineEnd && isSpaceOrTab(s[t])) t++;
        uint8_t fence = 0;
        if (t + 3 <= n && (memcmp(s + t, "```", 3) == 0)) fence = '`';
        else if (t + 3 <= n && (memcmp(s + t, "~~~", 3) == 0)) fence = '~';

        if (inFence) {
            em.add(lineStart, lineEnd, pal.string_);
            if (fence != 0 && fence == fenceMarker) inFence = false;
            i = nextLine; continue;
        }
        if (fence != 0) {
            em.add(lineStart, lineEnd, pal.string_);
            inFence = true; fenceMarker = fence;
            i = nextLine; continue;
        }
        // ATX heading (#..###### then space/eol)
        if (t < lineEnd && s[t] == '#') {
            size_t h = t;
            size_t hashes = 0;
            while (h < lineEnd && s[h] == '#') { h++; hashes++; }
            if (hashes >= 1 && hashes <= 6 && (h >= lineEnd || isSpaceOrTab(s[h]))) {
                em.add(lineStart, lineEnd, pal.keyword); i = nextLine; continue;
            }
        }
        // horizontal rule (>=3 of - * _)
        if (isHrLine(s, t, lineEnd)) {
            em.add(lineStart, lineEnd, pal.operator_); i = nextLine; continue;
        }
        // blockquote
        if (t < lineEnd && s[t] == '>') {
            size_t q = t;
            while (q < lineEnd && (s[q] == '>' || s[q] == ' ')) q++;
            em.add(t, q, pal.comment);
            inlineScan(q, lineEnd); i = nextLine; continue;
        }
        // list marker, then inline content
        const size_t mEnd = listMarkerEnd(s, t, lineEnd);
        size_t contentStart = t;
        if (mEnd > t) { em.add(t, mEnd, pal.operator_); contentStart = mEnd; }
        inlineScan(contentStart, lineEnd);
        i = nextLine;
    }
}

// ---------------------------------------------------------------------------
// Mode 2: structural markup (HTML/XML/SVG).

void runMarkup(const uint8_t* s, size_t n, const HighlightPalette& pal, Emitter& em) {
    auto isNameByteStart = [&](size_t idx, size_t* lenOut) {
        const uint32_t cp = decodeAt(s, n, idx, lenOut);
        return cpIsLetterOrDigit(cp) || cp == '-' || cp == '_' || cp == ':' || cp == '.';
    };

    size_t i = 0;
    while (i < n) {
        const uint8_t c = s[i];
        // comment <!-- ... -->
        if (startsWith(s, n, i, "<!--")) {
            const size_t end = indexOf(s, n, i + 4, "-->", 3);
            const size_t j = end == std::string::npos ? n : end + 3;
            em.add(i, j, pal.comment); i = j; continue;
        }
        // entity: &name; or &#123;
        if (c == '&') {
            size_t j = i + 1;
            while (j < n) {
                size_t len;
                const uint32_t cp = decodeAt(s, n, j, &len);
                if (cpIsLetterOrDigit(cp) || cp == '#') j += len; else break;
            }
            if (j < n && s[j] == ';') j++;
            if (j > i + 1) { em.add(i, j, pal.constant); i = j; continue; }
            i++; continue;
        }
        // tag: < ... >  (also </close>, <!doctype ...>, <?pi ...?>)
        if (c == '<') {
            size_t j = i + 1;
            em.add(i, i + 1, pal.operator_);  // '<'
            if (j < n && (s[j] == '/' || s[j] == '!' || s[j] == '?')) { em.add(j, j + 1, pal.operator_); j++; }
            while (j < n && isSpaceOrTab(s[j])) j++;
            const size_t nameStart = j;
            while (j < n) {
                size_t len;
                if (isNameByteStart(j, &len)) j += len; else break;
            }
            if (j > nameStart) em.add(nameStart, j, pal.keyword);  // tag name
            while (j < n && s[j] != '>') {
                const uint8_t a = s[j];
                if (a == ' ' || a == '\t' || a == '\n' || a == '\r') { j++; }
                else if (a == '/' || a == '?' || a == '=') { em.add(j, j + 1, pal.operator_); j++; }
                else if (a == '"' || a == '\'') {
                    size_t k = j + 1;
                    while (k < n && s[k] != a) k++;
                    const size_t close = k < n ? k + 1 : n;
                    em.add(j, close, pal.string_); j = close;  // attribute value
                } else {
                    size_t len;
                    if (isNameByteStart(j, &len)) {
                        const size_t attrStart = j;
                        j += len;
                        while (j < n) {
                            size_t l2;
                            if (isNameByteStart(j, &l2)) j += l2; else break;
                        }
                        em.add(attrStart, j, pal.property);  // attribute name
                    } else {
                        j++;
                    }
                }
            }
            if (j < n && s[j] == '>') { em.add(j, j + 1, pal.operator_); j++; }  // '>'
            i = j; continue;
        }
        i++;  // text content: uncolored
    }
}

// ---------------------------------------------------------------------------
// Mode 3: line-oriented key/value config languages (YAML, TOML, INI).

void runKeyValue(const uint8_t* s, size_t n, const HighlightProfile& p,
                 const HighlightPalette& pal, Emitter& em) {
    const uint8_t sep = p.sep;

    // Color a value range: quoted strings, numbers, boolean/null words, trailing inline comment.
    auto value = [&](size_t start, size_t end) {
        size_t k = start;
        while (k < end) {
            const uint8_t ch = s[k];
            if (k == start || isSpaceOrTab(s[k - 1])) {
                bool comment = false;
                for (const auto& lc : p.line_comments) {
                    if (startsWith(s, n, k, lc)) { comment = true; break; }
                }
                if (comment) { em.add(k, end, pal.comment); return; }
            }
            const std::string* d = nullptr;
            for (const auto& dd : p.delimiters) {
                if (startsWith(s, n, k, dd)) { d = &dd; break; }
            }
            if (d != nullptr) {
                size_t j = k + d->size();
                while (j < end && !startsWith(s, n, j, *d)) { if (s[j] == '\\') j++; j++; }
                const size_t close = j < end ? j + d->size() : end;
                em.add(k, close, pal.string_); k = close; continue;
            }
            if (cpIsDigit(ch) || (ch == '-' && k + 1 < end && cpIsDigit(s[k + 1]))) {
                size_t j = k + 1;
                while (j < end) {
                    size_t len;
                    const uint32_t cp = decodeAt(s, n, j, &len);
                    if (cpIsLetterOrDigit(cp) || cp == '.' || cp == ':' || cp == '+' || cp == '-' || cp == '_') j += len;
                    else break;
                }
                em.add(k, j, pal.number); k = j; continue;
            }
            size_t chLen;
            const uint32_t cp = decodeAt(s, n, k, &chLen);
            if (cpIsLetter(cp) || cp == '_' || cp == '~') {
                size_t j = k;
                while (j < end) {
                    size_t len;
                    const uint32_t wcp = decodeAt(s, n, j, &len);
                    if (cpIsLetterOrDigit(wcp) || wcp == '_' || wcp == '-' || wcp == '~') j += len; else break;
                }
                const std::string_view word(reinterpret_cast<const char*>(s) + k, j - k);
                if (setContains(p.keywords, word)) em.add(k, j, pal.keyword);
                k = j; continue;
            }
            k += chLen;
        }
    };

    size_t i = 0;
    while (i < n) {
        const size_t lineStart = i;
        size_t lineEnd = i;
        while (lineEnd < n && s[lineEnd] != '\n') lineEnd++;
        const size_t nextLine = lineEnd < n ? lineEnd + 1 : lineEnd;

        size_t t = lineStart;
        while (t < lineEnd && isSpaceOrTab(s[t])) t++;

        bool lineComment = false;
        if (t < lineEnd) {
            for (const auto& lc : p.line_comments) {
                if (startsWith(s, n, t, lc)) { lineComment = true; break; }
            }
        }

        if (t >= lineEnd) {
            // blank line
        } else if (lineComment) {
            em.add(t, lineEnd, pal.comment);
        } else if (p.sections && s[t] == '[') {
            em.add(t, lineEnd, pal.type);
        } else if (sep == ':' && (startsWith(s, n, t, "---") || startsWith(s, n, t, "..."))) {
            em.add(t, lineEnd, pal.operator_);
        } else {
            size_t c = t;
            // YAML list marker "- "
            if (sep == ':' && s[c] == '-' && c + 1 < lineEnd && isSpaceOrTab(s[c + 1])) {
                em.add(c, c + 1, pal.operator_); c++;
                while (c < lineEnd && isSpaceOrTab(s[c])) c++;
            }
            // Locate the key/value separator: for YAML the ':' must be followed by space/EOL.
            size_t sPos = c;
            while (sPos < lineEnd &&
                   !(s[sPos] == sep && (sep != ':' || sPos + 1 >= lineEnd || isSpaceOrTab(s[sPos + 1])))) {
                sPos++;
            }
            if (sPos < lineEnd && s[sPos] == sep && sPos > c) {
                size_t keyEnd = sPos;
                while (keyEnd > c && isSpaceOrTab(s[keyEnd - 1])) keyEnd--;
                em.add(c, keyEnd, pal.property);      // key
                em.add(sPos, sPos + 1, pal.operator_); // : or =
                value(sPos + 1, lineEnd);
            } else {
                value(c, lineEnd);
            }
        }
        i = nextLine;
    }
}

// ---------------------------------------------------------------------------
// Mode 4: built-in JSON / JSONC / JSON5.

void runJson(const uint8_t* s, size_t n, const HighlightPalette& pal, Emitter& em) {
    size_t i = 0;
    while (i < n) {
        const uint8_t c = s[i];
        if (c == '/' && i + 1 < n && s[i + 1] == '/') {
            size_t j = i + 2;
            while (j < n && s[j] != '\n') j++;
            em.add(i, j, pal.comment); i = j;
        } else if (c == '/' && i + 1 < n && s[i + 1] == '*') {
            const size_t e = indexOf(s, n, i + 2, "*/", 2);
            const size_t j = e == std::string::npos ? n : e + 2;
            em.add(i, j, pal.comment); i = j;
        } else if (c == '"' || c == '\'') {
            size_t j = i + 1;
            while (j < n && s[j] != c) { if (s[j] == '\\') j++; j++; }
            const size_t close = j < n ? j + 1 : n;
            size_t k = close;
            while (k < n && (s[k] == ' ' || s[k] == '\t' || s[k] == '\n' || s[k] == '\r')) k++;
            const bool isKey = k < n && s[k] == ':';
            em.add(i, close, isKey ? pal.property : pal.string_); i = close;
        } else if (cpIsDigit(c) || (c == '-' && i + 1 < n && cpIsDigit(s[i + 1]))) {
            size_t j = i + 1;
            while (j < n) {
                size_t len;
                const uint32_t cp = decodeAt(s, n, j, &len);
                if (cpIsLetterOrDigit(cp) || cp == '.' || cp == '+' || cp == '-' || cp == 'e' || cp == 'E') j += len;
                else break;
            }
            em.add(i, j, pal.number); i = j;
        } else {
            size_t cLen;
            const uint32_t cp = decodeAt(s, n, i, &cLen);
            if (cpIsLetter(cp)) {
                size_t j = i;
                while (j < n) {
                    size_t len;
                    const uint32_t wcp = decodeAt(s, n, j, &len);
                    if (cpIsLetter(wcp)) j += len; else break;
                }
                const std::string_view word(reinterpret_cast<const char*>(s) + i, j - i);
                if (word == "true" || word == "false" || word == "null" || word == "Infinity" || word == "NaN") {
                    em.add(i, j, pal.keyword);
                }
                i = j;
            } else if (c == '{' || c == '}' || c == '[' || c == ']' || c == ':' || c == ',') {
                em.add(i, i + 1, pal.operator_); i++;
            } else {
                i += cLen;
            }
        }
    }
}

}  // namespace

void highlight_run(const uint8_t* text, size_t n, HighlightMode mode,
                   const HighlightProfile* profile, const HighlightPalette& palette,
                   std::vector<int32_t>& out) {
    if (n == 0) return;
    Emitter em{out};
    static const HighlightProfile kEmptyProfile{};
    const HighlightProfile& p = profile != nullptr ? *profile : kEmptyProfile;
    switch (mode) {
        case HIGHLIGHT_TOKENIZE: runTokenize(text, n, p, palette, em); break;
        case HIGHLIGHT_MARKDOWN: runMarkdown(text, n, palette, em); break;
        case HIGHLIGHT_MARKUP: runMarkup(text, n, palette, em); break;
        case HIGHLIGHT_KEYVALUE: runKeyValue(text, n, p, palette, em); break;
        case HIGHLIGHT_JSON: runJson(text, n, palette, em); break;
        default: break;
    }
}

}  // namespace jcode
