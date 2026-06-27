# Grammar compilation CMakeLists.txt
# Each grammar is built from upstream tree-sitter-<lang> repository

cmake_minimum_required(VERSION 3.22.1)
project(jcode_grammars C)

include(FetchContent)

# Function to add a tree-sitter grammar
function(add_ts_grammar lang repo tag has_scanner)
    string(TOLOWER ${lang} lang_lower)

    FetchContent_Declare(
        tree_sitter_${lang_lower}
        GIT_REPOSITORY ${repo}
        GIT_TAG ${tag}
        GIT_SHALLOW TRUE
    )

    FetchContent_GetProperties(tree_sitter_${lang_lower})
    if(NOT tree_sitter_${lang_lower}_POPULATED)
        FetchContent_Populate(tree_sitter_${lang_lower})

        set(sources "${tree_sitter_${lang_lower}_SOURCE_DIR}/src/parser.c")
        if(has_scanner AND EXISTS "${tree_sitter_${lang_lower}_SOURCE_DIR}/src/scanner.c")
            list(APPEND sources "${tree_sitter_${lang_lower}_SOURCE_DIR}/src/scanner.c")
        endif()

        add_library(tree-sitter-${lang_lower} SHARED ${sources})

        target_include_directories(tree-sitter-${lang_lower} PRIVATE
            "${tree_sitter_${lang_lower}_SOURCE_DIR}/src"
        )

        target_compile_options(tree-sitter-${lang_lower} PRIVATE
            -fvisibility=hidden
            -O2
        )

        set_target_properties(tree-sitter-${lang_lower} PROPERTIES
            OUTPUT_NAME "tree-sitter-${lang_lower}"
            LIBRARY_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/grammars"
        )

        # Install to jniLibs
        install(TARGETS tree-sitter-${lang_lower}
            LIBRARY DESTINATION "${JCODE_JNI_OUTPUT_DIR}/${JCODE_VARIANT_DIR}/${ANDROID_ABI}"
        )
    endif()
endfunction()

# Build grammars based on GRAMMAR_LIST variable
# Default: build all supported grammars
if(NOT DEFINED GRAMMAR_LIST)
    set(GRAMMAR_LIST "c;javascript;python;kotlin;json;yaml;rust;cpp;java;typescript;html;css;markdown;csharp")
endif()

# C
if("c" IN_LIST GRAMMAR_LIST)
    add_ts_grammar(c
        "https://github.com/tree-sitter/tree-sitter-c.git"
        "v0.23.5"
        FALSE
    )
endif()

# JavaScript
if("javascript" IN_LIST GRAMMAR_LIST)
    add_ts_grammar(javascript
        "https://github.com/tree-sitter/tree-sitter-javascript.git"
        "v0.23.1"
        TRUE
    )
endif()

# Python
if("python" IN_LIST GRAMMAR_LIST)
    add_ts_grammar(python
        "https://github.com/tree-sitter/tree-sitter-python.git"
        "v0.23.6"
        TRUE
    )
endif()

# Kotlin
if("kotlin" IN_LIST GRAMMAR_LIST)
    add_ts_grammar(kotlin
        "https://github.com/fwcd/tree-sitter-kotlin.git"
        "0.3.9"
        TRUE
    )
endif()

# JSON
if("json" IN_LIST GRAMMAR_LIST)
    add_ts_grammar(json
        "https://github.com/tree-sitter/tree-sitter-json.git"
        "v0.24.8"
        FALSE
    )
endif()

# YAML
if("yaml" IN_LIST GRAMMAR_LIST)
    add_ts_grammar(yaml
        "https://github.com/ikatyang/tree-sitter-yaml.git"
        "v0.7.0"
        TRUE
    )
endif()

# Rust
if("rust" IN_LIST GRAMMAR_LIST)
    add_ts_grammar(rust
        "https://github.com/tree-sitter/tree-sitter-rust.git"
        "v0.23.2"
        TRUE
    )
endif()

# C++
if("cpp" IN_LIST GRAMMAR_LIST)
    add_ts_grammar(cpp
        "https://github.com/tree-sitter/tree-sitter-cpp.git"
        "v0.23.4"
        TRUE
    )
endif()

# Java
if("java" IN_LIST GRAMMAR_LIST)
    add_ts_grammar(java
        "https://github.com/tree-sitter/tree-sitter-java.git"
        "v0.23.5"
        FALSE
    )
endif()

# TypeScript
if("typescript" IN_LIST GRAMMAR_LIST)
    # TypeScript has a subdirectory structure
    FetchContent_Declare(
        tree_sitter_typescript
        GIT_REPOSITORY "https://github.com/tree-sitter/tree-sitter-typescript.git"
        GIT_TAG "v0.23.2"
        GIT_SHALLOW TRUE
    )

    FetchContent_GetProperties(tree_sitter_typescript)
    if(NOT tree_sitter_typescript_POPULATED)
        FetchContent_Populate(tree_sitter_typescript)

        # TypeScript grammar
        add_library(tree-sitter-typescript SHARED
            "${tree_sitter_typescript_SOURCE_DIR}/typescript/src/parser.c"
            "${tree_sitter_typescript_SOURCE_DIR}/typescript/src/scanner.c"
        )
        target_include_directories(tree-sitter-typescript PRIVATE
            "${tree_sitter_typescript_SOURCE_DIR}/typescript/src"
        )
        target_compile_options(tree-sitter-typescript PRIVATE -fvisibility=hidden -O2)
        set_target_properties(tree-sitter-typescript PROPERTIES
            OUTPUT_NAME "tree-sitter-typescript"
            LIBRARY_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/grammars"
        )

        # TSX grammar
        add_library(tree-sitter-tsx SHARED
            "${tree_sitter_typescript_SOURCE_DIR}/tsx/src/parser.c"
            "${tree_sitter_typescript_SOURCE_DIR}/tsx/src/scanner.c"
        )
        target_include_directories(tree-sitter-tsx PRIVATE
            "${tree_sitter_typescript_SOURCE_DIR}/tsx/src"
        )
        target_compile_options(tree-sitter-tsx PRIVATE -fvisibility=hidden -O2)
        set_target_properties(tree-sitter-tsx PROPERTIES
            OUTPUT_NAME "tree-sitter-tsx"
            LIBRARY_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/grammars"
        )
    endif()
endif()

# HTML
if("html" IN_LIST GRAMMAR_LIST)
    add_ts_grammar(html
        "https://github.com/tree-sitter/tree-sitter-html.git"
        "v0.23.2"
        TRUE
    )
endif()

# CSS
if("css" IN_LIST GRAMMAR_LIST)
    add_ts_grammar(css
        "https://github.com/tree-sitter/tree-sitter-css.git"
        "v0.23.1"
        TRUE
    )
endif()

# Markdown
if("markdown" IN_LIST GRAMMAR_LIST)
    # Markdown has inline and block grammars
    FetchContent_Declare(
        tree_sitter_markdown
        GIT_REPOSITORY "https://github.com/tree-sitter-grammars/tree-sitter-markdown.git"
        GIT_TAG "v0.4.0"
        GIT_SHALLOW TRUE
    )

    FetchContent_GetProperty(tree_sitter_markdown)
    if(NOT tree_sitter_markdown_POPULATED)
        FetchContent_Populate(tree_sitter_markdown)

        add_library(tree-sitter-markdown SHARED
            "${tree_sitter_markdown_SOURCE_DIR}/tree-sitter-markdown/src/parser.c"
            "${tree_sitter_markdown_SOURCE_DIR}/tree-sitter-markdown/src/scanner.c"
        )
        target_include_directories(tree-sitter-markdown PRIVATE
            "${tree_sitter_markdown_SOURCE_DIR}/tree-sitter-markdown/src"
        )
        target_compile_options(tree-sitter-markdown PRIVATE -fvisibility=hidden -O2)
        set_target_properties(tree-sitter-markdown PROPERTIES
            OUTPUT_NAME "tree-sitter-markdown"
            LIBRARY_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/grammars"
        )
    endif()
endif()

# C#
if("csharp" IN_LIST GRAMMAR_LIST)
    add_ts_grammar(csharp
        "https://github.com/tree-sitter/tree-sitter-c-sharp.git"
        "v0.23.1"
        TRUE
    )
endif()
