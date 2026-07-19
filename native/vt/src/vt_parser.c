#include "vt_parser.h"
#include <stdlib.h>
#include <string.h>
#include <stdio.h>

#define VT_SCROLLBACK_CAP 2000

// Blank-cell prototype: whole-struct assignment vectorizes to one wide store per cell, where the
// old field-by-field clears (4 narrow stores skipping the color bytes) could not. Side effect:
// cleared cells zero their color payload bytes too — unobservable, the renderer keys off mode.
static const VtCell VT_BLANK = { .ch = ' ' };

static void clear_cells(VtCell* p, int count) {
    for (int i = 0; i < count; i++) p[i] = VT_BLANK;
}

// Helper to allocate screen
static VtScreen* screen_create(int rows, int cols) {
    VtScreen* screen = (VtScreen*)calloc(1, sizeof(VtScreen));
    if (!screen) return NULL;

    screen->rows = rows;
    screen->cols = cols;
    screen->cells = (VtCell*)calloc(rows * cols, sizeof(VtCell));
    if (!screen->cells) {
        free(screen);
        return NULL;
    }

    clear_cells(screen->cells, rows * cols);

    screen->cursor_row = 0;
    screen->cursor_col = 0;
    screen->cursor_visible = true;
    screen->scroll_top = 0;
    screen->scroll_bottom = rows - 1;
    
    return screen;
}

static void screen_destroy(VtScreen* screen) {
    if (screen) {
        free(screen->cells);
        free(screen);
    }
}

static void screen_clear(VtScreen* screen) {
    if (!screen) return;
    clear_cells(screen->cells, screen->rows * screen->cols);
}

static inline VtCell* screen_cell_at(VtScreen* screen, int row, int col) {
    if (row < 0 || row >= screen->rows || col < 0 || col >= screen->cols) {
        return NULL;
    }
    return &screen->cells[row * screen->cols + col];
}

// Push a screen line into the scrollback ring buffer (oldest evicted when full).
static void scrollback_push(VtParser* parser, const VtCell* line, int line_cols) {
    if (!parser->scrollback || parser->scrollback_cap <= 0) return;
    int slot;
    if (parser->scrollback_count == parser->scrollback_cap) {
        slot = parser->scrollback_head;
        parser->scrollback_head = (parser->scrollback_head + 1) % parser->scrollback_cap;
    } else {
        slot = (parser->scrollback_head + parser->scrollback_count) % parser->scrollback_cap;
        parser->scrollback_count++;
    }
    VtCell* dst = &parser->scrollback[slot * parser->scrollback_cols];
    int n = line_cols < parser->scrollback_cols ? line_cols : parser->scrollback_cols;
    memcpy(dst, line, (size_t)n * sizeof(VtCell));
    clear_cells(dst + n, parser->scrollback_cols - n);
    parser->scrollback_pushed++;
}

static void clear_row_range(VtScreen* screen, int row, int col_from, int col_to) {
    if (row < 0 || row >= screen->rows) return;
    if (col_from < 0) col_from = 0;
    if (col_to >= screen->cols) col_to = screen->cols - 1;
    if (col_from > col_to) return;
    clear_cells(&screen->cells[row * screen->cols + col_from], col_to - col_from + 1);
}

// Shift rows [top, bottom] up by n inside the screen (no scrollback capture — see screen_scroll_up).
// The rows are contiguous in one cells array, so the shift is a single overlapping memmove — the
// previous one-memmove-per-row loop issued (rows-1) small copies for every scrolled line.
static void region_shift_up(VtScreen* screen, int top, int bottom, int n) {
    int move = bottom - top + 1 - n;
    if (move > 0) {
        memmove(&screen->cells[top * screen->cols],
                &screen->cells[(top + n) * screen->cols],
                (size_t)move * screen->cols * sizeof(VtCell));
    }
    clear_cells(&screen->cells[(bottom - n + 1) * screen->cols], n * screen->cols);
}

// Shift rows [top, bottom] down by n inside the screen.
static void region_shift_down(VtScreen* screen, int top, int bottom, int n) {
    int move = bottom - top + 1 - n;
    if (move > 0) {
        memmove(&screen->cells[(top + n) * screen->cols],
                &screen->cells[top * screen->cols],
                (size_t)move * screen->cols * sizeof(VtCell));
    }
    clear_cells(&screen->cells[top * screen->cols], n * screen->cols);
}

// Scroll region up by n lines. Lines evicted from the top of the primary full-height region are
// captured into scrollback first so the user can scroll back through history.
static void screen_scroll_up(VtParser* parser, int n) {
    VtScreen* screen = parser->active;
    if (n <= 0) return;
    if (n > screen->scroll_bottom - screen->scroll_top + 1) {
        n = screen->scroll_bottom - screen->scroll_top + 1;
    }

    if (screen == &parser->primary && screen->scroll_top == 0) {
        for (int k = 0; k < n; k++) {
            scrollback_push(parser, &screen->cells[(screen->scroll_top + k) * screen->cols], screen->cols);
        }
    }

    region_shift_up(screen, screen->scroll_top, screen->scroll_bottom, n);
}

// Scroll region down by n lines
static void screen_scroll_down(VtParser* parser, int n) {
    VtScreen* screen = parser->active;
    if (n <= 0) return;
    if (n > screen->scroll_bottom - screen->scroll_top + 1) {
        n = screen->scroll_bottom - screen->scroll_top + 1;
    }
    region_shift_down(screen, screen->scroll_top, screen->scroll_bottom, n);
}

// Write character at cursor position
static void screen_write_char(VtParser* parser, uint32_t ch) {
    VtScreen* screen = parser->active;
    
    // Handle line wrap (deferred: the cursor parks past the last column until the next printable).
    // With autowrap off (DECRST ?7) the last column is overwritten in place instead.
    if (screen->cursor_col >= screen->cols) {
        if (!parser->decawm) {
            screen->cursor_col = screen->cols - 1;
        } else {
            screen->cursor_col = 0;
            screen->cursor_row++;

            if (screen->cursor_row > screen->scroll_bottom) {
                screen_scroll_up(parser, 1);
                screen->cursor_row = screen->scroll_bottom;
            }
        }
    }

    VtCell* cell = screen_cell_at(screen, screen->cursor_row, screen->cursor_col);
    if (cell) {
        cell->ch = ch;
        cell->fg = parser->fg;
        cell->bg = parser->bg;
        cell->attrs = parser->attrs;
        
        // Mark row as dirty
        if (parser->dirty_rows) {
            parser->dirty_rows[screen->cursor_row] = true;
        }
    }
    
    screen->cursor_col++;
}

// Newline
static void screen_newline(VtParser* parser) {
    VtScreen* screen = parser->active;
    screen->cursor_row++;

    if (screen->cursor_row > screen->scroll_bottom) {
        screen_scroll_up(parser, 1);
        screen->cursor_row = screen->scroll_bottom;
    }
    
    if (parser->dirty_rows) {
        parser->dirty_rows[screen->cursor_row] = true;
    }
}

// Carriage return
static void screen_carriage_return(VtParser* parser) {
    parser->active->cursor_col = 0;
}

// Parse SGR (Select Graphic Rendition) parameters
static void handle_sgr(VtParser* parser) {
    if (parser->param_count == 0) {
        // Reset all attributes
        parser->attrs = 0;
        parser->fg.mode = 0;
        parser->bg.mode = 0;
        return;
    }
    
    for (int i = 0; i < parser->param_count; i++) {
        int p = parser->params[i];
        
        if (p == 0) {
            // Reset
            parser->attrs = 0;
            parser->fg.mode = 0;
            parser->bg.mode = 0;
        } else if (p == 1) {
            parser->attrs |= VT_ATTR_BOLD;
        } else if (p == 2) {
            parser->attrs |= VT_ATTR_DIM;
        } else if (p == 3) {
            parser->attrs |= VT_ATTR_ITALIC;
        } else if (p == 4) {
            parser->attrs |= VT_ATTR_UNDERLINE;
        } else if (p == 5) {
            parser->attrs |= VT_ATTR_BLINK;
        } else if (p == 7) {
            parser->attrs |= VT_ATTR_INVERSE;
        } else if (p == 8) {
            parser->attrs |= VT_ATTR_HIDDEN;
        } else if (p == 9) {
            parser->attrs |= VT_ATTR_STRIKETHROUGH;
        } else if (p == 21) {
            // Doubly underlined; rendered as a plain underline.
            parser->attrs |= VT_ATTR_UNDERLINE;
        } else if (p == 22) {
            // Attribute-off codes: without these, chalk-style output (\e[1m…\e[22m) never turns
            // bold/dim off and the styling bleeds across the rest of the screen.
            parser->attrs &= ~(VT_ATTR_BOLD | VT_ATTR_DIM);
        } else if (p == 23) {
            parser->attrs &= ~VT_ATTR_ITALIC;
        } else if (p == 24) {
            parser->attrs &= ~VT_ATTR_UNDERLINE;
        } else if (p == 25) {
            parser->attrs &= ~VT_ATTR_BLINK;
        } else if (p == 27) {
            parser->attrs &= ~VT_ATTR_INVERSE;
        } else if (p == 28) {
            parser->attrs &= ~VT_ATTR_HIDDEN;
        } else if (p == 29) {
            parser->attrs &= ~VT_ATTR_STRIKETHROUGH;
        } else if (p >= 30 && p <= 37) {
            // Standard foreground colors
            parser->fg.mode = 1;
            parser->fg.index = p - 30;
        } else if (p == 38) {
            // Extended foreground color
            if (i + 1 < parser->param_count) {
                int mode = parser->params[++i];
                if (mode == 5 && i + 1 < parser->param_count) {
                    // 256-color mode
                    parser->fg.mode = 1;
                    parser->fg.index = parser->params[++i];
                } else if (mode == 2 && i + 3 < parser->param_count) {
                    // Truecolor mode
                    parser->fg.mode = 2;
                    parser->fg.r = parser->params[++i];
                    parser->fg.g = parser->params[++i];
                    parser->fg.b = parser->params[++i];
                }
            }
        } else if (p == 39) {
            // Default foreground
            parser->fg.mode = 0;
        } else if (p >= 40 && p <= 47) {
            // Standard background colors
            parser->bg.mode = 1;
            parser->bg.index = p - 40;
        } else if (p == 48) {
            // Extended background color
            if (i + 1 < parser->param_count) {
                int mode = parser->params[++i];
                if (mode == 5 && i + 1 < parser->param_count) {
                    // 256-color mode
                    parser->bg.mode = 1;
                    parser->bg.index = parser->params[++i];
                } else if (mode == 2 && i + 3 < parser->param_count) {
                    // Truecolor mode
                    parser->bg.mode = 2;
                    parser->bg.r = parser->params[++i];
                    parser->bg.g = parser->params[++i];
                    parser->bg.b = parser->params[++i];
                }
            }
        } else if (p == 49) {
            // Default background
            parser->bg.mode = 0;
        } else if (p >= 90 && p <= 97) {
            // Bright foreground colors
            parser->fg.mode = 1;
            parser->fg.index = p - 90 + 8;
        } else if (p >= 100 && p <= 107) {
            // Bright background colors
            parser->bg.mode = 1;
            parser->bg.index = p - 100 + 8;
        }
    }
}

// Queue answerback bytes for the host to drain to the PTY (dropped when the buffer is full).
static void response_append(VtParser* parser, const char* s) {
    int len = (int)strlen(s);
    if (len > VT_RESPONSE_CAP - parser->response_len) return;
    memcpy(parser->responses + parser->response_len, s, (size_t)len);
    parser->response_len += len;
}

// DECSC/DECRC bodies, shared by ESC 7/8, CSI s/u, and the 1049 alt-screen switch.
static void cursor_save(VtParser* parser, VtScreen* s) {
    s->saved_row = s->cursor_row;
    s->saved_col = s->cursor_col;
    s->saved_attrs = parser->attrs;
    s->saved_fg = parser->fg;
    s->saved_bg = parser->bg;
}

static void cursor_restore(VtParser* parser, VtScreen* s) {
    s->cursor_row = s->saved_row;
    s->cursor_col = s->saved_col;
    parser->attrs = s->saved_attrs;
    parser->fg = s->saved_fg;
    parser->bg = s->saved_bg;
}

static bool has_intermediate(const VtParser* parser, char ch) {
    for (int i = 0; i < parser->intermediate_count; i++) {
        if (parser->intermediates[i] == ch) return true;
    }
    return false;
}

// DECRQM status for `CSI ? Pd $ p`: 1 = set, 2 = reset, 0 = not recognized. Programs probe modes
// this way before relying on them (Claude Code probes ?2026 synchronized output at startup).
static int dec_mode_status(const VtParser* parser, int mode) {
    switch (mode) {
        case 1: return parser->decckm ? 1 : 2;
        case 7: return parser->decawm ? 1 : 2;
        case 25: return parser->active->cursor_visible ? 1 : 2;
        case 47:
        case 1047:
        case 1049: return parser->active == &parser->alternate ? 1 : 2;
        case 9: return parser->mouse_mode == VT_MOUSE_X10 ? 1 : 2;
        case 1000: return parser->mouse_mode == VT_MOUSE_NORMAL ? 1 : 2;
        case 1002: return parser->mouse_mode == VT_MOUSE_BUTTON ? 1 : 2;
        case 1003: return parser->mouse_mode == VT_MOUSE_ANY ? 1 : 2;
        case 1004: return parser->focus_events ? 1 : 2;
        case 1006: return parser->mouse_enc == VT_MOUSE_ENC_SGR ? 1 : 2;
        case 1015: return parser->mouse_enc == VT_MOUSE_ENC_URXVT ? 1 : 2;
        case 1007: return parser->alt_scroll ? 1 : 2;
        case 2004: return parser->bracketed_paste ? 1 : 2;
        case 2026: return parser->sync_output ? 1 : 2;
        default: return 0;
    }
}

// Apply one DEC private mode set/reset (the `CSI ? … h/l` handler loops over every parameter —
// `ESC[?1049;1006h` must apply both, not just the first).
static void apply_dec_mode(VtParser* parser, int mode, bool set) {
    switch (mode) {
        case 1: parser->decckm = set; break;
        case 7: parser->decawm = set; break;
        case 25: parser->active->cursor_visible = set; break;
        case 47:
        case 1047:
        case 1049:
            if (set) {
                // Save only when actually TRANSITIONING onto the alt screen: a re-issued 1049h
                // while already there must not clobber the saved primary cursor/SGR state
                // (xterm keeps a per-buffer save slot).
                if (mode == 1049 && parser->active != &parser->alternate) {
                    cursor_save(parser, &parser->primary);
                }
                parser->active = &parser->alternate;
                // xterm: only 1049 clears the alternate screen on entry; 1047 clears it on
                // exit (below) and plain 47 never clears.
                if (mode == 1049) screen_clear(&parser->alternate);
            } else {
                if (mode == 1047 && parser->active == &parser->alternate) {
                    screen_clear(&parser->alternate);
                }
                parser->active = &parser->primary;
                if (mode == 1049) cursor_restore(parser, &parser->primary);
            }
            parser->full_refresh = true;
            break;
        // xterm turns tracking OFF on DECRST of ANY tracking mode (apps commonly clean up with
        // just ?1000l even if they enabled ?1003) — a matching-only reset left tracking latched,
        // swallowing every tap at the shell prompt afterwards.
        case 9: parser->mouse_mode = set ? VT_MOUSE_X10 : VT_MOUSE_OFF; break;
        case 1000: parser->mouse_mode = set ? VT_MOUSE_NORMAL : VT_MOUSE_OFF; break;
        case 1002: parser->mouse_mode = set ? VT_MOUSE_BUTTON : VT_MOUSE_OFF; break;
        case 1003: parser->mouse_mode = set ? VT_MOUSE_ANY : VT_MOUSE_OFF; break;
        // 1005 (UTF-8 coords) is deliberately NOT recognized: acknowledging it while emitting
        // X10 bytes would feed invalid UTF-8 to apps that trusted the ack. Unknown modes fall
        // through to default (ignored), and DECRQM reports 0 = not recognized.
        case 1006: if (set) parser->mouse_enc = VT_MOUSE_ENC_SGR; else if (parser->mouse_enc == VT_MOUSE_ENC_SGR) parser->mouse_enc = VT_MOUSE_ENC_X10; break;
        case 1015: if (set) parser->mouse_enc = VT_MOUSE_ENC_URXVT; else if (parser->mouse_enc == VT_MOUSE_ENC_URXVT) parser->mouse_enc = VT_MOUSE_ENC_X10; break;
        case 1004: parser->focus_events = set; break;
        case 1007: parser->alt_scroll = set; break;
        case 2004: parser->bracketed_paste = set; break;
        case 2026: parser->sync_output = set; break;
        default: break;
    }
}

// Handle CSI sequence
static void handle_csi(VtParser* parser, char final_char) {
    VtScreen* screen = parser->active;

    // CSI sequences with a <, =, or > parameter prefix are private terminal queries/reports
    // (XTVERSION `>q`, XTMODKEYS `>...m`, …). Secondary DA (`CSI > c`) gets an answer — programs
    // gate mouse/feature support on the reported version — everything else is consumed and ignored
    // so the final byte isn't mis-applied (e.g. `CSI > 4;1 m` must NOT be treated as SGR). DEC
    // private modes use `?` and fall through to the switch below.
    if (parser->intermediate_count > 0 &&
        (parser->intermediates[0] == '<' || parser->intermediates[0] == '=' ||
         parser->intermediates[0] == '>')) {
        if (parser->intermediates[0] == '>' && final_char == 'c' &&
            (parser->param_count == 0 || parser->params[0] == 0)) {
            // Secondary DA: report as xterm 285 (SGR mouse capable), matching what we implement.
            response_append(parser, "\x1b[>41;285;0c");
        }
        return;
    }

    switch (final_char) {
        case 'A': { // Cursor Up
            int n = parser->param_count > 0 && parser->params[0] > 0 ? parser->params[0] : 1;
            screen->cursor_row -= n;
            if (screen->cursor_row < screen->scroll_top) {
                screen->cursor_row = screen->scroll_top;
            }
            break;
        }
        case 'B': { // Cursor Down
            int n = parser->param_count > 0 && parser->params[0] > 0 ? parser->params[0] : 1;
            screen->cursor_row += n;
            if (screen->cursor_row > screen->scroll_bottom) {
                screen->cursor_row = screen->scroll_bottom;
            }
            break;
        }
        case 'C': { // Cursor Forward
            int n = parser->param_count > 0 && parser->params[0] > 0 ? parser->params[0] : 1;
            screen->cursor_col += n;
            if (screen->cursor_col >= screen->cols) {
                screen->cursor_col = screen->cols - 1;
            }
            break;
        }
        case 'D': { // Cursor Back
            int n = parser->param_count > 0 && parser->params[0] > 0 ? parser->params[0] : 1;
            screen->cursor_col -= n;
            if (screen->cursor_col < 0) {
                screen->cursor_col = 0;
            }
            break;
        }
        case 'E': { // Cursor Next Line (down n rows, column 0)
            int n = parser->param_count > 0 && parser->params[0] > 0 ? parser->params[0] : 1;
            screen->cursor_row += n;
            if (screen->cursor_row > screen->scroll_bottom) {
                screen->cursor_row = screen->scroll_bottom;
            }
            screen->cursor_col = 0;
            break;
        }
        case 'F': { // Cursor Previous Line (up n rows, column 0). Used by the .NET build / MSBuild
                    // terminal logger to return to the top of its live progress block before redrawing
                    // in place — without this each tick's duration lands on a new line.
            int n = parser->param_count > 0 && parser->params[0] > 0 ? parser->params[0] : 1;
            screen->cursor_row -= n;
            if (screen->cursor_row < screen->scroll_top) {
                screen->cursor_row = screen->scroll_top;
            }
            screen->cursor_col = 0;
            break;
        }
        case 'G': { // Cursor Horizontal Absolute (column; 1-based). Used by progress bars/spinners
                    // (Node readline.cursorTo) to return to col 0 before redrawing in place.
            int col = parser->param_count > 0 && parser->params[0] > 0 ? parser->params[0] - 1 : 0;
            screen->cursor_col = col;
            if (screen->cursor_col < 0) screen->cursor_col = 0;
            if (screen->cursor_col >= screen->cols) screen->cursor_col = screen->cols - 1;
            break;
        }
        case 'd': { // Line Position Absolute (row; 1-based)
            int row = parser->param_count > 0 && parser->params[0] > 0 ? parser->params[0] - 1 : 0;
            screen->cursor_row = row;
            if (screen->cursor_row < 0) screen->cursor_row = 0;
            if (screen->cursor_row >= screen->rows) screen->cursor_row = screen->rows - 1;
            break;
        }
        case 'H': // Cursor Position
        case 'f': {
            int row = parser->param_count > 0 && parser->params[0] > 0 ? parser->params[0] - 1 : 0;
            int col = parser->param_count > 1 && parser->params[1] > 0 ? parser->params[1] - 1 : 0;
            screen->cursor_row = row;
            screen->cursor_col = col;
            if (screen->cursor_row >= screen->rows) screen->cursor_row = screen->rows - 1;
            if (screen->cursor_col >= screen->cols) screen->cursor_col = screen->cols - 1;
            break;
        }
        case 'J': { // Erase in Display
            int mode = parser->param_count > 0 ? parser->params[0] : 0;
            if (mode == 0) {
                // Clear from cursor to end: the cursor row's tail, then the contiguous rows below.
                clear_row_range(screen, screen->cursor_row, screen->cursor_col, screen->cols - 1);
                if (screen->cursor_row + 1 < screen->rows) {
                    clear_cells(&screen->cells[(screen->cursor_row + 1) * screen->cols],
                                (screen->rows - screen->cursor_row - 1) * screen->cols);
                }
                if (parser->dirty_rows) {
                    for (int row = screen->cursor_row; row < screen->rows; row++) parser->dirty_rows[row] = true;
                }
            } else if (mode == 1) {
                // Clear from beginning to cursor: the contiguous rows above, then the row head.
                clear_cells(screen->cells, screen->cursor_row * screen->cols);
                clear_row_range(screen, screen->cursor_row, 0, screen->cursor_col);
                if (parser->dirty_rows) {
                    for (int row = 0; row <= screen->cursor_row; row++) parser->dirty_rows[row] = true;
                }
            } else if (mode == 2) {
                // Clear entire screen
                screen_clear(screen);
                parser->full_refresh = true;
            } else if (mode == 3) {
                // xterm: clear scrollback (used by `clear` and Claude Code's /clear).
                parser->scrollback_count = 0;
                parser->scrollback_head = 0;
                parser->full_refresh = true;
            }
            break;
        }
        case 'K': { // Erase in Line
            int mode = parser->param_count > 0 ? parser->params[0] : 0;
            if (mode == 0) {
                clear_row_range(screen, screen->cursor_row, screen->cursor_col, screen->cols - 1);
            } else if (mode == 1) {
                clear_row_range(screen, screen->cursor_row, 0, screen->cursor_col);
            } else if (mode == 2) {
                clear_row_range(screen, screen->cursor_row, 0, screen->cols - 1);
            }
            if (parser->dirty_rows) parser->dirty_rows[screen->cursor_row] = true;
            break;
        }
        case 'S': { // Scroll Up
            int n = parser->param_count > 0 && parser->params[0] > 0 ? parser->params[0] : 1;
            screen_scroll_up(parser, n);
            parser->full_refresh = true;
            break;
        }
        case 'T': { // Scroll Down
            int n = parser->param_count > 0 && parser->params[0] > 0 ? parser->params[0] : 1;
            screen_scroll_down(parser, n);
            parser->full_refresh = true;
            break;
        }
        case '@': { // ICH — insert n blank cells at the cursor, shifting the rest of the row right
            int n = parser->param_count > 0 && parser->params[0] > 0 ? parser->params[0] : 1;
            int row = screen->cursor_row;
            int col = screen->cursor_col;
            if (col >= screen->cols) col = screen->cols - 1;
            if (n > screen->cols - col) n = screen->cols - col;
            VtCell* line = &screen->cells[row * screen->cols];
            memmove(&line[col + n], &line[col], (size_t)(screen->cols - col - n) * sizeof(VtCell));
            clear_row_range(screen, row, col, col + n - 1);
            if (parser->dirty_rows) parser->dirty_rows[row] = true;
            break;
        }
        case 'P': { // DCH — delete n cells at the cursor, shifting the rest of the row left
            int n = parser->param_count > 0 && parser->params[0] > 0 ? parser->params[0] : 1;
            int row = screen->cursor_row;
            int col = screen->cursor_col;
            if (col >= screen->cols) col = screen->cols - 1;
            if (n > screen->cols - col) n = screen->cols - col;
            VtCell* line = &screen->cells[row * screen->cols];
            memmove(&line[col], &line[col + n], (size_t)(screen->cols - col - n) * sizeof(VtCell));
            clear_row_range(screen, row, screen->cols - n, screen->cols - 1);
            if (parser->dirty_rows) parser->dirty_rows[row] = true;
            break;
        }
        case 'X': { // ECH — erase n cells from the cursor (no shift)
            int n = parser->param_count > 0 && parser->params[0] > 0 ? parser->params[0] : 1;
            int col = screen->cursor_col;
            if (col >= screen->cols) col = screen->cols - 1;
            if (n > screen->cols - col) n = screen->cols - col;
            clear_row_range(screen, screen->cursor_row, col, col + n - 1);
            if (parser->dirty_rows) parser->dirty_rows[screen->cursor_row] = true;
            break;
        }
        case 'L': { // IL — insert n blank lines at the cursor row (within the scroll region)
            int n = parser->param_count > 0 && parser->params[0] > 0 ? parser->params[0] : 1;
            if (screen->cursor_row >= screen->scroll_top && screen->cursor_row <= screen->scroll_bottom) {
                int span = screen->scroll_bottom - screen->cursor_row + 1;
                if (n > span) n = span;
                region_shift_down(screen, screen->cursor_row, screen->scroll_bottom, n);
                screen->cursor_col = 0;
                parser->full_refresh = true;
            }
            break;
        }
        case 'M': { // DL — delete n lines at the cursor row (within the scroll region)
            int n = parser->param_count > 0 && parser->params[0] > 0 ? parser->params[0] : 1;
            if (screen->cursor_row >= screen->scroll_top && screen->cursor_row <= screen->scroll_bottom) {
                int span = screen->scroll_bottom - screen->cursor_row + 1;
                if (n > span) n = span;
                region_shift_up(screen, screen->cursor_row, screen->scroll_bottom, n);
                screen->cursor_col = 0;
                parser->full_refresh = true;
            }
            break;
        }
        case 'c': { // Primary DA — identify as a VT420-class xterm (the feature list Termux reports)
            if (parser->param_count == 0 || parser->params[0] == 0) {
                response_append(parser, "\x1b[?64;1;2;6;9;15;18;21;22c");
            }
            break;
        }
        case 'n': { // DSR — status report (5) and cursor position report (6 / DECXCPR ?6)
            int req = parser->param_count > 0 ? parser->params[0] : 0;
            if (req == 5) {
                response_append(parser, "\x1b[0n");
            } else if (req == 6) {
                int row = screen->cursor_row + 1;
                int col = screen->cursor_col + 1;
                if (col > screen->cols) col = screen->cols;  // deferred-wrap: cursor parks past the edge
                if (row > screen->rows) row = screen->rows;
                char reply[32];
                bool dec = has_intermediate(parser, '?');
                snprintf(reply, sizeof(reply), dec ? "\x1b[?%d;%dR" : "\x1b[%d;%dR", row, col);
                response_append(parser, reply);
            }
            break;
        }
        case 't': { // XTWINOPS — answer the text-area-size report; ignore resize/icon requests
            if (parser->param_count > 0 && parser->params[0] == 18) {
                char reply[32];
                snprintf(reply, sizeof(reply), "\x1b[8;%d;%dt", screen->rows, screen->cols);
                response_append(parser, reply);
            }
            break;
        }
        case 'p': { // DECRQM (`CSI ? Pd $ p` / `CSI Pa $ p`) — report mode status
            if (has_intermediate(parser, '$')) {
                int mode = parser->param_count > 0 ? parser->params[0] : 0;
                char reply[32];
                if (has_intermediate(parser, '?')) {
                    snprintf(reply, sizeof(reply), "\x1b[?%d;%d$y", mode, dec_mode_status(parser, mode));
                } else {
                    snprintf(reply, sizeof(reply), "\x1b[%d;0$y", mode);
                }
                response_append(parser, reply);
            }
            break;
        }
        case 'm': { // SGR
            handle_sgr(parser);
            break;
        }
        case 'r': { // Set Scrolling Region (with `?` this is XTRESTORE mode-restore — not DECSTBM)
            if (parser->intermediate_count > 0) break;
            int top = parser->param_count > 0 && parser->params[0] > 0 ? parser->params[0] - 1 : 0;
            int bottom = parser->param_count > 1 && parser->params[1] > 0 ? parser->params[1] - 1 : screen->rows - 1;
            if (top >= 0 && bottom < screen->rows && top < bottom) {
                screen->scroll_top = top;
                screen->scroll_bottom = bottom;
                screen->cursor_row = 0;
                screen->cursor_col = 0;
            }
            break;
        }
        case 's': { // Save Cursor (with `?` this is XTSAVE mode-save — must not clobber the cursor)
            if (parser->intermediate_count > 0) break;
            cursor_save(parser, screen);
            break;
        }
        case 'u': { // Restore Cursor (with `?` this is a kitty-keyboard-protocol query — ignore)
            if (parser->intermediate_count > 0) break;
            cursor_restore(parser, screen);
            break;
        }
        case 'h':   // Mode set   (DEC private when prefixed with `?`)
        case 'l': { // Mode reset (DEC private when prefixed with `?`)
            if (parser->intermediate_count > 0 && parser->intermediates[0] == '?') {
                bool set = (final_char == 'h');
                for (int i = 0; i < parser->param_count; i++) {
                    apply_dec_mode(parser, parser->params[i], set);
                }
            }
            break;
        }
    }
}

// Grow the OSC accumulator so the next byte fits; false once the hard cap is reached.
static bool osc_grow(VtParser* parser) {
    if (parser->osc_length < parser->osc_cap - 1) return true;
    if (parser->osc_cap >= VT_OSC_BUFFER_MAX) return false;
    int new_cap = parser->osc_cap * 2;
    if (new_cap > VT_OSC_BUFFER_MAX) new_cap = VT_OSC_BUFFER_MAX;
    char* grown = (char*)realloc(parser->osc_buffer, (size_t)new_cap);
    if (!grown) return false;
    parser->osc_buffer = grown;
    parser->osc_cap = new_cap;
    return true;
}

// Queue a JCode shell-integration OSC event for the host to drain (bounded ring; oldest dropped).
static void osc_event_push(VtParser* parser, int code, const char* payload) {
    size_t len = strlen(payload);
    char* copy = (char*)malloc(len + 1);
    if (!copy) return;
    memcpy(copy, payload, len + 1);
    if (parser->osc_event_count == VT_OSC_EVENT_CAP) {
        free(parser->osc_events[parser->osc_event_head].payload);
        parser->osc_events[parser->osc_event_head].payload = NULL;
        parser->osc_event_head = (parser->osc_event_head + 1) % VT_OSC_EVENT_CAP;
        parser->osc_event_count--;
    }
    int slot = (parser->osc_event_head + parser->osc_event_count) % VT_OSC_EVENT_CAP;
    parser->osc_events[slot].code = code;
    parser->osc_events[slot].payload = copy;
    parser->osc_event_count++;
}

// Handle OSC (Operating System Command). `bel_terminated` records which terminator ended the
// sequence so query replies can mirror it (xterm behavior — some parsers only accept their own).
static void handle_osc(VtParser* parser, bool bel_terminated) {
    // Parse command number
    int cmd = 0;
    int i = 0;
    while (i < parser->osc_length && parser->osc_buffer[i] >= '0' && parser->osc_buffer[i] <= '9') {
        cmd = cmd * 10 + (parser->osc_buffer[i] - '0');
        if (cmd > 65535) cmd = 65535;
        i++;
    }

    // Skip semicolon
    if (i < parser->osc_length && parser->osc_buffer[i] == ';') {
        i++;
    }

    // Handle specific commands
    if (cmd == 0 || cmd == 2) {
        // Set window title
        char* title = &parser->osc_buffer[i];
        if (parser->on_title_change) {
            parser->on_title_change(parser->userdata, title);
        }
    } else if ((cmd == 10 || cmd == 11) && parser->osc_buffer[i] == '?') {
        // Default foreground/background color query — used for light/dark theme detection
        // (Claude Code derives its theme from the OSC 11 reply). The renderer draws white on black.
        char reply[48];
        snprintf(reply, sizeof(reply), "\x1b]%d;rgb:%s%s", cmd,
                 cmd == 10 ? "ffff/ffff/ffff" : "0000/0000/0000",
                 bel_terminated ? "\x07" : "\x1b\\");
        response_append(parser, reply);
    } else if (cmd == 52) {
        // Clipboard write (OSC 52 "c;<base64>") — forwarded to the host to set the Android
        // clipboard. Queries ("?") are filtered host-side; the clipboard is never reported back.
        // A payload that overflowed even the grown accumulator is dropped whole: a missing
        // clipboard write is recoverable, a silently truncated one is not.
        if (!parser->osc_truncated) {
            osc_event_push(parser, cmd, &parser->osc_buffer[i]);
        }
    } else if (cmd >= 7711 && cmd <= 7714) {
        // JCode shell-integration events (7711 open-file, 7712 tab title, 7713 task complete,
        // 7714 open-url): queued for the host to drain after each feed (see vt_parser_osc_event_at),
        // replacing the Kotlin-side per-byte re-scan of the same output.
        osc_event_push(parser, cmd, &parser->osc_buffer[i]);
    }
}

// State machine transition
static void parser_transition(VtParser* parser, uint8_t ch) {
    switch (parser->state) {
        case VT_STATE_GROUND:
            // UTF-8 continuation: while mid multi-byte sequence, fold in continuation bytes
            // (10xxxxxx) and emit the decoded codepoint once complete. Without this, each byte of a
            // multi-byte character (e.g. U+2502 '|' = E2 94 82) is written as its own garbage cell.
            if (parser->utf8_left > 0) {
                if ((ch & 0xC0) == 0x80) {
                    parser->utf8_acc = (parser->utf8_acc << 6) | (uint32_t)(ch & 0x3F);
                    if (--parser->utf8_left == 0) {
                        screen_write_char(parser, parser->utf8_acc);
                    }
                    break;
                }
                // Malformed sequence: emit a replacement glyph and reprocess this byte below.
                screen_write_char(parser, 0xFFFD);
                parser->utf8_left = 0;
            }
            if (ch == 0x1B) {
                parser->state = VT_STATE_ESCAPE;
            } else if (ch == 0x07) {
                // Bell
                if (parser->on_bell) parser->on_bell(parser->userdata);
            } else if (ch == 0x08) {
                // Backspace
                if (parser->active->cursor_col > 0) {
                    parser->active->cursor_col--;
                }
            } else if (ch == 0x09) {
                // Tab
                parser->active->cursor_col = (parser->active->cursor_col + 8) & ~7;
                if (parser->active->cursor_col >= parser->active->cols) {
                    parser->active->cursor_col = parser->active->cols - 1;
                }
            } else if (ch == 0x0A || ch == 0x0B || ch == 0x0C) {
                // Line feed
                screen_newline(parser);
            } else if (ch == 0x0D) {
                // Carriage return
                screen_carriage_return(parser);
            } else if (ch < 0x80) {
                // Printable ASCII
                if (ch >= 0x20) {
                    screen_write_char(parser, ch);
                }
            } else {
                // UTF-8 lead byte: begin a multi-byte sequence.
                if ((ch & 0xE0) == 0xC0) {        // 110xxxxx -> 1 continuation byte
                    parser->utf8_acc = (uint32_t)(ch & 0x1F);
                    parser->utf8_left = 1;
                } else if ((ch & 0xF0) == 0xE0) { // 1110xxxx -> 2 continuation bytes
                    parser->utf8_acc = (uint32_t)(ch & 0x0F);
                    parser->utf8_left = 2;
                } else if ((ch & 0xF8) == 0xF0) { // 11110xxx -> 3 continuation bytes
                    parser->utf8_acc = (uint32_t)(ch & 0x07);
                    parser->utf8_left = 3;
                } else {
                    // Stray continuation byte or invalid lead -> replacement glyph.
                    screen_write_char(parser, 0xFFFD);
                }
            }
            break;
            
        case VT_STATE_ESCAPE:
            if (ch == '[') {
                parser->state = VT_STATE_CSI_ENTRY;
                parser->param_count = 0;
                parser->current_param = 0;
                parser->intermediate_count = 0;
            } else if (ch == ']') {
                parser->state = VT_STATE_OSC_STRING;
                parser->osc_length = 0;
                parser->osc_truncated = false;
            } else if (ch == 'P') {
                parser->state = VT_STATE_DCS_ENTRY;
            } else if (ch == 'X' || ch == '^' || ch == '_') {
                parser->state = VT_STATE_SOS_PM_APC_STRING;
            } else if (ch >= 0x20 && ch <= 0x2F) {
                // Intermediate byte of an nF escape: charset designation (ESC ( B, ESC ) 0, ESC * B,
                // ESC + B, ESC % G), DECALN (ESC # 8), etc. Collect it and swallow the final byte in
                // ESCAPE_INTERMEDIATE — otherwise the final (B/0/G/8/…) leaks into GROUND and is printed
                // to the grid as a stray glyph that accumulates on the alternate screen.
                parser->state = VT_STATE_ESCAPE_INTERMEDIATE;
            } else if (ch == '7') {
                // DECSC — save cursor + attributes (same as CSI s).
                VtScreen* s = parser->active;
                s->saved_row = s->cursor_row;
                s->saved_col = s->cursor_col;
                s->saved_attrs = parser->attrs;
                s->saved_fg = parser->fg;
                s->saved_bg = parser->bg;
                parser->state = VT_STATE_GROUND;
            } else if (ch == '8') {
                // DECRC — restore cursor + attributes (same as CSI u).
                VtScreen* s = parser->active;
                s->cursor_row = s->saved_row;
                s->cursor_col = s->saved_col;
                parser->attrs = s->saved_attrs;
                parser->fg = s->saved_fg;
                parser->bg = s->saved_bg;
                parser->state = VT_STATE_GROUND;
            } else if (ch == 'D') {
                // IND — index (move down one line, scroll at bottom of region).
                screen_newline(parser);
                parser->state = VT_STATE_GROUND;
            } else if (ch == 'M') {
                // RI — reverse index (move up one line, scroll down at top of region).
                if (parser->active->cursor_row <= parser->active->scroll_top) {
                    screen_scroll_down(parser, 1);
                } else {
                    parser->active->cursor_row--;
                }
                parser->state = VT_STATE_GROUND;
            } else if (ch == 'E') {
                // NEL — next line (CR + LF).
                screen_carriage_return(parser);
                screen_newline(parser);
                parser->state = VT_STATE_GROUND;
            } else if (ch == 'c') {
                // RIS — full terminal reset.
                vt_parser_reset(parser);
                parser->state = VT_STATE_GROUND;
            } else {
                // Any other single-byte ESC final (keypad mode ESC =/>, etc.): consume, don't print.
                parser->state = VT_STATE_GROUND;
            }
            break;
            
        case VT_STATE_CSI_ENTRY:
            if (ch >= '0' && ch <= '9') {
                parser->current_param = ch - '0';
                parser->state = VT_STATE_CSI_PARAM;
            } else if (ch == ';') {
                if (parser->param_count < VT_MAX_PARAMS) {
                    parser->params[parser->param_count++] = 0;
                }
                parser->state = VT_STATE_CSI_PARAM;
            } else if (ch >= 0x40 && ch <= 0x7E) {
                // Final character
                handle_csi(parser, ch);
                parser->state = VT_STATE_GROUND;
            } else if (ch >= 0x3C && ch <= 0x3F) {
                // Private parameter markers: < = > ?  (DEC private modes `?`, XTVERSION `>q`,
                // XTMODKEYS `>...m`, secondary DA `>c`, …). Store and keep parsing so the whole
                // sequence is consumed instead of leaking its tail (params + final) as text.
                if (parser->intermediate_count < 4) {
                    parser->intermediates[parser->intermediate_count++] = ch;
                }
                parser->state = VT_STATE_CSI_PARAM;
            } else {
                parser->state = VT_STATE_GROUND;
            }
            break;

        case VT_STATE_CSI_PARAM:
            if (ch >= '0' && ch <= '9') {
                // Cap at xterm's 65535 so a hostile digit run can't overflow the accumulator
                // (signed overflow is UB) before the handlers' own clamps run.
                parser->current_param = parser->current_param * 10 + (ch - '0');
                if (parser->current_param > 65535) parser->current_param = 65535;
            } else if (ch == ';') {
                if (parser->param_count < VT_MAX_PARAMS) {
                    parser->params[parser->param_count++] = parser->current_param;
                }
                parser->current_param = 0;
            } else if (ch >= 0x40 && ch <= 0x7E) {
                // Final character
                if (parser->param_count < VT_MAX_PARAMS) {
                    parser->params[parser->param_count++] = parser->current_param;
                }
                handle_csi(parser, ch);
                parser->state = VT_STATE_GROUND;
            } else if (ch >= 0x20 && ch <= 0x2F) {
                // Intermediate
                if (parser->intermediate_count < 4) {
                    parser->intermediates[parser->intermediate_count++] = ch;
                }
                parser->state = VT_STATE_CSI_INTERMEDIATE;
            } else {
                parser->state = VT_STATE_CSI_IGNORE;
            }
            break;
            
        case VT_STATE_CSI_INTERMEDIATE:
            if (ch >= 0x40 && ch <= 0x7E) {
                if (parser->param_count < VT_MAX_PARAMS) {
                    parser->params[parser->param_count++] = parser->current_param;
                }
                handle_csi(parser, ch);
                parser->state = VT_STATE_GROUND;
            } else if (ch >= 0x20 && ch <= 0x2F) {
                if (parser->intermediate_count < 4) {
                    parser->intermediates[parser->intermediate_count++] = ch;
                }
            } else {
                parser->state = VT_STATE_CSI_IGNORE;
            }
            break;
            
        case VT_STATE_CSI_IGNORE:
            if (ch >= 0x40 && ch <= 0x7E) {
                parser->state = VT_STATE_GROUND;
            }
            break;
            
        case VT_STATE_OSC_STRING:
            if (ch == 0x07 || ch == 0x1B) {
                // End of OSC
                parser->osc_buffer[parser->osc_length] = '\0';
                handle_osc(parser, ch == 0x07);
                parser->state = (ch == 0x1B) ? VT_STATE_ESCAPE : VT_STATE_GROUND;
            } else if (osc_grow(parser)) {
                parser->osc_buffer[parser->osc_length++] = ch;
            } else {
                parser->osc_truncated = true;
            }
            break;
            
        case VT_STATE_DCS_ENTRY:
        case VT_STATE_DCS_PARAM:
        case VT_STATE_DCS_INTERMEDIATE:
        case VT_STATE_DCS_PASSTHROUGH:
        case VT_STATE_DCS_IGNORE:
            // DCS sequences - consume until ST
            if (ch == 0x1B) {
                parser->state = VT_STATE_ESCAPE;
            } else if (ch == 0x07) {
                parser->state = VT_STATE_GROUND;
            }
            break;
            
        case VT_STATE_SOS_PM_APC_STRING:
            // Consume until ST
            if (ch == 0x1B) {
                parser->state = VT_STATE_ESCAPE;
            } else if (ch == 0x07) {
                parser->state = VT_STATE_GROUND;
            }
            break;
            
        case VT_STATE_ESCAPE_INTERMEDIATE:
            // Keep collecting intermediate bytes; the final (0x30-0x7E) ends the escape. Charset
            // designations and similar nF escapes aren't rendered, so consume the final without printing.
            if (ch < 0x20 || ch > 0x2F) {
                parser->state = VT_STATE_GROUND;
            }
            break;
    }
}

// Public API implementation

VtParser* vt_parser_create(int rows, int cols) {
    VtParser* parser = (VtParser*)calloc(1, sizeof(VtParser));
    if (!parser) return NULL;
    
    parser->state = VT_STATE_GROUND;
    
    // Create screens
    parser->primary.cells = NULL;
    parser->alternate.cells = NULL;
    
    VtScreen* primary = screen_create(rows, cols);
    VtScreen* alternate = screen_create(rows, cols);
    
    if (!primary || !alternate) {
        screen_destroy(primary);
        screen_destroy(alternate);
        free(parser);
        return NULL;
    }
    
    parser->primary = *primary;
    parser->alternate = *alternate;
    free(primary);
    free(alternate);
    
    parser->active = &parser->primary;

    // Allocate dirty row tracking. Failure paths free the embedded screens' cells directly —
    // screen_destroy would free() the embedded structs themselves, which are not heap pointers.
    parser->dirty_rows = (bool*)calloc(rows, sizeof(bool));
    if (!parser->dirty_rows) {
        free(parser->primary.cells);
        free(parser->alternate.cells);
        free(parser);
        return NULL;
    }

    // OSC accumulator (grows on demand — see osc_grow)
    parser->osc_cap = VT_OSC_BUFFER_CAP;
    parser->osc_buffer = (char*)malloc((size_t)parser->osc_cap);
    if (!parser->osc_buffer) {
        free(parser->dirty_rows);
        free(parser->primary.cells);
        free(parser->alternate.cells);
        free(parser);
        return NULL;
    }

    // Allocate scrollback ring buffer (non-fatal if it fails; scrollback just stays empty).
    parser->scrollback_cap = VT_SCROLLBACK_CAP;
    parser->scrollback_cols = cols;
    parser->scrollback_count = 0;
    parser->scrollback_head = 0;
    parser->scrollback = (VtCell*)calloc((size_t)parser->scrollback_cap * (size_t)cols, sizeof(VtCell));
    if (!parser->scrollback) {
        parser->scrollback_cap = 0;
    }

    // Initialize default colors
    parser->fg.mode = 0;
    parser->bg.mode = 0;
    parser->attrs = 0;

    // calloc zeroed every mode flag; autowrap and alternate-scroll (xterm-style wheel->arrows
    // on the alt screen, resettable via DECRST ?1007) default on.
    parser->decawm = true;
    parser->alt_scroll = true;

    return parser;
}

void vt_parser_destroy(VtParser* parser) {
    if (!parser) return;

    vt_parser_osc_clear(parser);
    free(parser->osc_buffer);
    free(parser->primary.cells);
    free(parser->alternate.cells);
    free(parser->dirty_rows);
    free(parser->scrollback);
    free(parser);
}

int vt_parser_osc_event_count(const VtParser* parser) {
    return parser ? parser->osc_event_count : 0;
}

bool vt_parser_osc_event_at(const VtParser* parser, int index, int* code, const char** payload) {
    if (!parser || index < 0 || index >= parser->osc_event_count) return false;
    int idx = (parser->osc_event_head + index) % VT_OSC_EVENT_CAP;
    if (code) *code = parser->osc_events[idx].code;
    if (payload) *payload = parser->osc_events[idx].payload;
    return true;
}

void vt_parser_osc_clear(VtParser* parser) {
    if (!parser) return;
    for (int k = 0; k < parser->osc_event_count; k++) {
        int idx = (parser->osc_event_head + k) % VT_OSC_EVENT_CAP;
        free(parser->osc_events[idx].payload);
        parser->osc_events[idx].payload = NULL;
    }
    parser->osc_event_count = 0;
    parser->osc_event_head = 0;
}

int vt_parser_take_responses(VtParser* parser, uint8_t* out, int cap) {
    if (!parser || !out || cap <= 0) return 0;
    int n = parser->response_len < cap ? parser->response_len : cap;
    memcpy(out, parser->responses, (size_t)n);
    if (n < parser->response_len) {
        memmove(parser->responses, parser->responses + n, (size_t)(parser->response_len - n));
    }
    parser->response_len -= n;
    return n;
}

int vt_parser_input_modes(const VtParser* parser) {
    if (!parser) return 0;
    int modes = 0;
    if (parser->decckm) modes |= VT_MODE_APP_CURSOR_KEYS;
    if (parser->bracketed_paste) modes |= VT_MODE_BRACKETED_PASTE;
    if (parser->focus_events) modes |= VT_MODE_FOCUS_EVENTS;
    if (parser->alt_scroll) modes |= VT_MODE_ALT_SCROLL;
    if (parser->sync_output) modes |= VT_MODE_SYNC_OUTPUT;
    if (parser->active == &parser->alternate) modes |= VT_MODE_ALT_SCREEN;
    modes |= ((int)parser->mouse_mode & 0x7) << VT_MODE_MOUSE_SHIFT;
    modes |= ((int)parser->mouse_enc & 0x3) << VT_MODE_MOUSE_ENC_SHIFT;
    return modes;
}

int vt_parser_scrollback_count(const VtParser* parser) {
    if (!parser) return 0;
    // The alternate screen (vim/htop/less) manages its own buffer; no scrollback there.
    if (parser->active == &parser->alternate) return 0;
    return parser->scrollback_count;
}

const VtCell* vt_parser_row_ptr(const VtParser* parser, int row) {
    if (!parser) return NULL;
    const VtScreen* screen = parser->active;
    if (row >= 0) {
        if (row >= screen->rows) return NULL;
        return &screen->cells[row * screen->cols];
    }
    // Negative row -> scrollback. row == -1 is the most recently scrolled-off line.
    int sb_count = vt_parser_scrollback_count(parser);
    int line = sb_count + row;
    if (line < 0 || line >= sb_count) return NULL;
    int idx = (parser->scrollback_head + line) % parser->scrollback_cap;
    return &parser->scrollback[idx * parser->scrollback_cols];
}

int vt_parser_row_cols(const VtParser* parser, int row) {
    if (!parser) return 0;
    return row >= 0 ? parser->active->cols : parser->scrollback_cols;
}

const VtCell* vt_parser_cell_at(const VtParser* parser, int row, int col) {
    if (!parser) return NULL;
    if (col < 0 || col >= parser->active->cols) return NULL;
    if (row < 0 && col >= parser->scrollback_cols) return NULL;
    const VtCell* base = vt_parser_row_ptr(parser, row);
    return base ? base + col : NULL;
}

void vt_parser_feed(VtParser* parser, const uint8_t* data, size_t len) {
    if (!parser || !data) return;

    for (size_t i = 0; i < len; i++) {
        // Fast path: in GROUND state, a run of printable ASCII (the dominant byte class in
        // streamed output) is written directly into the row — one bounds check and one dirty
        // mark per run instead of per-byte state dispatch + per-cell checks. The run stops at
        // the row edge, leaving the same parked-cursor state the slow path's deferred wrap
        // produces; 0x7F is included because the slow path prints every byte in [0x20, 0x7F].
        if (parser->state == VT_STATE_GROUND && parser->utf8_left == 0 &&
            data[i] >= 0x20 && data[i] < 0x80) {
            VtScreen* s = parser->active;
            if (s->cursor_col < s->cols && s->cursor_row >= 0 && s->cursor_row < s->rows) {
                size_t max_run = (size_t)(s->cols - s->cursor_col);
                if (max_run > len - i) max_run = len - i;
                VtCell* cell = &s->cells[s->cursor_row * s->cols + s->cursor_col];
                size_t run = 0;
                while (run < max_run && data[i + run] >= 0x20 && data[i + run] < 0x80) {
                    cell[run].ch = data[i + run];
                    cell[run].fg = parser->fg;
                    cell[run].bg = parser->bg;
                    cell[run].attrs = parser->attrs;
                    run++;
                }
                if (run > 0) {
                    s->cursor_col += (int)run;
                    if (parser->dirty_rows) parser->dirty_rows[s->cursor_row] = true;
                    i += run - 1;
                    continue;
                }
            }
        }
        parser_transition(parser, data[i]);
    }
}

// Copy a row (col-limited) from src cells to dst cells.
static void copy_row_cells(VtCell* dst, int dst_cols, const VtCell* src, int src_cols) {
    int n = dst_cols < src_cols ? dst_cols : src_cols;
    for (int c = 0; c < n; c++) dst[c] = src[c];
}

void vt_parser_resize(VtParser* parser, int rows, int cols) {
    if (!parser) return;
    if (rows <= 0 || cols <= 0) return;
    int old_rows = parser->primary.rows;
    int old_cols = parser->primary.cols;
    if (old_rows == rows && old_cols == cols) return;

    bool on_primary = (parser->active == &parser->primary);

    // 1) Reallocate scrollback to the new column count first (preserve stored lines; no width reflow),
    //    re-basing the ring so logical line L lives at index ((head + L) % cap).
    if (parser->scrollback && parser->scrollback_cap > 0 && cols != parser->scrollback_cols) {
        VtCell* new_sb = (VtCell*)calloc((size_t)parser->scrollback_cap * (size_t)cols, sizeof(VtCell));
        if (new_sb) {
            for (int i = 0; i < parser->scrollback_cap * cols; i++) new_sb[i].ch = ' ';
            int ccols = cols < parser->scrollback_cols ? cols : parser->scrollback_cols;
            for (int l = 0; l < parser->scrollback_count; l++) {
                int src = (parser->scrollback_head + l) % parser->scrollback_cap;
                for (int c = 0; c < ccols; c++) new_sb[l * cols + c] = parser->scrollback[src * parser->scrollback_cols + c];
            }
            free(parser->scrollback);
            parser->scrollback = new_sb;
            parser->scrollback_cols = cols;
            parser->scrollback_head = 0;
        }
    }

    // 2) Resize the PRIMARY screen, anchoring content to the bottom and reflowing through scrollback so
    //    nothing is lost or duplicated. Growing pulls recent scrollback lines back into the top; shrinking
    //    pushes the top lines into scrollback. The cursor moves with the content (NOT reset to 0,0 — that
    //    was the bug that let post-resize redraws corrupt existing rows).
    {
        VtScreen* old = &parser->primary;
        VtScreen* nw = screen_create(rows, cols);
        if (!nw) return;

        if (rows >= old_rows) {
            int delta = rows - old_rows;
            // Backfill the new height from scrollback, but only as far as history actually exists.
            // Old content shifts DOWN by `from_sb`; any remaining new rows are appended at the BOTTOM
            // (blank). With no history this keeps content top-anchored (no blank gap above the prompt).
            int from_sb = parser->scrollback_count < delta ? parser->scrollback_count : delta;
            for (int r = 0; r < old_rows; r++) {
                copy_row_cells(&nw->cells[(r + from_sb) * cols], cols, &old->cells[r * old_cols], old_cols);
            }
            for (int k = 0; k < from_sb; k++) {
                int sb_line = parser->scrollback_count - from_sb + k;
                int src = (parser->scrollback_head + sb_line) % parser->scrollback_cap;
                copy_row_cells(&nw->cells[k * cols], cols,
                               &parser->scrollback[src * parser->scrollback_cols], parser->scrollback_cols);
            }
            parser->scrollback_count -= from_sb;
            nw->cursor_row = old->cursor_row + from_sb;
        } else {
            int delta = old_rows - rows;
            // Cursor-anchored: scroll off only as many TOP rows as needed to keep the cursor on the
            // last visible row. If the cursor already fits in the new height (screen mostly empty),
            // scroll == 0 and we simply drop blank rows from the bottom — the prompt is NOT pushed
            // into scrollback (which previously caused a duplicate prompt when growing back).
            int scroll = old->cursor_row - (rows - 1);
            if (scroll < 0) scroll = 0;
            if (scroll > delta) scroll = delta;
            for (int r = 0; r < scroll; r++) {
                scrollback_push(parser, &old->cells[r * old_cols], old_cols);
            }
            for (int r = 0; r < rows; r++) {
                copy_row_cells(&nw->cells[r * cols], cols, &old->cells[(r + scroll) * old_cols], old_cols);
            }
            nw->cursor_row = old->cursor_row - scroll;
        }
        nw->cursor_col = old->cursor_col;
        if (nw->cursor_row < 0) nw->cursor_row = 0;
        if (nw->cursor_row >= rows) nw->cursor_row = rows - 1;
        if (nw->cursor_col < 0) nw->cursor_col = 0;
        if (nw->cursor_col >= cols) nw->cursor_col = cols - 1;
        nw->cursor_visible = old->cursor_visible;
        nw->scroll_top = 0;
        nw->scroll_bottom = rows - 1;
        free(old->cells);
        parser->primary = *nw;
        free(nw);
    }

    // 3) Resize the ALTERNATE screen with a simple clamp/copy. Full-screen apps (vim, htop, less) repaint
    //    themselves on SIGWINCH, and the alternate screen has no scrollback.
    {
        VtScreen* old = &parser->alternate;
        VtScreen* nw = screen_create(rows, cols);
        if (!nw) return;
        int crows = rows < old_rows ? rows : old_rows;
        for (int r = 0; r < crows; r++) {
            copy_row_cells(&nw->cells[r * cols], cols, &old->cells[r * old_cols], old_cols);
        }
        nw->cursor_row = old->cursor_row < rows ? old->cursor_row : rows - 1;
        nw->cursor_col = old->cursor_col < cols ? old->cursor_col : cols - 1;
        nw->cursor_visible = old->cursor_visible;
        nw->scroll_top = 0;
        nw->scroll_bottom = rows - 1;
        free(old->cells);
        parser->alternate = *nw;
        free(nw);
    }

    parser->active = on_primary ? &parser->primary : &parser->alternate;

    free(parser->dirty_rows);
    parser->dirty_rows = (bool*)calloc(rows, sizeof(bool));

    parser->full_refresh = true;
}

const VtScreen* vt_parser_get_screen(const VtParser* parser) {
    return parser ? parser->active : NULL;
}

bool vt_parser_is_alternate_screen(const VtParser* parser) {
    return parser && parser->active == &parser->alternate;
}

void vt_parser_clear_dirty(VtParser* parser) {
    if (!parser || !parser->dirty_rows) return;
    memset(parser->dirty_rows, 0, parser->active->rows * sizeof(bool));
    parser->full_refresh = false;
}

void vt_parser_reset(VtParser* parser) {
    if (!parser) return;
    
    parser->state = VT_STATE_GROUND;
    parser->param_count = 0;
    parser->current_param = 0;
    parser->intermediate_count = 0;
    parser->osc_length = 0;
    parser->osc_truncated = false;
    vt_parser_osc_clear(parser);
    parser->response_len = 0;
    parser->utf8_acc = 0;
    parser->utf8_left = 0;
    parser->attrs = 0;
    parser->fg.mode = 0;
    parser->bg.mode = 0;

    parser->decckm = false;
    parser->decawm = true;
    parser->bracketed_paste = false;
    parser->focus_events = false;
    parser->alt_scroll = true;
    parser->sync_output = false;
    parser->mouse_mode = VT_MOUSE_OFF;
    parser->mouse_enc = VT_MOUSE_ENC_X10;
    
    screen_clear(&parser->primary);
    screen_clear(&parser->alternate);
    parser->active = &parser->primary;

    parser->scrollback_count = 0;
    parser->scrollback_head = 0;
    
    parser->primary.cursor_row = 0;
    parser->primary.cursor_col = 0;
    parser->alternate.cursor_row = 0;
    parser->alternate.cursor_col = 0;
    
    parser->full_refresh = true;
}
