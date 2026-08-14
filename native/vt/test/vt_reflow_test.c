// Tests for vt_parser's resize behaviour: column rewrap, cursor tracking and the DECSC save slot.
//
// vt_parser.c is pure C with no Android dependencies, so this builds as a plain executable with the
// NDK's clang and runs on the device (or any aarch64 Linux) in well under a second. That is a much
// tighter loop than an instrumented androidTest, and it can afford a fuzz pass and a timing pass.
//
// Build and run:  native/vt/test/run.sh
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <time.h>
#include "../src/vt_parser.h"

static int failures = 0;
static int checks = 0;

static void check(int cond, const char* what) {
    checks++;
    if (!cond) { printf("  FAIL: %s\n", what); failures++; }
}

static void feed(VtParser* p, const char* s) {
    vt_parser_feed(p, (const uint8_t*)s, strlen(s));
}

// Concatenate a screen row's content (trailing blanks trimmed), ASCII only.
static void row_text(VtParser* p, int row, char* out, int out_cap) {
    const VtScreen* s = vt_parser_get_screen(p);
    int n = 0;
    int last = -1;
    for (int c = 0; c < s->cols; c++) {
        uint32_t ch = s->cells[row * s->cols + c].ch;
        if (ch != ' ' && ch != 0) last = c;
    }
    for (int c = 0; c <= last && n < out_cap - 1; c++) {
        uint32_t ch = s->cells[row * s->cols + c].ch;
        out[n++] = (ch == 0) ? '~' : (char)(ch < 128 ? ch : '?');
    }
    out[n] = 0;
}

// Whole visible screen, rows joined by '|', trailing blank rows dropped.
static void screen_text(VtParser* p, char* out, int out_cap) {
    const VtScreen* s = vt_parser_get_screen(p);
    out[0] = 0;
    int last = -1;
    char row[512];
    for (int r = 0; r < s->rows; r++) { row_text(p, r, row, sizeof(row)); if (row[0]) last = r; }
    for (int r = 0; r <= last; r++) {
        row_text(p, r, row, sizeof(row));
        if (r) strncat(out, "|", out_cap - strlen(out) - 1);
        strncat(out, row, out_cap - strlen(out) - 1);
    }
}

static void expect_screen(VtParser* p, const char* want, const char* what) {
    char got[4096];
    screen_text(p, got, sizeof(got));
    checks++;
    if (strcmp(got, want) != 0) {
        printf("  FAIL: %s\n    want: %s\n    got : %s\n", what, want, got);
        failures++;
    }
}

// ---------------------------------------------------------------------------

static void test_widen_rejoins(void) {
    printf("widen rejoins a wrapped line\n");
    VtParser* p = vt_parser_create(6, 10);
    feed(p, "ABCDEFGHIJKLMNOPQRSTUVWXYZ");     // 26 chars at 10 cols -> 3 rows
    expect_screen(p, "ABCDEFGHIJ|KLMNOPQRST|UVWXYZ", "pre-resize layout");
    vt_parser_resize(p, 6, 30);
    expect_screen(p, "ABCDEFGHIJKLMNOPQRSTUVWXYZ", "rejoined at 30 cols");
    vt_parser_destroy(p);
}

static void test_narrow_resplits(void) {
    printf("narrow re-splits without loss\n");
    VtParser* p = vt_parser_create(8, 30);
    feed(p, "ABCDEFGHIJKLMNOPQRSTUVWXYZ");
    vt_parser_resize(p, 8, 10);
    expect_screen(p, "ABCDEFGHIJ|KLMNOPQRST|UVWXYZ", "re-split at 10 cols");
    vt_parser_destroy(p);
}

static void test_round_trip(void) {
    printf("narrow then widen round-trips\n");
    VtParser* p = vt_parser_create(8, 40);
    feed(p, "The quick brown fox jumps over the lazy dog and keeps running");
    char before[4096];
    screen_text(p, before, sizeof(before));
    vt_parser_resize(p, 8, 13);
    vt_parser_resize(p, 8, 40);
    char after[4096];
    screen_text(p, after, sizeof(after));
    checks++;
    if (strcmp(before, after) != 0) {
        printf("  FAIL: round trip\n    before: %s\n    after : %s\n", before, after);
        failures++;
    }
    vt_parser_destroy(p);
}

static void test_tui_rows_untouched(void) {
    printf("rows the app laid out itself are never re-split (TUI safety)\n");
    VtParser* p = vt_parser_create(6, 40);
    // Each line is shorter than the width and ends with an explicit CRLF: no wrapped bits at all.
    feed(p, "line one\r\nline two\r\nline three\r\n");
    vt_parser_resize(p, 6, 20);
    expect_screen(p, "line one|line two|line three", "narrower: same rows, same content");
    vt_parser_resize(p, 6, 60);
    expect_screen(p, "line one|line two|line three", "wider: same rows, same content");
    vt_parser_destroy(p);
}

static void test_trailing_spaces_not_a_wrap(void) {
    printf("real trailing spaces are not a wrap\n");
    VtParser* p = vt_parser_create(6, 12);
    feed(p, "abc   \r\ndef\r\n");
    vt_parser_resize(p, 6, 40);
    expect_screen(p, "abc|def", "not joined into one line");
    vt_parser_destroy(p);
}

static void test_exact_fill_not_joined(void) {
    printf("a row filled exactly to the width, then CRLF, is standalone\n");
    VtParser* p = vt_parser_create(6, 10);
    feed(p, "0123456789\r\nnext\r\n");   // 10 chars parks the cursor; CRLF ends the line
    vt_parser_resize(p, 6, 40);
    expect_screen(p, "0123456789|next", "still two lines");
    vt_parser_destroy(p);
}

static void test_erase_breaks_the_join(void) {
    printf("erasing a wrapped row's start breaks the join\n");
    VtParser* p = vt_parser_create(6, 10);
    feed(p, "ABCDEFGHIJKLMNOPQRST");     // 2 rows, second is a continuation
    feed(p, "\x1b[2;1H\x1b[2K");         // cursor to row 2 col 1, erase the whole line
    vt_parser_resize(p, 6, 40);
    expect_screen(p, "ABCDEFGHIJ", "row 2 cleared and not rejoined");
    vt_parser_destroy(p);
}

static void test_blank_lines_preserved(void) {
    printf("blank lines keep their count\n");
    VtParser* p = vt_parser_create(8, 20);
    feed(p, "a\r\n\r\n\r\nb\r\n");
    vt_parser_resize(p, 8, 40);
    expect_screen(p, "a|||b", "three-line gap preserved");
    vt_parser_destroy(p);
}

static void test_cursor_follows_content(void) {
    printf("cursor stays on its character\n");
    VtParser* p = vt_parser_create(6, 10);
    feed(p, "ABCDEFGHIJKLMNO");          // cursor after 'O' on row 1, col 5
    check(vt_parser_get_screen(p)->cursor_row == 1, "cursor row before");
    check(vt_parser_get_screen(p)->cursor_col == 5, "cursor col before");
    vt_parser_resize(p, 6, 30);
    const VtScreen* s = vt_parser_get_screen(p);
    check(s->cursor_row == 0, "cursor row after widen");
    check(s->cursor_col == 15, "cursor col after widen (just past 'O')");
    // Typing continues where the cursor is.
    feed(p, "P");
    char row[512]; row_text(p, 0, row, sizeof(row));
    check(strcmp(row, "ABCDEFGHIJKLMNOP") == 0, "next character lands after O");
    vt_parser_destroy(p);
}

static void test_parked_cursor(void) {
    printf("parked cursor survives a resize\n");
    VtParser* p = vt_parser_create(6, 10);
    feed(p, "0123456789");               // exactly full: cursor parks at col 10
    check(vt_parser_get_screen(p)->cursor_col == 10, "parked before resize");
    vt_parser_resize(p, 6, 20);
    // Content now fits in 20 cols with room to spare, so the cursor is at col 10, not parked.
    feed(p, "X");
    char row[512]; row_text(p, 0, row, sizeof(row));
    check(strcmp(row, "0123456789X") == 0, "next char appends, no wrap");
    vt_parser_destroy(p);
}

static void test_parked_stays_parked(void) {
    printf("parked cursor is still parked when the width is unchanged in effect\n");
    VtParser* p = vt_parser_create(6, 10);
    feed(p, "0123456789ABCDEFGHIJ");     // 20 chars: 2 full rows, cursor parked at end of row 1
    check(vt_parser_get_screen(p)->cursor_col == 10, "parked before");
    vt_parser_resize(p, 6, 5);           // re-split into 4 rows of 5, still exactly full
    const VtScreen* s = vt_parser_get_screen(p);
    check(s->cursor_col == 5, "still parked at the row edge after re-split");
    feed(p, "Z");
    expect_screen(p, "01234|56789|ABCDE|FGHIJ|Z", "next char wraps to a new row");
    vt_parser_destroy(p);
}

static void test_scrollback_reflows(void) {
    printf("scrollback reflows and survives\n");
    VtParser* p = vt_parser_create(4, 10);
    for (int i = 0; i < 20; i++) feed(p, "ABCDEFGHIJKLMNOPQRST\r\n");   // each wraps to 2 rows
    int64_t pushed_before = p->scrollback_pushed;
    int count_before = p->scrollback_count;
    check(count_before > 0, "scrollback populated");
    vt_parser_resize(p, 4, 20);
    check(p->scrollback_pushed >= pushed_before, "scrollback_pushed is monotonic");
    // At 20 cols each logical line is one row, so history should be about half as many rows.
    check(p->scrollback_count < count_before, "history re-partitioned smaller");
    // Every retained line should be the full 20-char text now.
    const VtCell* line = vt_parser_row_ptr(p, -1);
    check(line != NULL, "can read scrollback row -1");
    if (line) {
        char buf[64]; int n = 0;
        for (int c = 0; c < 20; c++) buf[n++] = (char)line[c].ch;
        buf[n] = 0;
        check(strcmp(buf, "ABCDEFGHIJKLMNOPQRST") == 0, "scrollback line rejoined");
    }
    vt_parser_destroy(p);
}

static void test_alt_screen_not_rewrapped(void) {
    printf("alternate screen is clamped, not rewrapped\n");
    VtParser* p = vt_parser_create(6, 10);
    feed(p, "\x1b[?1049h");               // enter alt screen
    feed(p, "ABCDEFGHIJKLMNOPQRST");      // wraps within the alt screen
    vt_parser_resize(p, 6, 30);
    expect_screen(p, "ABCDEFGHIJ|KLMNOPQRST", "alt content clamped in place, rows unchanged");
    vt_parser_destroy(p);
}

static void test_saved_cursor_survives(void) {
    printf("saved cursor survives a resize\n");
    VtParser* p = vt_parser_create(6, 20);
    feed(p, "hello\r\nworld");            // cursor on row 1 col 5
    feed(p, "\x1b" "7");                  // DECSC
    feed(p, "\x1b[6;1Hjunk");             // move away and write
    vt_parser_resize(p, 6, 40);
    feed(p, "\x1b" "8");                  // DECRC
    const VtScreen* s = vt_parser_get_screen(p);
    check(s->cursor_row == 1, "restored row is the saved one, not 0");
    check(s->cursor_col == 5, "restored col is the saved one, not 0");
    vt_parser_destroy(p);
}

static void test_alt_exit_restores_prompt(void) {
    printf("alt-screen exit after a resize does not home the prompt\n");
    VtParser* p = vt_parser_create(6, 20);
    feed(p, "line1\r\nline2\r\n$ ");      // prompt on row 2, col 2
    const VtScreen* s0 = vt_parser_get_screen(p);
    check(s0->cursor_row == 2 && s0->cursor_col == 2, "prompt position before");
    feed(p, "\x1b[?1049h");               // full-screen app starts: saves the prompt cursor
    vt_parser_resize(p, 6, 40);           // user drags the split handle
    feed(p, "\x1b[?1049l");               // app exits
    const VtScreen* s = vt_parser_get_screen(p);
    check(s->cursor_row == 2, "prompt row restored (was 0 before the fix)");
    check(s->cursor_col == 2, "prompt col restored");
    vt_parser_destroy(p);
}

static void test_wide_chars(void) {
    printf("wide characters never split\n");
    VtParser* p = vt_parser_create(6, 9);
    // 6 CJK chars = 12 columns -> wraps at 9 (4 chars + a pad blank), continuing on the next row.
    feed(p, "\xe6\x97\xa5\xe6\x9c\xac\xe8\xaa\x9e\xe6\x97\xa5\xe6\x9c\xac\xe8\xaa\x9e");
    for (int w = 4; w <= 24; w++) {
        vt_parser_resize(p, 6, w);
        const VtScreen* s = vt_parser_get_screen(p);
        for (int r = 0; r < s->rows; r++) {
            // No row may START with a continuation cell — that would be an orphan.
            check(s->cells[r * s->cols].ch != 0, "no row starts with a continuation");
            // A lead must always be followed by its continuation.
            for (int c = 0; c < s->cols; c++) {
                uint32_t ch = s->cells[r * s->cols + c].ch;
                if (ch != 0 && ch > 0x1100) {
                    check(c + 1 < s->cols && s->cells[r * s->cols + c + 1].ch == 0,
                          "wide lead keeps its continuation");
                    c++;
                }
            }
        }
    }
    vt_parser_destroy(p);
}

static void test_fuzz(void) {
    printf("fuzz: invariants hold across random content and widths\n");
    unsigned seed = 12345;
    for (int iter = 0; iter < 400; iter++) {
        seed = seed * 1103515245u + 12345u;
        int rows = 4 + (int)((seed >> 16) % 12);
        int cols = 6 + (int)((seed >> 8) % 40);
        VtParser* p = vt_parser_create(rows, cols);
        for (int k = 0; k < 40; k++) {
            seed = seed * 1103515245u + 12345u;
            switch ((seed >> 16) % 6) {
                case 0: feed(p, "hello world "); break;
                case 1: feed(p, "\r\n"); break;
                case 2: feed(p, "\xe6\x97\xa5\xe6\x9c\xac"); break;
                case 3: feed(p, "\x1b[2K"); break;
                case 4: feed(p, "\x1b[3;1H"); break;
                default: feed(p, "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"); break;
            }
        }
        int64_t pushed = p->scrollback_pushed;
        for (int k = 0; k < 6; k++) {
            seed = seed * 1103515245u + 12345u;
            int nc = 4 + (int)((seed >> 12) % 60);
            int nr = 3 + (int)((seed >> 20) % 14);
            vt_parser_resize(p, nr, nc);
            const VtScreen* s = vt_parser_get_screen(p);
            check(p->scrollback_pushed >= pushed, "pushed monotonic");
            pushed = p->scrollback_pushed;
            check(s->cursor_row >= 0 && s->cursor_row < s->rows, "cursor row in range");
            check(s->cursor_col >= 0 && s->cursor_col <= s->cols, "cursor col in range");
            check(s->saved_row >= 0 && s->saved_row < s->rows, "saved row in range");
            check(p->scrollback_count >= 0 && p->scrollback_count <= p->scrollback_cap, "count in range");
            for (int r = 0; r < s->rows; r++) {
                check(s->cells[r * s->cols].ch != 0, "no row starts with a continuation");
            }
            if (failures > 20) { vt_parser_destroy(p); return; }
        }
        vt_parser_destroy(p);
    }
}

static void test_perf(void) {
    printf("perf: resize with a full scrollback\n");
    VtParser* p = vt_parser_create(40, 200);
    // Fill the whole 2000-line ring with wrapped content — the worst case the cap allows.
    for (int i = 0; i < 2500; i++) {
        feed(p, "the quick brown fox jumps over the lazy dog and then keeps on running well past the "
                "right hand edge of this terminal so that every single line has to wrap at least once\r\n");
    }
    struct timespec a, b;
    int widths[8] = { 100, 60, 180, 80, 200, 45, 120, 90 };
    double worst = 0;
    for (int k = 0; k < 8; k++) {
        clock_gettime(CLOCK_MONOTONIC, &a);
        vt_parser_resize(p, 40, widths[k]);
        clock_gettime(CLOCK_MONOTONIC, &b);
        double ms = (b.tv_sec - a.tv_sec) * 1000.0 + (b.tv_nsec - a.tv_nsec) / 1e6;
        if (ms > worst) worst = ms;
        printf("    -> %3d cols: %6.2f ms\n", widths[k], ms);
    }
    check(worst < 60.0, "worst-case resize stays well inside a debounce interval");
    printf("    scrollback %d lines, worst %.2f ms\n", p->scrollback_count, worst);
    vt_parser_destroy(p);
}

int main(void) {
    test_widen_rejoins();
    test_narrow_resplits();
    test_round_trip();
    test_tui_rows_untouched();
    test_trailing_spaces_not_a_wrap();
    test_exact_fill_not_joined();
    test_erase_breaks_the_join();
    test_blank_lines_preserved();
    test_cursor_follows_content();
    test_parked_cursor();
    test_parked_stays_parked();
    test_scrollback_reflows();
    test_alt_screen_not_rewrapped();
    test_saved_cursor_survives();
    test_alt_exit_restores_prompt();
    test_wide_chars();
    test_fuzz();
    test_perf();
    printf("\n%d checks, %d failures\n", checks, failures);
    return failures ? 1 : 0;
}
