#pragma once

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <memory>
#include <utility>
#include <vector>

namespace jcode {

/**
 * Piece-table text buffer, the native mirror of core/buffer PieceTable.kt — it must reproduce
 * that implementation's semantics exactly (all offsets are UTF-8 bytes; "column" is bytes since
 * line start; ranges are end-exclusive and coerced into [0, length]).
 *
 * Content is a sequence of pieces referencing two byte stores: the immutable original the buffer
 * was opened with, and an append-only add store receiving every insertion. Edits only split/trim
 * the piece list; newline positions are indexed once per store, so line/offset conversions are
 * binary searches, never byte scans.
 *
 * Concurrency contract (single writer, lock-free readers): a byte or newline entry once written
 * is never moved or changed. The add store is fixed-size chunks (no reallocation), the newline
 * index grows copy-on-write, and a Snapshot captures shared ownership plus the lengths at capture
 * time — later appends only touch bytes/entries beyond what any existing snapshot reads. Buffer
 * close/destruction cannot dangle a live Snapshot: everything is shared_ptr-owned.
 */

/** Append-only byte storage addressed by a global offset; bytes never move once written. */
class AddBuffer {
public:
    static constexpr size_t kChunkSize = 64 * 1024;
    struct Chunk {
        uint8_t bytes[kChunkSize];
    };
    using ChunkList = std::vector<std::shared_ptr<Chunk>>;

    size_t length() const { return len_; }

    /** Appends n bytes, returns the global offset of the first appended byte. */
    size_t append(const uint8_t* data, size_t n);

    /** Copies the global range [start, start + n) into out; the range may span chunks. */
    static void copyOut(const ChunkList& chunks, size_t start, size_t n, uint8_t* out);

    ChunkList shareChunks() const { return chunks_; }

private:
    ChunkList chunks_;
    size_t len_ = 0;
};

/**
 * Growable sorted index of newline byte offsets. Growth is copy-on-write (a snapshot holding the
 * old array keeps reading its captured prefix); in-place appends only write entries beyond any
 * snapshot's captured count.
 */
class NewlineIndex {
public:
    void push(size_t offset);
    size_t count() const { return count_; }
    const size_t* data() const { return arr_ ? arr_->data() : nullptr; }
    std::shared_ptr<std::vector<size_t>> share() const { return arr_; }

private:
    std::shared_ptr<std::vector<size_t>> arr_;
    size_t count_ = 0;
};

/** The original content the buffer was opened with (heap copy or mmap), shared with snapshots. */
struct OriginalSource {
    std::shared_ptr<void> owner;
    const uint8_t* data = nullptr;
    size_t length = 0;
};

/** A run of [length] bytes at [start] in either the original store or the add store. */
struct Piece {
    bool from_add;
    size_t start;
    size_t length;
    /** Index into the owning store's newline array of the first newline at/after start. */
    size_t newline_from;
    /** Newlines inside [start, start + length). */
    size_t newline_count;
};

/** Edit operation: insert or delete bytes. */
struct EditOp {
    enum Type { INSERT, DELETE };
    Type type;
    size_t offset;              // byte offset for insert / delete start
    size_t length;              // bytes to delete (DELETE only)
    std::vector<uint8_t> data;  // bytes to insert (INSERT only)
};

/**
 * Immutable view of the buffer at a point in time. Ref-counted for the JNI layer (the Kotlin
 * Snapshot's Cleaner calls decRef exactly once). Reads are binary searches over piece prefix
 * sums — O(log pieces) + copy.
 */
class Snapshot {
public:
    void incRef() { ref_count_.fetch_add(1, std::memory_order_relaxed); }
    void decRef();

    size_t byteLength() const { return length_; }
    size_t lineCount() const { return newline_total_ + 1; }

    /** Copies [start, end) (coerced into [0, length]) into out, at most cap bytes; returns bytes written. */
    size_t readRange(int64_t start, int64_t end, uint8_t* out, size_t cap) const;

    /** Byte offset (coerced) -> 0-based (line, byte column). */
    std::pair<size_t, size_t> offsetToLineColumn(int64_t offset) const;

    /** 0-based (line, byte column) -> byte offset, clamped to [0, length]. */
    size_t lineColumnToOffset(int64_t line, int64_t column) const;

    /** Byte range [start, end) of a 0-based line, excluding the newline. Past-end lines -> (length, length). */
    std::pair<size_t, size_t> lineAt(int64_t line) const;

private:
    friend class PieceTreeBuffer;
    Snapshot() = default;

    /** Global byte offset of the k-th newline (k in 1..newline_total_). */
    size_t newlineGlobalOffset(size_t k) const;
    const size_t* newlineArray(const Piece& p) const;
    /** Newlines in [0, off); off must be <= length_. */
    size_t linesBeforeOffset(size_t off) const;

    OriginalSource original_;
    std::shared_ptr<const std::vector<size_t>> original_newlines_;
    AddBuffer::ChunkList chunks_;
    std::shared_ptr<std::vector<size_t>> add_newlines_owner_;
    const size_t* add_newlines_ = nullptr;
    size_t add_newline_count_ = 0;

    std::vector<Piece> pieces_;
    std::vector<size_t> byte_start_;  // size pieces+1; byte_start_[i] = global offset of piece i, sentinel = length_
    std::vector<size_t> nl_before_;   // size pieces+1; newlines before piece i, sentinel = newline_total_
    size_t length_ = 0;
    size_t newline_total_ = 0;
    std::atomic<int> ref_count_{1};
};

/** The live, single-writer buffer. */
class PieceTreeBuffer {
public:
    static PieceTreeBuffer* openFromBytes(const uint8_t* data, size_t length);
    static PieceTreeBuffer* openFromFd(int fd);

    size_t byteLength() const { return length_; }
    size_t lineCount() const { return newline_total_ + 1; }

    /** New immutable snapshot with ref-count 1 (caller decRefs when done). */
    Snapshot* snapshot() const;

    /** Applies ops in order, returns a fresh snapshot of the result. */
    Snapshot* applyEdits(const std::vector<EditOp>& ops);

    /** Releases the live table's shares early; kept for JNI symmetry (snapshots stay valid). */
    void close();

private:
    PieceTreeBuffer() = default;
    void initFromOriginal(OriginalSource original);

    void insert(int64_t offset, const uint8_t* data, size_t n);
    void erase(int64_t start_offset, int64_t end_offset);
    Piece makePiece(bool from_add, size_t start, size_t len) const;
    size_t newlinesInPieceRange(const Piece& p, size_t from_local, size_t to_local) const;

    OriginalSource original_;
    std::shared_ptr<const std::vector<size_t>> original_newlines_;
    AddBuffer add_;
    NewlineIndex add_newlines_;
    std::vector<Piece> pieces_;
    size_t length_ = 0;
    size_t newline_total_ = 0;
};

}  // namespace jcode
