#include "piece_tree.h"
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>
#include <cstring>
#include <algorithm>

namespace jcode {

// --- Snapshot ---

Snapshot::~Snapshot() {
    freeTree(root_);
}

void Snapshot::decRef() {
    if (ref_count_.fetch_sub(1, std::memory_order_acq_rel) == 1) {
        delete this;
    }
}

size_t Snapshot::byteLength() const {
    return root_ ? root_->subtree_length : 0;
}

size_t Snapshot::lineCount() const {
    return root_ ? root_->subtree_lines + 1 : 1;
}

void Snapshot::readRange(size_t start, size_t end, uint8_t* out) const {
    if (!root_ || start >= end) return;

    size_t offset = 0;
    size_t out_pos = 0;

    std::function<void(Node*)> traverse = [&](Node* node) {
        if (!node || offset >= end) return;

        // Traverse left subtree
        if (node->left && offset + node->left->subtree_length > start) {
            traverse(node->left);
        }

        // Process this piece
        size_t piece_end = offset + node->piece.length;
        if (piece_end > start && offset < end) {
            size_t copy_start = std::max(start, offset) - offset;
            size_t copy_end = std::min(end, piece_end) - offset;
            size_t copy_len = copy_end - copy_start;

            const SourceBuffer& src = (*sources_)[node->piece.source_index];
            std::memcpy(out + out_pos, src.data + node->piece.start + copy_start, copy_len);
            out_pos += copy_len;
        }
        offset = piece_end;

        // Traverse right subtree
        if (node->right && offset < end) {
            traverse(node->right);
        }
    };

    traverse(root_);
}

std::pair<size_t, size_t> Snapshot::offsetToLineColumn(size_t offset) const {
    if (!root_) return {0, 0};

    size_t current_offset = 0;
    size_t current_line = 0;
    size_t column = 0;

    std::function<void(Node*)> traverse = [&](Node* node) {
        if (!node) return;

        // Traverse left
        if (node->left && current_offset + node->left->subtree_length <= offset) {
            current_offset += node->left->subtree_length;
            current_line += node->left->subtree_lines;
        } else if (node->left) {
            traverse(node->left);
            return;
        }

        // Check this piece
        if (current_offset + node->piece.length > offset) {
            // Offset is in this piece
            size_t local_offset = offset - current_offset;
            const SourceBuffer& src = (*sources_)[node->piece.source_index];
            const uint8_t* piece_data = src.data + node->piece.start;

            // Count newlines before offset in this piece
            for (size_t i = 0; i < local_offset; ++i) {
                if (piece_data[i] == '\n') {
                    current_line++;
                    column = 0;
                } else {
                    column++;
                }
            }
            return;
        }

        // Count newlines in this piece
        const SourceBuffer& src = (*sources_)[node->piece.source_index];
        const uint8_t* piece_data = src.data + node->piece.start;
        for (size_t i = 0; i < node->piece.length; ++i) {
            if (piece_data[i] == '\n') current_line++;
        }
        current_offset += node->piece.length;

        // Traverse right
        if (node->right) {
            traverse(node->right);
        }
    };

    traverse(root_);
    return {current_line, column};
}

size_t Snapshot::lineColumnToOffset(size_t line, size_t column) const {
    if (!root_) return 0;

    size_t current_line = 0;
    size_t current_offset = 0;

    std::function<bool(Node*)> traverse = [&](Node* node) -> bool {
        if (!node) return false;

        // Check left subtree
        if (node->left) {
            if (current_line + node->left->subtree_lines >= line) {
                if (traverse(node->left)) return true;
            } else {
                current_line += node->left->subtree_lines;
                current_offset += node->left->subtree_length;
            }
        }

        // Check this piece
        const SourceBuffer& src = (*sources_)[node->piece.source_index];
        const uint8_t* piece_data = src.data + node->piece.start;

        for (size_t i = 0; i < node->piece.length; ++i) {
            if (current_line == line && column == 0) {
                return true;
            }
            if (piece_data[i] == '\n') {
                current_line++;
                if (current_line > line) return true;
            } else if (current_line == line) {
                column--;
            }
            current_offset++;
        }

        // Check right subtree
        if (node->right) {
            return traverse(node->right);
        }

        return false;
    };

    traverse(root_);
    return current_offset;
}

std::pair<size_t, size_t> Snapshot::lineAt(size_t line) const {
    size_t start = lineColumnToOffset(line, 0);
    size_t end = (line + 1 < lineCount()) ? lineColumnToOffset(line + 1, 0) : byteLength();
    return {start, end};
}

void Snapshot::freeTree(Node* node) {
    if (!node) return;
    freeTree(node->left);
    freeTree(node->right);
    delete node;
}

// --- PieceTreeBuffer ---

PieceTreeBuffer::PieceTreeBuffer()
    : root_(nullptr), fd_(-1), mmap_addr_(nullptr), mmap_length_(0) {
    sources_shared_ = std::make_shared<std::vector<SourceBuffer>>();
}

PieceTreeBuffer::~PieceTreeBuffer() {
    close();
}

PieceTreeBuffer* PieceTreeBuffer::openFromFd(int fd) {
    struct stat st;
    if (fstat(fd, &st) != 0) return nullptr;

    void* addr = mmap(nullptr, st.st_size, PROT_READ, MAP_PRIVATE, fd, 0);
    if (addr == MAP_FAILED) return nullptr;

    auto* buffer = new PieceTreeBuffer();
    buffer->fd_ = fd;
    buffer->mmap_addr_ = addr;
    buffer->mmap_length_ = st.st_size;

    size_t idx = buffer->addSourceBuffer(static_cast<uint8_t*>(addr), st.st_size, true);
    Piece piece{idx, 0, static_cast<size_t>(st.st_size), buffer->countLines(static_cast<uint8_t*>(addr), st.st_size), 0};
    buffer->root_ = new Node(piece);
    buffer->root_->is_red = false;

    return buffer;
}

PieceTreeBuffer* PieceTreeBuffer::openFromBytes(const uint8_t* data, size_t length) {
    auto* buffer = new PieceTreeBuffer();

    // Copy data to heap
    uint8_t* copy = new uint8_t[length];
    std::memcpy(copy, data, length);

    size_t idx = buffer->addSourceBuffer(copy, length, false);
    Piece piece{idx, 0, length, buffer->countLines(copy, length), 0};
    buffer->root_ = new Node(piece);
    buffer->root_->is_red = false;

    return buffer;
}

Snapshot* PieceTreeBuffer::snapshot() {
    if (!root_) {
        return new Snapshot(nullptr, sources_shared_);
    }

    // Deep copy the tree for snapshot
    std::function<Node*(Node*)> copyTree = [&](Node* node) -> Node* {
        if (!node) return nullptr;
        Node* copy = new Node(node->piece);
        copy->is_red = node->is_red;
        copy->subtree_length = node->subtree_length;
        copy->subtree_lines = node->subtree_lines;
        copy->left = copyTree(node->left);
        copy->right = copyTree(node->right);
        if (copy->left) copy->left->parent = copy;
        if (copy->right) copy->right->parent = copy;
        return copy;
    };

    Node* snapshot_root = copyTree(root_);
    return new Snapshot(snapshot_root, sources_shared_);
}

Snapshot* PieceTreeBuffer::applyEdits(const std::vector<EditOp>& ops) {
    for (const auto& op : ops) {
        if (op.type == EditOp::INSERT) {
            root_ = insert(root_, op.offset, op.data.data(), op.data.size());
        } else if (op.type == EditOp::DELETE) {
            root_ = remove(root_, op.offset, op.offset + op.length);
        }
    }
    return snapshot();
}

void PieceTreeBuffer::close() {
    if (mmap_addr_) {
        munmap(mmap_addr_, mmap_length_);
        mmap_addr_ = nullptr;
    }
    if (fd_ >= 0) {
        ::close(fd_);
        fd_ = -1;
    }

    // Free heap-allocated source buffers
    for (auto& src : sources_) {
        if (!src.is_original) {
            delete[] src.data;
        }
    }
    sources_.clear();

    freeTree(root_);
    root_ = nullptr;
}

Node* PieceTreeBuffer::insert(Node* root, size_t offset, const uint8_t* data, size_t length) {
    if (length == 0) return root;

    // Add new source buffer
    uint8_t* copy = new uint8_t[length];
    std::memcpy(copy, data, length);
    size_t src_idx = addSourceBuffer(copy, length, false);

    Piece new_piece{src_idx, 0, length, countLines(data, length), findLineStart(data, length)};
    Node* new_node = new Node(new_piece);

    if (!root) {
        new_node->is_red = false;
        return new_node;
    }

    // Find insertion point and split if necessary
    Node* current = root;
    size_t current_offset = 0;

    while (current) {
        size_t left_len = current->left ? current->left->subtree_length : 0;

        if (offset <= current_offset + left_len) {
            if (current->left) {
                current = current->left;
            } else {
                current->left = new_node;
                new_node->parent = current;
                break;
            }
        } else if (offset <= current_offset + left_len + current->piece.length) {
            // Split this piece
            Node* left_piece = nullptr;
            Node* right_piece = nullptr;
            splitPiece(current, offset - (current_offset + left_len), left_piece, right_piece);

            // Insert new node between left and right
            new_node->parent = current;
            if (left_piece) {
                current->left = left_piece;
                left_piece->parent = current;
            }
            if (right_piece) {
                current->right = right_piece;
                right_piece->parent = current;
            }
            break;
        } else {
            current_offset += left_len + current->piece.length;
            if (current->right) {
                current = current->right;
            } else {
                current->right = new_node;
                new_node->parent = current;
                break;
            }
        }
    }

    updateSubtree(current);
    return insertFixup(root, new_node);
}

Node* PieceTreeBuffer::remove(Node* root, size_t start, size_t end) {
    // Simplified: mark pieces as deleted by setting length to 0
    // A full implementation would rebalance and remove nodes
    if (!root || start >= end) return root;

    std::function<void(Node*, size_t&)> traverse = [&](Node* node, size_t& offset) {
        if (!node) return;

        traverse(node->left, offset);

        size_t piece_end = offset + node->piece.length;
        if (piece_end > start && offset < end) {
            // This piece overlaps with delete range
            size_t delete_start = std::max(start, offset) - offset;
            size_t delete_end = std::min(end, piece_end) - offset;

            if (delete_start == 0 && delete_end == node->piece.length) {
                // Delete entire piece
                node->piece.length = 0;
                node->piece.line_count = 0;
            } else {
                // Partial delete - adjust piece
                node->piece.start += delete_start;
                node->piece.length -= (delete_end - delete_start);
                // Recount lines
                const SourceBuffer& src = sources_[node->piece.source_index];
                node->piece.line_count = countLines(src.data + node->piece.start, node->piece.length);
            }
        }
        offset = piece_end;

        traverse(node->right, offset);
    };

    size_t offset = 0;
    traverse(root, offset);

    // Update all subtree metadata
    std::function<void(Node*)> update = [&](Node* node) {
        if (!node) return;
        update(node->left);
        update(node->right);
        updateSubtree(node);
    };
    update(root);

    return root;
}

void PieceTreeBuffer::splitPiece(Node* node, size_t offset, Node*& left, Node*& right) {
    if (offset == 0 || offset >= node->piece.length) {
        left = nullptr;
        right = nullptr;
        return;
    }

    const SourceBuffer& src = sources_[node->piece.source_index];
    const uint8_t* piece_data = src.data + node->piece.start;

    // Left piece
    Piece left_piece{node->piece.source_index, node->piece.start, offset,
                     countLines(piece_data, offset), findLineStart(piece_data, offset)};
    left = new Node(left_piece);

    // Right piece
    Piece right_piece{node->piece.source_index, node->piece.start + offset,
                      node->piece.length - offset,
                      countLines(piece_data + offset, node->piece.length - offset),
                      findLineStart(piece_data + offset, node->piece.length - offset)};
    right = new Node(right_piece);
}

size_t PieceTreeBuffer::countLines(const uint8_t* data, size_t length) {
    size_t count = 0;
    for (size_t i = 0; i < length; ++i) {
        if (data[i] == '\n') count++;
    }
    return count;
}

size_t PieceTreeBuffer::findLineStart(const uint8_t* data, size_t length) {
    for (size_t i = 0; i < length; ++i) {
        if (data[i] == '\n') return i + 1;
    }
    return 0;
}

size_t PieceTreeBuffer::addSourceBuffer(const uint8_t* data, size_t length, bool is_original) {
    sources_.push_back({data, length, is_original});
    sources_shared_->push_back({data, length, is_original});
    return sources_.size() - 1;
}

void PieceTreeBuffer::freeTree(Node* node) {
    if (!node) return;
    freeTree(node->left);
    freeTree(node->right);
    delete node;
}

// Red-black tree operations (simplified stubs for now)
Node* PieceTreeBuffer::insertFixup(Node* root, Node* node) {
    // Simplified: just ensure root is black
    if (root) root->is_red = false;
    return root;
}

Node* PieceTreeBuffer::deleteFixup(Node* root, Node* node, Node* parent) {
    return root;
}

void PieceTreeBuffer::rotateLeft(Node* node) {}
void PieceTreeBuffer::rotateRight(Node* node) {}
void PieceTreeBuffer::transplant(Node* u, Node* v) {}
Node* PieceTreeBuffer::minimum(Node* node) {
    while (node && node->left) node = node->left;
    return node;
}

void PieceTreeBuffer::updateSubtree(Node* node) {
    if (!node) return;
    node->subtree_length = node->piece.length;
    node->subtree_lines = node->piece.line_count;
    if (node->left) {
        node->subtree_length += node->left->subtree_length;
        node->subtree_lines += node->left->subtree_lines;
    }
    if (node->right) {
        node->subtree_length += node->right->subtree_length;
        node->subtree_lines += node->right->subtree_lines;
    }
}

}  // namespace jcode
