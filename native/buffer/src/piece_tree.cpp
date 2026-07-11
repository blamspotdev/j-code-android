#include "piece_tree.h"

#include <algorithm>
#include <cstring>

#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

namespace jcode {

namespace {

constexpr uint8_t kNewline = '\n';

/** First index i in [0, n) with arr[i] >= key, else n. */
size_t lowerBound(const size_t* arr, size_t n, size_t key) {
    const size_t* end = arr + n;
    return static_cast<size_t>(std::lower_bound(arr, end, key) - arr);
}

std::shared_ptr<const std::vector<size_t>> scanNewlines(const uint8_t* data, size_t n) {
    auto out = std::make_shared<std::vector<size_t>>();
    const uint8_t* p = data;
    while ((p = static_cast<const uint8_t*>(memchr(p, kNewline, n - static_cast<size_t>(p - data)))) != nullptr) {
        out->push_back(static_cast<size_t>(p - data));
        ++p;
    }
    return out;
}

size_t coerce(int64_t v, size_t hi) {
    if (v < 0) return 0;
    const size_t uv = static_cast<size_t>(v);
    return uv > hi ? hi : uv;
}

}  // namespace

// ---------------------------------------------------------------------------
// AddBuffer

size_t AddBuffer::append(const uint8_t* data, size_t n) {
    const size_t start = len_;
    size_t written = 0;
    while (written < n) {
        const size_t chunk_index = (len_ + written) / kChunkSize;
        const size_t chunk_offset = (len_ + written) % kChunkSize;
        if (chunk_index == chunks_.size()) {
            chunks_.push_back(std::make_shared<Chunk>());
        }
        const size_t room = kChunkSize - chunk_offset;
        const size_t take = std::min(room, n - written);
        memcpy(chunks_[chunk_index]->bytes + chunk_offset, data + written, take);
        written += take;
    }
    len_ += n;
    return start;
}

void AddBuffer::copyOut(const ChunkList& chunks, size_t start, size_t n, uint8_t* out) {
    size_t copied = 0;
    while (copied < n) {
        const size_t off = start + copied;
        const size_t chunk_index = off / kChunkSize;
        const size_t chunk_offset = off % kChunkSize;
        const size_t take = std::min(kChunkSize - chunk_offset, n - copied);
        memcpy(out + copied, chunks[chunk_index]->bytes + chunk_offset, take);
        copied += take;
    }
}

// ---------------------------------------------------------------------------
// NewlineIndex

void NewlineIndex::push(size_t offset) {
    if (!arr_ || count_ == arr_->size()) {
        auto bigger = std::make_shared<std::vector<size_t>>(std::max<size_t>(16, count_ * 2));
        if (arr_) std::copy(arr_->begin(), arr_->begin() + static_cast<long>(count_), bigger->begin());
        arr_ = std::move(bigger);
    }
    (*arr_)[count_++] = offset;
}

// ---------------------------------------------------------------------------
// PieceTreeBuffer

PieceTreeBuffer* PieceTreeBuffer::openFromBytes(const uint8_t* data, size_t length) {
    OriginalSource original;
    if (length > 0) {
        auto copy = std::make_shared<std::vector<uint8_t>>(data, data + length);
        original.data = copy->data();
        original.length = length;
        original.owner = std::move(copy);
    }
    auto* buffer = new PieceTreeBuffer();
    buffer->initFromOriginal(std::move(original));
    return buffer;
}

PieceTreeBuffer* PieceTreeBuffer::openFromFd(int fd) {
    struct stat st;
    if (fstat(fd, &st) != 0 || st.st_size < 0) return nullptr;
    OriginalSource original;
    const size_t length = static_cast<size_t>(st.st_size);
    if (length > 0) {
        void* addr = mmap(nullptr, length, PROT_READ, MAP_PRIVATE, fd, 0);
        if (addr == MAP_FAILED) return nullptr;
        original.data = static_cast<const uint8_t*>(addr);
        original.length = length;
        original.owner = std::shared_ptr<void>(addr, [length](void* p) { munmap(p, length); });
    }
    auto* buffer = new PieceTreeBuffer();
    buffer->initFromOriginal(std::move(original));
    return buffer;
}

void PieceTreeBuffer::initFromOriginal(OriginalSource original) {
    original_ = std::move(original);
    original_newlines_ = original_.length > 0
        ? scanNewlines(original_.data, original_.length)
        : std::make_shared<const std::vector<size_t>>();
    if (original_.length > 0) {
        pieces_.push_back(Piece{false, 0, original_.length, 0, original_newlines_->size()});
        length_ = original_.length;
        newline_total_ = original_newlines_->size();
    }
}

void PieceTreeBuffer::close() {
    pieces_.clear();
    original_ = OriginalSource{};
    length_ = 0;
    newline_total_ = 0;
}

Piece PieceTreeBuffer::makePiece(bool from_add, size_t start, size_t len) const {
    const size_t* arr = from_add ? add_newlines_.data() : original_newlines_->data();
    const size_t n = from_add ? add_newlines_.count() : original_newlines_->size();
    const size_t from = lowerBound(arr, n, start);
    const size_t to = lowerBound(arr, n, start + len);
    return Piece{from_add, start, len, from, to - from};
}

size_t PieceTreeBuffer::newlinesInPieceRange(const Piece& p, size_t from_local, size_t to_local) const {
    const size_t* arr = p.from_add ? add_newlines_.data() : original_newlines_->data();
    const size_t n = p.from_add ? add_newlines_.count() : original_newlines_->size();
    return lowerBound(arr, n, p.start + to_local) - lowerBound(arr, n, p.start + from_local);
}

void PieceTreeBuffer::insert(int64_t offset, const uint8_t* data, size_t n) {
    if (n == 0) return;
    const size_t off = coerce(offset, length_);

    const size_t add_start = add_.append(data, n);
    size_t inserted_newlines = 0;
    for (size_t i = 0; i < n; ++i) {
        if (data[i] == kNewline) {
            add_newlines_.push(add_start + i);
            inserted_newlines++;
        }
    }

    size_t idx = 0;
    size_t acc = 0;
    while (idx < pieces_.size() && acc + pieces_[idx].length < off) {
        acc += pieces_[idx].length;
        idx++;
    }

    if (idx == pieces_.size()) {
        pieces_.push_back(makePiece(true, add_start, n));
    } else {
        const Piece p = pieces_[idx];
        const size_t local = off - acc;
        if (local == p.length) {
            // Insertion right after this piece. Typing fast path: a piece that already ends
            // exactly where the new bytes were appended just grows, keeping the piece count
            // flat during bursts of consecutive single-character inserts.
            if (p.from_add && p.start + p.length == add_start) {
                pieces_[idx] = makePiece(true, p.start, p.length + n);
            } else {
                pieces_.insert(pieces_.begin() + static_cast<long>(idx) + 1, makePiece(true, add_start, n));
            }
        } else if (local == 0) {
            pieces_.insert(pieces_.begin() + static_cast<long>(idx), makePiece(true, add_start, n));
        } else {
            pieces_[idx] = makePiece(p.from_add, p.start, local);
            pieces_.insert(pieces_.begin() + static_cast<long>(idx) + 1, makePiece(true, add_start, n));
            pieces_.insert(pieces_.begin() + static_cast<long>(idx) + 2,
                           makePiece(p.from_add, p.start + local, p.length - local));
        }
    }

    length_ += n;
    newline_total_ += inserted_newlines;
}

void PieceTreeBuffer::erase(int64_t start_offset, int64_t end_offset) {
    const size_t s = coerce(start_offset, length_);
    const size_t e = coerce(end_offset, length_);
    if (s >= e) return;

    size_t idx = 0;
    size_t acc = 0;
    while (idx < pieces_.size() && acc + pieces_[idx].length <= s) {
        acc += pieces_[idx].length;
        idx++;
    }

    size_t remaining = e - s;
    size_t local_start = s - acc;
    size_t removed_newlines = 0;
    while (idx < pieces_.size() && remaining > 0) {
        const Piece p = pieces_[idx];
        const size_t del_len = std::min(p.length - local_start, remaining);
        removed_newlines += newlinesInPieceRange(p, local_start, local_start + del_len);
        const size_t left_len = local_start;
        const size_t right_len = p.length - local_start - del_len;
        if (left_len == 0 && right_len == 0) {
            pieces_.erase(pieces_.begin() + static_cast<long>(idx));
        } else if (left_len == 0) {
            pieces_[idx] = makePiece(p.from_add, p.start + del_len, right_len);
            idx++;
        } else if (right_len == 0) {
            pieces_[idx] = makePiece(p.from_add, p.start, left_len);
            idx++;
        } else {
            pieces_[idx] = makePiece(p.from_add, p.start, left_len);
            pieces_.insert(pieces_.begin() + static_cast<long>(idx) + 1,
                           makePiece(p.from_add, p.start + left_len + del_len, right_len));
            idx += 2;
        }
        remaining -= del_len;
        local_start = 0;
    }

    length_ -= (e - s);
    newline_total_ -= removed_newlines;
}

Snapshot* PieceTreeBuffer::snapshot() const {
    auto* s = new Snapshot();
    s->original_ = original_;
    s->original_newlines_ = original_newlines_;
    s->chunks_ = add_.shareChunks();
    s->add_newlines_owner_ = add_newlines_.share();
    s->add_newlines_ = add_newlines_.data();
    s->add_newline_count_ = add_newlines_.count();
    s->pieces_ = pieces_;
    s->length_ = length_;
    s->newline_total_ = newline_total_;

    s->byte_start_.reserve(pieces_.size() + 1);
    s->nl_before_.reserve(pieces_.size() + 1);
    size_t bytes = 0;
    size_t lines = 0;
    for (const Piece& p : pieces_) {
        s->byte_start_.push_back(bytes);
        s->nl_before_.push_back(lines);
        bytes += p.length;
        lines += p.newline_count;
    }
    s->byte_start_.push_back(bytes);
    s->nl_before_.push_back(lines);
    return s;
}

Snapshot* PieceTreeBuffer::applyEdits(const std::vector<EditOp>& ops) {
    for (const EditOp& op : ops) {
        if (op.type == EditOp::INSERT) {
            insert(static_cast<int64_t>(op.offset), op.data.data(), op.data.size());
        } else {
            erase(static_cast<int64_t>(op.offset), static_cast<int64_t>(op.offset + op.length));
        }
    }
    return snapshot();
}

// ---------------------------------------------------------------------------
// Snapshot

void Snapshot::decRef() {
    if (ref_count_.fetch_sub(1, std::memory_order_acq_rel) == 1) {
        delete this;
    }
}

const size_t* Snapshot::newlineArray(const Piece& p) const {
    return p.from_add ? add_newlines_ : original_newlines_->data();
}

size_t Snapshot::readRange(int64_t start, int64_t end, uint8_t* out, size_t cap) const {
    const size_t s = coerce(start, length_);
    const size_t e = coerce(end, length_);
    if (s >= e) return 0;
    const size_t n = std::min(e - s, cap);

    // Last piece with byte_start_ <= s (byte_start_ carries a length_ sentinel at the end).
    size_t idx = static_cast<size_t>(
        std::upper_bound(byte_start_.begin(), byte_start_.end() - 1, s) - byte_start_.begin()) - 1;
    size_t written = 0;
    while (written < n && idx < pieces_.size()) {
        const Piece& p = pieces_[idx];
        const size_t piece_begin = byte_start_[idx];
        const size_t from_local = s + written > piece_begin ? s + written - piece_begin : 0;
        const size_t take = std::min(p.length - from_local, n - written);
        if (p.from_add) {
            AddBuffer::copyOut(chunks_, p.start + from_local, take, out + written);
        } else {
            memcpy(out + written, original_.data + p.start + from_local, take);
        }
        written += take;
        idx++;
    }
    return written;
}

size_t Snapshot::linesBeforeOffset(size_t off) const {
    if (pieces_.empty() || off >= length_) return newline_total_;
    const size_t idx = static_cast<size_t>(
        std::upper_bound(byte_start_.begin(), byte_start_.end() - 1, off) - byte_start_.begin()) - 1;
    const Piece& p = pieces_[idx];
    const size_t local = off - byte_start_[idx];
    const size_t k = lowerBound(newlineArray(p), p.newline_from + p.newline_count, p.start + local) - p.newline_from;
    return nl_before_[idx] + k;
}

size_t Snapshot::newlineGlobalOffset(size_t k) const {
    if (k == 0 || k > newline_total_) return length_;
    // First piece whose cumulative newline count reaches k (nl_before_ carries a sentinel).
    const size_t idx = static_cast<size_t>(
        std::lower_bound(nl_before_.begin() + 1, nl_before_.end(), k) - (nl_before_.begin() + 1));
    const Piece& p = pieces_[idx];
    const size_t within = k - nl_before_[idx] - 1;
    return byte_start_[idx] + (newlineArray(p)[p.newline_from + within] - p.start);
}

std::pair<size_t, size_t> Snapshot::offsetToLineColumn(int64_t offset) const {
    const size_t off = coerce(offset, length_);
    const size_t line = linesBeforeOffset(off);
    if (line == 0) return {0, off};
    return {line, off - newlineGlobalOffset(line) - 1};
}

size_t Snapshot::lineColumnToOffset(int64_t line, int64_t column) const {
    size_t line_start;
    if (line <= 0) {
        line_start = 0;
    } else if (static_cast<size_t>(line) > newline_total_) {
        line_start = length_;
    } else {
        line_start = newlineGlobalOffset(static_cast<size_t>(line)) + 1;
    }
    const size_t col = column > 0 ? static_cast<size_t>(column) : 0;
    return std::min(line_start + col, length_);
}

std::pair<size_t, size_t> Snapshot::lineAt(int64_t line) const {
    if (line > 0 && static_cast<size_t>(line) > newline_total_) return {length_, length_};
    const size_t ln = line > 0 ? static_cast<size_t>(line) : 0;
    const size_t start = ln == 0 ? 0 : newlineGlobalOffset(ln) + 1;
    const size_t end = ln + 1 <= newline_total_ ? newlineGlobalOffset(ln + 1) : length_;
    return {start, end};
}

}  // namespace jcode
