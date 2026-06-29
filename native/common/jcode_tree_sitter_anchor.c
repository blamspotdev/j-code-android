#include <tree_sitter/api.h>

void* jcode_tree_sitter_anchor(void) {
    return ts_parser_new();
}
