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

// Initial OSC accumulation capacity (code + ';' + payload + NUL). The buffer grows geometrically
// up to VT_OSC_BUFFER_MAX so OSC 52 clipboard payloads (base64, easily tens of KB) survive intact;
// a sequence exceeding the max is marked truncated and clipboard events are then DROPPED — a
// missing clipboard write is recoverable, a silently shortened one is not.
#define VT_OSC_BUFFER_CAP 4096
#define VT_OSC_BUFFER_MAX (1 << 20)

// Bounded queue of JCode shell-integration OSC events (7711 open-file, 7712 tab title, 7713 task
// complete) awaiting a host drain. The oldest event is dropped when the queue is full.
#define VT_OSC_EVENT_CAP 32

// Pending answerback bytes (DA/DSR/CPR/DECRQM/OSC-color replies) awaiting a host drain to the PTY.
// Replies are small (< 32 bytes each); when the buffer is full further replies are dropped rather
// than blocking the feed.
#define VT_RESPONSE_CAP 256

// A queued shell-integration OSC event.
typedef struct {
    int code;
    char* payload;  // heap copy owned by the parser, NUL-terminated
} VtOscEvent;

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

// Mouse tracking level (DECSET 9 < 1000 < 1002 < 1003; stored as the ordinal, not the mode number).
typedef enum {
    VT_MOUSE_OFF = 0,
    VT_MOUSE_X10 = 1,     // ?9    press only
    VT_MOUSE_NORMAL = 2,  // ?1000 press + release + wheel
    VT_MOUSE_BUTTON = 3,  // ?1002 + drag motion
    VT_MOUSE_ANY = 4,     // ?1003 + all motion
} VtMouseMode;

// Mouse coordinate encoding (DECSET 1006/1015; ?1005 UTF-8 coords is deliberately unsupported —
// see apply_dec_mode). Legacy X10 single-byte coords by default.
typedef enum {
    VT_MOUSE_ENC_X10 = 0,
    VT_MOUSE_ENC_SGR = 2,    // ?1006
    VT_MOUSE_ENC_URXVT = 3,  // ?1015
} VtMouseEncoding;

// Bit layout of vt_parser_input_modes(): the DEC private modes the host consults when encoding
// input (arrow keys, paste, scroll gestures) and gating repaints, plus the alt-screen flag so the
// host reads one mutually-consistent snapshot. Mirrored in VtParser.kt.
#define VT_MODE_APP_CURSOR_KEYS (1 << 0)  // ?1 DECCKM
#define VT_MODE_BRACKETED_PASTE (1 << 1)  // ?2004
#define VT_MODE_FOCUS_EVENTS (1 << 2)     // ?1004
#define VT_MODE_ALT_SCROLL (1 << 3)       // ?1007 (default on)
#define VT_MODE_SYNC_OUTPUT (1 << 4)      // ?2026 (set while a synchronized update is open)
#define VT_MODE_ALT_SCREEN (1 << 5)       // alternate screen buffer active
#define VT_MODE_MOUSE_SHIFT 8             // bits 8-10: VtMouseMode ordinal
#define VT_MODE_MOUSE_ENC_SHIFT 12        // bits 12-13: VtMouseEncoding ordinal

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
    
    // OSC string buffer (heap; grows from VT_OSC_BUFFER_CAP up to VT_OSC_BUFFER_MAX)
    char* osc_buffer;
    int osc_cap;
    int osc_length;
    bool osc_truncated;

    // Queued shell-integration OSC events (ring indexed from osc_event_head; see VT_OSC_EVENT_CAP)
    VtOscEvent osc_events[VT_OSC_EVENT_CAP];
    int osc_event_head;
    int osc_event_count;

    // Pending answerback bytes (DA/DSR/DECRQM/OSC replies) for the host to write to the PTY.
    uint8_t responses[VT_RESPONSE_CAP];
    int response_len;

    // DEC private modes consulted by the host for input encoding / scroll routing (see VT_MODE_*).
    bool decckm;           // ?1  application cursor keys
    bool decawm;           // ?7  autowrap (default on)
    bool bracketed_paste;  // ?2004
    bool focus_events;     // ?1004
    bool alt_scroll;       // ?1007 wheel -> arrow keys on the alternate screen
    bool sync_output;      // ?2026 synchronized update open
    uint8_t mouse_mode;    // VtMouseMode
    uint8_t mouse_enc;     // VtMouseEncoding


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
    int64_t scrollback_pushed;  // monotonic total of lines ever pushed (keeps growing at capacity)

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

// Resolve a whole logical row to its first cell (same row semantics as vt_parser_cell_at), so
// bulk readers pay the live/scrollback resolution once per row instead of once per cell. NULL if
// out of range; the row's width is vt_parser_row_cols (scrollback rows may be narrower after a
// resize).
const VtCell* vt_parser_row_ptr(const VtParser* parser, int row);
int vt_parser_row_cols(const VtParser* parser, int row);

// Check if screen is using alternate buffer
bool vt_parser_is_alternate_screen(const VtParser* parser);

// Clear dirty flags after rendering
void vt_parser_clear_dirty(VtParser* parser);

// Number of queued shell-integration OSC events (7711-7713).
int vt_parser_osc_event_count(const VtParser* parser);

// Read queued OSC event `index` (0 = oldest) without removing it. The payload pointer is owned by
// the parser and only valid until the next feed/clear/destroy. Returns false if out of range.
bool vt_parser_osc_event_at(const VtParser* parser, int index, int* code, const char** payload);

// Drop (and free) all queued OSC events.
void vt_parser_osc_clear(VtParser* parser);

// Copy up to `cap` pending answerback bytes (DA/DSR/CPR/DECRQM/OSC replies) into `out`, removing
// them from the parser. Returns the number of bytes copied (0 when nothing is pending). The host
// must write these to the PTY after each feed, or query-probing programs (Claude Code, fzf, …)
// time out waiting for the terminal to identify itself.
int vt_parser_take_responses(VtParser* parser, uint8_t* out, int cap);

// Packed snapshot of the input-affecting DEC private modes (see the VT_MODE_* bit layout).
int vt_parser_input_modes(const VtParser* parser);

// Reset parser to initial state
void vt_parser_reset(VtParser* parser);

#ifdef __cplusplus
}
#endif

#endif // VT_PARSER_H
