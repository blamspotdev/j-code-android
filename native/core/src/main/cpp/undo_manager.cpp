#include "undo_manager.h"
#include "editor_state.h"
#include <algorithm>
#include <chrono>

namespace jcode {

UndoManager::UndoManager(int32_t max_groups, int32_t max_inverted_bytes)
    : max_groups_(max_groups),
      max_inverted_bytes_(max_inverted_bytes),
      total_inverted_bytes_(0),
      current_group_start_(0),
      is_composing_(false) {}

UndoManager::~UndoManager() = default;

void UndoManager::record_edit(const std::vector<EditOp>& ops,
                               const std::vector<Caret>& selection_before,
                               const std::string& deleted_text) {
    auto inverted = invert_ops(ops, deleted_text);
    auto now = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::system_clock::now().time_since_epoch()).count();

    // Decide whether to start a new group
    bool should_new_group = false;
    
    if (is_composing_) {
        should_new_group = false;  // composing stays in same group
    } else if (history_.empty()) {
        should_new_group = true;
    } else if (now - current_group_start_ > 500) {
        should_new_group = true;
    } else if (!current_group_selection_.empty() &&
               selection_moved(current_group_selection_, selection_before)) {
        should_new_group = true;
    }

    if (should_new_group) {
        flush_group();
        current_group_start_ = now;
    }
    current_group_selection_ = selection_before;

    UndoEntry entry;
    entry.inverted_ops = inverted;
    entry.selection_before = selection_before;
    entry.selection_after = selection_before;
    entry.timestamp = now;

    history_.push_back(entry);
    total_inverted_bytes_ += estimate_byte_size(inverted);
    redo_stack_.clear();

    evict_oldest_if_needed();
}

void UndoManager::begin_composing() {
    is_composing_ = true;
    flush_group();
    auto now = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::system_clock::now().time_since_epoch()).count();
    current_group_start_ = now;
}

void UndoManager::end_composing() {
    is_composing_ = false;
    flush_group();
}

void UndoManager::flush_group() {
    current_group_start_ = 0;
    current_group_selection_.clear();
}

std::vector<EditOp> UndoManager::undo() {
    if (history_.empty()) return {};
    
    flush_group();

    auto entry = history_.back();
    history_.pop_back();
    total_inverted_bytes_ -= estimate_byte_size(entry.inverted_ops);

    // Prepare for redo
    UndoEntry redo_entry = entry;
    redo_entry.selection_after = entry.selection_before;
    redo_stack_.push_back(redo_entry);

    return entry.inverted_ops;
}

std::vector<Caret> UndoManager::get_undo_selection() const {
    if (history_.empty()) return {};
    return history_.back().selection_before;
}

std::vector<EditOp> UndoManager::redo() {
    if (redo_stack_.empty()) return {};

    auto entry = redo_stack_.back();
    redo_stack_.pop_back();

    // Invert the inverted ops to get the original ops
    std::vector<EditOp> original_ops;
    for (const auto& op : entry.inverted_ops) {
        if (op.type == EditOp::INSERT) {
            // Inverted insert -> delete
            original_ops.emplace_back(EditOp::DELETE, op.offset, op.data.size());
        } else {
            // Inverted delete -> insert
            original_ops.emplace_back(EditOp::INSERT, op.offset, 0, op.data);
        }
    }

    // Add back to history
    UndoEntry history_entry = entry;
    history_entry.inverted_ops = invert_ops(original_ops, "");
    history_.push_back(history_entry);
    total_inverted_bytes_ += estimate_byte_size(history_entry.inverted_ops);

    return original_ops;
}

std::vector<Caret> UndoManager::get_redo_selection() const {
    if (redo_stack_.empty()) return {};
    return redo_stack_.back().selection_after;
}

void UndoManager::clear() {
    history_.clear();
    redo_stack_.clear();
    total_inverted_bytes_ = 0;
    flush_group();
}

bool UndoManager::can_undo() const {
    return !history_.empty();
}

bool UndoManager::can_redo() const {
    return !redo_stack_.empty();
}

std::vector<EditOp> UndoManager::invert_ops(const std::vector<EditOp>& ops,
                                             const std::string& deleted_text) {
    std::vector<EditOp> inverted;
    
    // Invert ops in reverse order
    for (auto it = ops.rbegin(); it != ops.rend(); ++it) {
        const auto& op = *it;
        if (op.type == EditOp::INSERT) {
            // Insert -> Delete
            int32_t end = op.offset + static_cast<int32_t>(op.data.size());
            inverted.emplace_back(EditOp::DELETE, op.offset, end - op.offset);
        } else {
            // Delete -> Insert (need the deleted text)
            inverted.emplace_back(EditOp::INSERT, op.offset, 0, deleted_text);
        }
    }
    
    return inverted;
}

bool UndoManager::selection_moved(const std::vector<Caret>& before,
                                   const std::vector<Caret>& after) {
    if (before.size() != after.size()) return true;
    
    for (size_t i = 0; i < before.size(); ++i) {
        if (before[i].head != after[i].head) return true;
    }
    
    return false;
}

int32_t UndoManager::estimate_byte_size(const std::vector<EditOp>& ops) {
    int32_t total = 0;
    for (const auto& op : ops) {
        if (op.type == EditOp::INSERT) {
            total += static_cast<int32_t>(op.data.size());
        } else {
            total += op.length;
        }
    }
    return total;
}

void UndoManager::evict_oldest_if_needed() {
    while (static_cast<int32_t>(history_.size()) > max_groups_ ||
           total_inverted_bytes_ > max_inverted_bytes_) {
        if (history_.empty()) break;
        
        auto removed = history_.front();
        history_.pop_front();
        total_inverted_bytes_ -= estimate_byte_size(removed.inverted_ops);
    }
}

}  // namespace jcode
