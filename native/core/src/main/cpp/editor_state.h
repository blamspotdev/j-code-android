#pragma once

#include <cstdint>
#include <vector>
#include <string>
#include <memory>
#include <functional>
#include <mutex>

namespace jcode {

/**
 * Caret position within the buffer.
 */
struct Caret {
    int32_t anchor;  // Selection anchor (byte offset)
    int32_t head;    // Cursor position (byte offset)
    int32_t preferred_column;  // Preferred column for vertical movement (-1 = none)

    Caret(int32_t a = 0, int32_t h = 0, int32_t col = -1)
        : anchor(a), head(h), preferred_column(col) {}

    bool is_selection() const { return anchor != head; }
    int32_t start() const { return std::min(anchor, head); }
    int32_t end() const { return std::max(anchor, head); }
};

/**
 * Viewport describing the visible region.
 */
struct Viewport {
    int32_t scroll_y = 0;
    int32_t scroll_x = 0;
    int32_t width_px = 0;
    int32_t height_px = 0;
    int32_t line_height_px = 20;

    int32_t visible_line_top() const {
        return line_height_px > 0 ? scroll_y / line_height_px : 0;
    }

    int32_t visible_line_bottom() const {
        return line_height_px > 0 ? (scroll_y + height_px) / line_height_px + 1 : 0;
    }
};

/**
 * Fold range [start_line, end_line] inclusive.
 */
struct FoldRange {
    int32_t start_line;
    int32_t end_line;
    std::string summary_text;

    FoldRange(int32_t s, int32_t e, const std::string& txt = "")
        : start_line(s), end_line(e), summary_text(txt) {}
};

/**
 * Editor event types.
 */
enum class EditorEventType {
    TEXT_CHANGED,
    SELECTION_CHANGED,
    VIEWPORT_CHANGED,
    FOLDS_CHANGED,
    DECORATIONS_CHANGED
};

/**
 * Editor event data.
 */
struct EditorEvent {
    EditorEventType type;
    
    // For TEXT_CHANGED
    int32_t range_start = 0;
    int32_t range_end = 0;
    int32_t new_length = 0;
    
    // For VIEWPORT_CHANGED
    Viewport viewport;

    EditorEvent(EditorEventType t) : type(t) {}
};

/**
 * Event listener callback.
 */
using EventListener = std::function<void(const EditorEvent&)>;

/**
 * EditorState manages the core editor state: carets, viewport, folds.
 * Thread-safe for single writer, multiple readers.
 * 
 * Note: Buffer management is handled separately by PieceTreeBuffer.
 * This class focuses on UI state (carets, viewport, folds).
 */
class EditorState {
public:
    EditorState();
    ~EditorState();

    // Caret management
    void set_carets(const std::vector<Caret>& carets);
    std::vector<Caret> get_carets() const;
    void add_caret(const Caret& caret);
    void clear_carets();

    // Viewport management
    void set_viewport(const Viewport& viewport);
    Viewport get_viewport() const;
    void scroll_to(int32_t line, int32_t column, int32_t line_height_px);

    // Fold management
    void add_fold(const FoldRange& fold);
    void remove_fold(const FoldRange& fold);
    void toggle_fold(const FoldRange& fold);
    std::vector<FoldRange> get_folds() const;
    void clear_folds();

    // Event system
    void add_event_listener(EventListener listener);
    void remove_event_listener(EventListener listener);

    // Read-only flag
    void set_read_only(bool read_only);
    bool is_read_only() const;

private:
    void emit_event(const EditorEvent& event);

    mutable std::mutex mutex_;
    std::vector<Caret> carets_;
    Viewport viewport_;
    std::vector<FoldRange> folds_;
    std::vector<EventListener> event_listeners_;
    bool read_only_ = false;
};

}  // namespace jcode
