#pragma once

#include <cstdint>
#include <vector>
#include <string>
#include <memory>
#include <deque>
#include "edit_op.h"

namespace jcode {

// Forward declarations
struct Caret;

/**
 * Undo entry representing an invertible edit operation.
 */
struct UndoEntry {
    std::vector<EditOp> inverted_ops;  // Inverted edit operations
    std::vector<Caret> selection_before;
    std::vector<Caret> selection_after;
    int64_t timestamp;  // Milliseconds since epoch

    UndoEntry() : timestamp(0) {}
};

/**
 * Linear-history undo manager.
 * Groups adjacent edits within:
 * - A single IME composing session
 * - A 500ms typing burst
 * - Until selection moves
 */
class UndoManager {
public:
    UndoManager(int32_t max_groups = 500, int32_t max_inverted_bytes = 50 * 1024 * 1024);
    ~UndoManager();

    /**
     * Record an edit for potential undo.
     * @param ops The edit operations that were applied
     * @param selection_before Carets before the edit
     * @param deleted_text Text that was deleted (for INSERT ops, this is empty)
     */
    void record_edit(const std::vector<EditOp>& ops,
                     const std::vector<Caret>& selection_before,
                     const std::string& deleted_text = "");

    /**
     * Mark the start of an IME composing session.
     */
    void begin_composing();

    /**
     * Mark the end of an IME composing session.
     */
    void end_composing();

    /**
     * Flush the current group boundary.
     */
    void flush_group();

    /**
     * Perform undo.
     * @return The inverted edit operations to apply, or empty if nothing to undo
     */
    std::vector<EditOp> undo();

    /**
     * Get the selection to restore after undo.
     */
    std::vector<Caret> get_undo_selection() const;

    /**
     * Perform redo.
     * @return The edit operations to apply, or empty if nothing to redo
     */
    std::vector<EditOp> redo();

    /**
     * Get the selection to restore after redo.
     */
    std::vector<Caret> get_redo_selection() const;

    /**
     * Clear all undo/redo history.
     */
    void clear();

    /**
     * Check if undo is available.
     */
    bool can_undo() const;

    /**
     * Check if redo is available.
     */
    bool can_redo() const;

private:
    /**
     * Invert an edit operation.
     */
    std::vector<EditOp> invert_ops(const std::vector<EditOp>& ops,
                                    const std::string& deleted_text);

    /**
     * Check if selection moved between two caret sets.
     */
    bool selection_moved(const std::vector<Caret>& before,
                          const std::vector<Caret>& after);

    /**
     * Estimate byte size of edit operations.
     */
    int32_t estimate_byte_size(const std::vector<EditOp>& ops);

    /**
     * Evict oldest entries if limits exceeded.
     */
    void evict_oldest_if_needed();

    std::deque<UndoEntry> history_;
    std::deque<UndoEntry> redo_stack_;
    int32_t max_groups_;
    int32_t max_inverted_bytes_;
    int64_t total_inverted_bytes_;

    int64_t current_group_start_;
    std::vector<Caret> current_group_selection_;
    bool is_composing_;
};

}  // namespace jcode
