#ifndef VT_PARSER_H
#define VT_PARSER_H

#include <stdint.h>
#include <stdbool.h>
#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

// Maximum parameters in a CSI sequence
#define VT_MAX_PARAMS 16

// Cell attributes
typedef enum {
    VT_ATTR_BOLD = 1 << 0,
    VT_ATTR_DIM = 1 << 1,
    VT_ATTR_ITALIC = 1 << 2,
    VT_ATTR_UNDERLINE = 1 << 3,
    VT_ATTR_BLINK = 1 << 4,
    VT_ATTR_INVERSE = 1 << 5,
    VT_ATTR_HIDDEN = 1 << 6,
    VT_ATTR_STRIKETHROUGH = 1 << 7,
} VtAttribute;

// Color representation (supports 256-color and truecolor)
typedef struct {
    uint8_t mode;      // 0=default, 1=indexed(256), 2=truecolor
    uint8_t index;     // For indexed mode (0-255)
    uint8_t r, g, b;   // For truecolor mode
} VtColor;

// Terminal cell
typedef struct {
    uint32_t ch;        // Unicode codepoint
    VtColor fg;         // Foreground color
    VtColor bg;         // Background color
    uint16_t attrs;     // Attribute flags
} VtCell;

// Parser states
typedef enum {
    VT_STATE_GROUND,
    VT_STATE_ESCAPE,
    VT_STATE_ESCAPE_INTERMEDIATE,
    VT_STATE_CSI_ENTRY,
    VT_STATE_CSI_PARAM,
    VT_STATE_CSI_INTERMEDIATE,
    VT_STATE_CSI_IGNORE,
    VT_STATE_OSC_STRING,
    VT_STATE_DCS_ENTRY,
    VT_STATE_DCS_PARAM,
    VT_STATE_DCS_INTERMEDIATE,
    VT_STATE_DCS_PASSTHROUGH,
    VT_STATE_DCS_IGNORE,
    VT_STATE_SOS_PM_APC_STRING,
} VtParserState;

// Screen buffer
typedef struct {
    VtCell* cells;      // Array of cells (rows * cols)
    int rows;
    int cols;
    int cursor_row;
    int cursor_col;
    bool cursor_visible;
    
    // Scroll region
    int scroll_top;
    int scroll_bottom;
    
    // Saved cursor state
    int saved_row;
    int saved_col;
    uint16_t saved_attrs;
    VtColor saved_fg;
    VtColor saved_bg;
} VtScreen;

// Parser context
typedef struct {
    VtParserState state;

    // UTF-8 decode accumulator for the GROUND state. utf8_left > 0 means we are in the middle of a
    // multi-byte sequence and expect that many more continuation bytes before emitting utf8_acc.
    uint32_t utf8_acc;
    int utf8_left;

    // CSI parameter storage
    int params[VT_MAX_PARAMS];
    int param_count;
    int current_param;
    
    // Intermediate characters
    char intermediates[4];
    int intermediate_count;
    
    // OSC string buffer
    char osc_buffer[256];
    int osc_length;
    
    // Current text attributes
    uint16_t attrs;
    VtColor fg;
    VtColor bg;
    
    // Primary and alternate screens
    VtScreen primary;
    VtScreen alternate;
    VtScreen* active;
    
    // Dirty region tracking
    bool* dirty_rows;
    bool full_refresh;

    // Scrollback ring buffer (lines that scrolled off the top of the primary screen)
    VtCell* scrollback;     // scrollback_cap * scrollback_cols cells
    int scrollback_cols;
    int scrollback_cap;     // capacity in lines
    int scrollback_count;   // number of lines currently stored
    int scrollback_head;    // ring index of the oldest line

    // Callback for output (bell, etc.)
    void (*on_bell)(void* userdata);
    void (*on_title_change)(void* userdata, const char* title);
    void* userdata;
} VtParser;

// Initialize parser
VtParser* vt_parser_create(int rows, int cols);

// Cleanup parser
void vt_parser_destroy(VtParser* parser);

// Feed input bytes to parser
void vt_parser_feed(VtParser* parser, const uint8_t* data, size_t len);

// Resize terminal
void vt_parser_resize(VtParser* parser, int rows, int cols);

// Get current screen state
const VtScreen* vt_parser_get_screen(const VtParser* parser);

// Number of scrollback lines available (0 while on the alternate screen).
int vt_parser_scrollback_count(const VtParser* parser);

// Get a cell by logical row. row >= 0 addresses the live screen; row < 0 addresses scrollback
// (row == -1 is the most-recently scrolled-off line). Returns NULL if out of range.
const VtCell* vt_parser_cell_at(const VtParser* parser, int row, int col);

// Check if screen is using alternate buffer
bool vt_parser_is_alternate_screen(const VtParser* parser);

// Clear dirty flags after rendering
void vt_parser_clear_dirty(VtParser* parser);

// Reset parser to initial state
void vt_parser_reset(VtParser* parser);

#ifdef __cplusplus
}
#endif

#endif // VT_PARSER_H
