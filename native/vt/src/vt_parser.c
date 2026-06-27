#include "vt_parser.h"
#include <stdlib.h>
#include <string.h>
#include <stdio.h>

#define VT_SCROLLBACK_CAP 2000

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
    
    // Initialize all cells to space with default colors
    for (int i = 0; i < rows * cols; i++) {
        screen->cells[i].ch = ' ';
        screen->cells[i].fg.mode = 0;
        screen->cells[i].bg.mode = 0;
        screen->cells[i].attrs = 0;
    }
    
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
    for (int i = 0; i < screen->rows * screen->cols; i++) {
        screen->cells[i].ch = ' ';
        screen->cells[i].fg.mode = 0;
        screen->cells[i].bg.mode = 0;
        screen->cells[i].attrs = 0;
    }
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
    for (int c = 0; c < n; c++) dst[c] = line[c];
    for (int c = n; c < parser->scrollback_cols; c++) {
        dst[c].ch = ' '; dst[c].fg.mode = 0; dst[c].bg.mode = 0; dst[c].attrs = 0;
    }
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

    // Move rows up
    for (int row = screen->scroll_top; row <= screen->scroll_bottom - n; row++) {
        memmove(&screen->cells[row * screen->cols],
                &screen->cells[(row + n) * screen->cols],
                screen->cols * sizeof(VtCell));
    }

    // Clear bottom lines
    for (int row = screen->scroll_bottom - n + 1; row <= screen->scroll_bottom; row++) {
        for (int col = 0; col < screen->cols; col++) {
            VtCell* cell = screen_cell_at(screen, row, col);
            if (cell) {
                cell->ch = ' ';
                cell->fg.mode = 0;
                cell->bg.mode = 0;
                cell->attrs = 0;
            }
        }
    }
}

// Scroll region down by n lines
static void screen_scroll_down(VtParser* parser, int n) {
    VtScreen* screen = parser->active;
    if (n <= 0) return;
    if (n > screen->scroll_bottom - screen->scroll_top + 1) {
        n = screen->scroll_bottom - screen->scroll_top + 1;
    }
    
    // Move rows down
    for (int row = screen->scroll_bottom; row >= screen->scroll_top + n; row--) {
        memmove(&screen->cells[row * screen->cols],
                &screen->cells[(row - n) * screen->cols],
                screen->cols * sizeof(VtCell));
    }
    
    // Clear top lines
    for (int row = screen->scroll_top; row < screen->scroll_top + n; row++) {
        for (int col = 0; col < screen->cols; col++) {
            VtCell* cell = screen_cell_at(screen, row, col);
            if (cell) {
                cell->ch = ' ';
                cell->fg.mode = 0;
                cell->bg.mode = 0;
                cell->attrs = 0;
            }
        }
    }
}

// Write character at cursor position
static void screen_write_char(VtParser* parser, uint32_t ch) {
    VtScreen* screen = parser->active;
    
    // Handle line wrap
    if (screen->cursor_col >= screen->cols) {
        screen->cursor_col = 0;
        screen->cursor_row++;
        
        if (screen->cursor_row > screen->scroll_bottom) {
            screen_scroll_up(parser, 1);
            screen->cursor_row = screen->scroll_bottom;
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

// Handle CSI sequence
static void handle_csi(VtParser* parser, char final_char) {
    VtScreen* screen = parser->active;
    
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
                // Clear from cursor to end
                for (int col = screen->cursor_col; col < screen->cols; col++) {
                    VtCell* cell = screen_cell_at(screen, screen->cursor_row, col);
                    if (cell) {
                        cell->ch = ' ';
                        cell->fg.mode = 0;
                        cell->bg.mode = 0;
                        cell->attrs = 0;
                    }
                }
                for (int row = screen->cursor_row + 1; row < screen->rows; row++) {
                    for (int col = 0; col < screen->cols; col++) {
                        VtCell* cell = screen_cell_at(screen, row, col);
                        if (cell) {
                            cell->ch = ' ';
                            cell->fg.mode = 0;
                            cell->bg.mode = 0;
                            cell->attrs = 0;
                        }
                    }
                    if (parser->dirty_rows) parser->dirty_rows[row] = true;
                }
                if (parser->dirty_rows) parser->dirty_rows[screen->cursor_row] = true;
            } else if (mode == 1) {
                // Clear from beginning to cursor
                for (int row = 0; row < screen->cursor_row; row++) {
                    for (int col = 0; col < screen->cols; col++) {
                        VtCell* cell = screen_cell_at(screen, row, col);
                        if (cell) {
                            cell->ch = ' ';
                            cell->fg.mode = 0;
                            cell->bg.mode = 0;
                            cell->attrs = 0;
                        }
                    }
                    if (parser->dirty_rows) parser->dirty_rows[row] = true;
                }
                for (int col = 0; col <= screen->cursor_col; col++) {
                    VtCell* cell = screen_cell_at(screen, screen->cursor_row, col);
                    if (cell) {
                        cell->ch = ' ';
                        cell->fg.mode = 0;
                        cell->bg.mode = 0;
                        cell->attrs = 0;
                    }
                }
                if (parser->dirty_rows) parser->dirty_rows[screen->cursor_row] = true;
            } else if (mode == 2) {
                // Clear entire screen
                screen_clear(screen);
                parser->full_refresh = true;
            }
            break;
        }
        case 'K': { // Erase in Line
            int mode = parser->param_count > 0 ? parser->params[0] : 0;
            if (mode == 0) {
                // Clear from cursor to end of line
                for (int col = screen->cursor_col; col < screen->cols; col++) {
                    VtCell* cell = screen_cell_at(screen, screen->cursor_row, col);
                    if (cell) {
                        cell->ch = ' ';
                        cell->fg.mode = 0;
                        cell->bg.mode = 0;
                        cell->attrs = 0;
                    }
                }
            } else if (mode == 1) {
                // Clear from beginning of line to cursor
                for (int col = 0; col <= screen->cursor_col; col++) {
                    VtCell* cell = screen_cell_at(screen, screen->cursor_row, col);
                    if (cell) {
                        cell->ch = ' ';
                        cell->fg.mode = 0;
                        cell->bg.mode = 0;
                        cell->attrs = 0;
                    }
                }
            } else if (mode == 2) {
                // Clear entire line
                for (int col = 0; col < screen->cols; col++) {
                    VtCell* cell = screen_cell_at(screen, screen->cursor_row, col);
                    if (cell) {
                        cell->ch = ' ';
                        cell->fg.mode = 0;
                        cell->bg.mode = 0;
                        cell->attrs = 0;
                    }
                }
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
        case 'm': { // SGR
            handle_sgr(parser);
            break;
        }
        case 'r': { // Set Scrolling Region
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
        case 's': { // Save Cursor
            screen->saved_row = screen->cursor_row;
            screen->saved_col = screen->cursor_col;
            screen->saved_attrs = parser->attrs;
            screen->saved_fg = parser->fg;
            screen->saved_bg = parser->bg;
            break;
        }
        case 'u': { // Restore Cursor
            screen->cursor_row = screen->saved_row;
            screen->cursor_col = screen->saved_col;
            parser->attrs = screen->saved_attrs;
            parser->fg = screen->saved_fg;
            parser->bg = screen->saved_bg;
            break;
        }
        case '?': { // Private modes
            if (parser->intermediate_count > 0 && parser->intermediates[0] == '?') {
                int mode = parser->param_count > 0 ? parser->params[0] : 0;
                if (mode == 1049 || mode == 47 || mode == 1047) {
                    // Alternate screen buffer
                    if (final_char == 'h') {
                        // Switch to alternate
                        parser->active = &parser->alternate;
                        screen_clear(parser->active);
                    } else if (final_char == 'l') {
                        // Switch to primary
                        parser->active = &parser->primary;
                    }
                    parser->full_refresh = true;
                } else if (mode == 25) {
                    // Cursor visibility
                    screen->cursor_visible = (final_char == 'h');
                }
            }
            break;
        }
    }
}

// Handle OSC (Operating System Command)
static void handle_osc(VtParser* parser) {
    // Parse command number
    int cmd = 0;
    int i = 0;
    while (i < parser->osc_length && parser->osc_buffer[i] >= '0' && parser->osc_buffer[i] <= '9') {
        cmd = cmd * 10 + (parser->osc_buffer[i] - '0');
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
            } else if (ch == 'P') {
                parser->state = VT_STATE_DCS_ENTRY;
            } else if (ch == 'X' || ch == '^' || ch == '_') {
                parser->state = VT_STATE_SOS_PM_APC_STRING;
            } else {
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
            } else if (ch == '?') {
                parser->intermediates[parser->intermediate_count++] = ch;
                parser->state = VT_STATE_CSI_PARAM;
            } else {
                parser->state = VT_STATE_GROUND;
            }
            break;
            
        case VT_STATE_CSI_PARAM:
            if (ch >= '0' && ch <= '9') {
                parser->current_param = parser->current_param * 10 + (ch - '0');
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
                handle_osc(parser);
                parser->state = (ch == 0x1B) ? VT_STATE_ESCAPE : VT_STATE_GROUND;
            } else if (parser->osc_length < 255) {
                parser->osc_buffer[parser->osc_length++] = ch;
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
            parser->state = VT_STATE_GROUND;
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
    
    // Allocate dirty row tracking
    parser->dirty_rows = (bool*)calloc(rows, sizeof(bool));
    if (!parser->dirty_rows) {
        screen_destroy(&parser->primary);
        screen_destroy(&parser->alternate);
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
    
    return parser;
}

void vt_parser_destroy(VtParser* parser) {
    if (!parser) return;

    free(parser->primary.cells);
    free(parser->alternate.cells);
    free(parser->dirty_rows);
    free(parser->scrollback);
    free(parser);
}

int vt_parser_scrollback_count(const VtParser* parser) {
    if (!parser) return 0;
    // The alternate screen (vim/htop/less) manages its own buffer; no scrollback there.
    if (parser->active == &parser->alternate) return 0;
    return parser->scrollback_count;
}

const VtCell* vt_parser_cell_at(const VtParser* parser, int row, int col) {
    if (!parser) return NULL;
    const VtScreen* screen = parser->active;
    if (col < 0 || col >= screen->cols) return NULL;
    if (row >= 0) {
        if (row >= screen->rows) return NULL;
        return &screen->cells[row * screen->cols + col];
    }
    // Negative row -> scrollback. row == -1 is the most recently scrolled-off line.
    int sb_count = vt_parser_scrollback_count(parser);
    int line = sb_count + row;
    if (line < 0 || line >= sb_count) return NULL;
    if (col >= parser->scrollback_cols) return NULL;
    int idx = (parser->scrollback_head + line) % parser->scrollback_cap;
    return &parser->scrollback[idx * parser->scrollback_cols + col];
}

void vt_parser_feed(VtParser* parser, const uint8_t* data, size_t len) {
    if (!parser || !data) return;
    
    for (size_t i = 0; i < len; i++) {
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
    parser->utf8_acc = 0;
    parser->utf8_left = 0;
    parser->attrs = 0;
    parser->fg.mode = 0;
    parser->bg.mode = 0;
    
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
