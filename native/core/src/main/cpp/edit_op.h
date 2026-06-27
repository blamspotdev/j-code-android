#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace jcode {

/**
 * Edit operation: insert or delete bytes.
 * This is the common definition used by both buffer and editor modules.
 */
struct EditOp {
    enum Type { INSERT = 0, DELETE = 1 };
    Type type;
    int32_t offset;        // byte offset for insert/delete start
    int32_t length;        // bytes to delete (DELETE only)
    std::string data;      // text to insert (INSERT only, UTF-8)

    EditOp() : type(INSERT), offset(0), length(0) {}
    EditOp(Type t, int32_t off, int32_t len = 0, const std::string& d = "")
        : type(t), offset(off), length(len), data(d) {}
};

}  // namespace jcode
