#pragma once

#include <cstdint>
#include <memory>
#include <string>
#include <vector>
#include <atomic>
#include <functional>

namespace jcode {

/**
 * Source buffer: either the original (mmap'd) or added (append-only).
 */
struct SourceBuffer {
    const uint8_t* data;
    size_t length;
    bool is_original;  // true if mmap'd, false if heap-allocated
};

/**
 * A piece in the piece tree: a view into a source buffer.
 */
struct Piece {
    size_t source_index;  // index into Buffer::sources_
    size_t start;         // byte offset in source
    size_t length;        // byte length
    size_t line_count;    // number of \n in this piece
    size_t line_start;    // byte offset of first line start in piece (relative to piece start)
};

/**
 * Red-black tree node containing a piece.
 */
struct Node {
    Piece piece;
    bool is_red;
    Node* left;
    Node* right;
    Node* parent;

    // Subtree metadata for O(log n) lookups
    size_t subtree_length;  // total byte length in subtree
    size_t subtree_lines;   // total line count in subtree

    Node(Piece p)
        : piece(p), is_red(true), left(nullptr), right(nullptr), parent(nullptr),
          subtree_length(p.length), subtree_lines(p.line_count) {}
};

/**
 * Snapshot: a ref-counted root pointer for lock-free reads.
 */
class Snapshot {
public:
    Snapshot(Node* root, std::shared_ptr<std::vector<SourceBuffer>> sources)
        : root_(root), sources_(sources), ref_count_(1) {}

    ~Snapshot();

    void incRef() { ref_count_.fetch_add(1, std::memory_order_relaxed); }
    void decRef();

    Node* root() const { return root_; }
    const std::vector<SourceBuffer>& sources() const { return *sources_; }

    size_t byteLength() const;
    size_t lineCount() const;

    // Read bytes [start, end) into output buffer
    void readRange(size_t start, size_t end, uint8_t* out) const;

    // Convert byte offset to (line, column)
    std::pair<size_t, size_t> offsetToLineColumn(size_t offset) const;

    // Convert (line, column) to byte offset
    size_t lineColumnToOffset(size_t line, size_t column) const;

    // Get byte range for a line (returns [start, end))
    std::pair<size_t, size_t> lineAt(size_t line) const;

private:
    void freeTree(Node* node);
    Node* root_;
    std::shared_ptr<std::vector<SourceBuffer>> sources_;
    std::atomic<int> ref_count_;
};

/**
 * Edit operation: insert or delete bytes.
 */
struct EditOp {
    enum Type { INSERT, DELETE };
    Type type;
    size_t offset;        // byte offset for insert/delete start
    size_t length;        // bytes to delete (DELETE only)
    std::vector<uint8_t> data;  // bytes to insert (INSERT only)
};

/**
 * Piece tree text buffer.
 * Thread-safe for single writer, multiple readers via RCU snapshots.
 */
class PieceTreeBuffer {
public:
    PieceTreeBuffer();
    ~PieceTreeBuffer();

    // Open from file descriptor (mmap) or byte array
    static PieceTreeBuffer* openFromFd(int fd);
    static PieceTreeBuffer* openFromBytes(const uint8_t* data, size_t length);

    // Create a snapshot (caller must decRef when done)
    Snapshot* snapshot();

    // Apply edits and return new snapshot
    Snapshot* applyEdits(const std::vector<EditOp>& ops);

    // Close and free all resources
    void close();

private:
    // Piece tree operations
    Node* insert(Node* root, size_t offset, const uint8_t* data, size_t length);
    Node* remove(Node* root, size_t start, size_t end);
    void splitPiece(Node* node, size_t offset, Node*& left, Node*& right);

    // Red-black tree balancing
    Node* insertFixup(Node* root, Node* node);
    Node* deleteFixup(Node* root, Node* node, Node* parent);
    void rotateLeft(Node* node);
    void rotateRight(Node* node);
    void transplant(Node* u, Node* v);
    Node* minimum(Node* node);
    void updateSubtree(Node* node);

    // Line counting
    size_t countLines(const uint8_t* data, size_t length);
    size_t findLineStart(const uint8_t* data, size_t length);

    // Memory management
    void freeTree(Node* node);
    size_t addSourceBuffer(const uint8_t* data, size_t length, bool is_original);

    std::vector<SourceBuffer> sources_;
    std::shared_ptr<std::vector<SourceBuffer>> sources_shared_;
    Node* root_;
    int fd_;
    void* mmap_addr_;
    size_t mmap_length_;
};

}  // namespace jcode
