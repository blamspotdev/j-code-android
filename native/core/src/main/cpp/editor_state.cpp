#include "editor_state.h"
#include <algorithm>

namespace jcode {

EditorState::EditorState() {
    // Initialize with a single caret at position 0
    carets_.push_back(Caret(0, 0));
}

EditorState::~EditorState() = default;

// Caret management

void EditorState::set_carets(const std::vector<Caret>& carets) {
    std::lock_guard<std::mutex> lock(mutex_);
    carets_ = carets;
    
    // Sort by head position
    std::sort(carets_.begin(), carets_.end(),
              [](const Caret& a, const Caret& b) { return a.head < b.head; });
    
    EditorEvent event(EditorEventType::SELECTION_CHANGED);
    emit_event(event);
}

std::vector<Caret> EditorState::get_carets() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return carets_;
}

void EditorState::add_caret(const Caret& caret) {
    std::lock_guard<std::mutex> lock(mutex_);
    carets_.push_back(caret);
    
    // Sort by head position
    std::sort(carets_.begin(), carets_.end(),
              [](const Caret& a, const Caret& b) { return a.head < b.head; });
    
    EditorEvent event(EditorEventType::SELECTION_CHANGED);
    emit_event(event);
}

void EditorState::clear_carets() {
    std::lock_guard<std::mutex> lock(mutex_);
    carets_.clear();
    
    EditorEvent event(EditorEventType::SELECTION_CHANGED);
    emit_event(event);
}

// Viewport management

void EditorState::set_viewport(const Viewport& viewport) {
    std::lock_guard<std::mutex> lock(mutex_);
    viewport_ = viewport;
    
    EditorEvent event(EditorEventType::VIEWPORT_CHANGED);
    event.viewport = viewport;
    emit_event(event);
}

Viewport EditorState::get_viewport() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return viewport_;
}

void EditorState::scroll_to(int32_t line, int32_t column, int32_t line_height_px) {
    std::lock_guard<std::mutex> lock(mutex_);
    
    // Estimate scroll position from line
    int32_t target_scroll_y = line * line_height_px;
    viewport_.scroll_y = target_scroll_y;
    
    EditorEvent event(EditorEventType::VIEWPORT_CHANGED);
    event.viewport = viewport_;
    emit_event(event);
}

// Fold management

void EditorState::add_fold(const FoldRange& fold) {
    std::lock_guard<std::mutex> lock(mutex_);
    
    // Check if fold already exists
    auto it = std::find_if(folds_.begin(), folds_.end(),
                           [&fold](const FoldRange& f) {
                               return f.start_line == fold.start_line &&
                                      f.end_line == fold.end_line;
                           });
    
    if (it == folds_.end()) {
        folds_.push_back(fold);
        
        EditorEvent event(EditorEventType::FOLDS_CHANGED);
        emit_event(event);
    }
}

void EditorState::remove_fold(const FoldRange& fold) {
    std::lock_guard<std::mutex> lock(mutex_);
    
    folds_.erase(
        std::remove_if(folds_.begin(), folds_.end(),
                       [&fold](const FoldRange& f) {
                           return f.start_line == fold.start_line &&
                                  f.end_line == fold.end_line;
                       }),
        folds_.end());
    
    EditorEvent event(EditorEventType::FOLDS_CHANGED);
    emit_event(event);
}

void EditorState::toggle_fold(const FoldRange& fold) {
    std::lock_guard<std::mutex> lock(mutex_);
    
    auto it = std::find_if(folds_.begin(), folds_.end(),
                           [&fold](const FoldRange& f) {
                               return f.start_line == fold.start_line &&
                                      f.end_line == fold.end_line;
                           });
    
    if (it != folds_.end()) {
        folds_.erase(it);
    } else {
        folds_.push_back(fold);
    }
    
    EditorEvent event(EditorEventType::FOLDS_CHANGED);
    emit_event(event);
}

std::vector<FoldRange> EditorState::get_folds() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return folds_;
}

void EditorState::clear_folds() {
    std::lock_guard<std::mutex> lock(mutex_);
    folds_.clear();
    
    EditorEvent event(EditorEventType::FOLDS_CHANGED);
    emit_event(event);
}

// Event system

void EditorState::add_event_listener(EventListener listener) {
    std::lock_guard<std::mutex> lock(mutex_);
    event_listeners_.push_back(listener);
}

void EditorState::remove_event_listener(EventListener listener) {
    std::lock_guard<std::mutex> lock(mutex_);
    // Note: This is a simplified implementation. In production, you'd want
    // to use a more robust listener management system (e.g., listener IDs).
}

// Read-only flag

void EditorState::set_read_only(bool read_only) {
    std::lock_guard<std::mutex> lock(mutex_);
    read_only_ = read_only;
}

bool EditorState::is_read_only() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return read_only_;
}

// Private methods

void EditorState::emit_event(const EditorEvent& event) {
    // Note: mutex_ is already held by the caller
    for (const auto& listener : event_listeners_) {
        if (listener) {
            listener(event);
        }
    }
}

}  // namespace jcode
