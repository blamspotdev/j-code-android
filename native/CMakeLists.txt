cmake_minimum_required(VERSION 3.22.1)

project(jcode_native_superbuild LANGUAGES C CXX)

include(FetchContent)

if(NOT DEFINED JCODE_NATIVE_MODULE)
    message(FATAL_ERROR "JCODE_NATIVE_MODULE must be provided by Gradle.")
endif()

if(NOT DEFINED JCODE_JNI_OUTPUT_DIR)
    message(FATAL_ERROR "JCODE_JNI_OUTPUT_DIR must be provided by Gradle.")
endif()

if(NOT DEFINED JCODE_VARIANT_DIR)
    message(FATAL_ERROR "JCODE_VARIANT_DIR must be provided by Gradle.")
endif()

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_C_EXTENSIONS OFF)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(FETCHCONTENT_UPDATES_DISCONNECTED ON CACHE BOOL "" FORCE)

set(JCODE_STUB_SOURCE "${CMAKE_CURRENT_LIST_DIR}/common/jcode_native_stub.c")
set(JCODE_OUTPUT_DIR "${JCODE_JNI_OUTPUT_DIR}/${JCODE_VARIANT_DIR}/${ANDROID_ABI}")

function(jcode_configure_target target output_name)
    target_compile_options(${target} PRIVATE -fvisibility=hidden)
    target_link_options(${target} PRIVATE "-Wl,-z,max-page-size=16384")

    if(JCODE_VARIANT_DIR STREQUAL "release")
        target_link_options(${target} PRIVATE "-Wl,-s")
    endif()

    set_target_properties(${target} PROPERTIES
        OUTPUT_NAME "${output_name}"
        LIBRARY_OUTPUT_DIRECTORY "${JCODE_OUTPUT_DIR}"
        RUNTIME_OUTPUT_DIRECTORY "${JCODE_OUTPUT_DIR}"
    )
endfunction()

function(jcode_add_stub_library target output_name)
    add_library(${target} SHARED "${JCODE_STUB_SOURCE}")
    target_compile_definitions(${target} PRIVATE JCODE_STUB_MODULE="${output_name}")
    jcode_configure_target(${target} ${output_name})
endfunction()

function(jcode_fetch_tree_sitter)
    set(TREE_SITTER_FEATURE_WASM OFF CACHE BOOL "" FORCE)

    FetchContent_Declare(tree_sitter_upstream
        GIT_REPOSITORY https://github.com/tree-sitter/tree-sitter.git
        GIT_TAG 8b9e570c5773b058f210834f86779dcbf1a1878b
        SOURCE_SUBDIR lib
    )

    FetchContent_MakeAvailable(tree_sitter_upstream)
endfunction()

function(jcode_fetch_mbedtls)
    set(USE_STATIC_MBEDTLS_LIBRARY ON CACHE BOOL "" FORCE)
    set(USE_SHARED_MBEDTLS_LIBRARY OFF CACHE BOOL "" FORCE)
    set(ENABLE_PROGRAMS OFF CACHE BOOL "" FORCE)
    set(ENABLE_TESTING OFF CACHE BOOL "" FORCE)
    set(GEN_FILES OFF CACHE BOOL "" FORCE)
    set(LINK_WITH_PTHREAD OFF CACHE BOOL "" FORCE)
    set(MBEDTLS_FATAL_WARNINGS OFF CACHE BOOL "" FORCE)
    set(MBEDTLS_TARGET_PREFIX "" CACHE STRING "" FORCE)

    FetchContent_Declare(mbedtls_upstream
        GIT_REPOSITORY https://github.com/Mbed-TLS/mbedtls.git
        GIT_TAG 34e66e1b7b97f9dc69c19f6f14c9f91e588dc31a
        GIT_SUBMODULES_RECURSE TRUE
    )

    FetchContent_MakeAvailable(mbedtls_upstream)
endfunction()

function(jcode_fetch_libssh2)
    FetchContent_GetProperties(mbedtls_upstream)

    set(MBEDTLS_INCLUDE_DIR "${mbedtls_upstream_SOURCE_DIR}/include" CACHE PATH "" FORCE)
    set(MBEDCRYPTO_LIBRARY "${mbedtls_upstream_BINARY_DIR}/library/libmbedcrypto.a" CACHE FILEPATH "" FORCE)
    set(MBEDTLS_VERSION "3.6.2" CACHE STRING "" FORCE)
    set(BUILD_STATIC_LIBS ON CACHE BOOL "" FORCE)
    set(BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
    set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
    set(CRYPTO_BACKEND mbedTLS CACHE STRING "" FORCE)
    set(ENABLE_ZLIB_COMPRESSION OFF CACHE BOOL "" FORCE)
    set(ENABLE_DEBUG_LOGGING OFF CACHE BOOL "" FORCE)
    set(HIDE_SYMBOLS ON CACHE BOOL "" FORCE)

    FetchContent_Declare(libssh2_upstream
        GIT_REPOSITORY https://github.com/libssh2/libssh2.git
        GIT_TAG 3a735286108ad19e3b49c64ebcb66342f1f21df7
    )

    FetchContent_MakeAvailable(libssh2_upstream)
endfunction()

function(jcode_fetch_libgit2)
    set(BUILD_TESTS OFF CACHE BOOL "" FORCE)
    set(BUILD_CLI OFF CACHE BOOL "" FORCE)
    set(BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
    set(BUILD_FUZZERS OFF CACHE BOOL "" FORCE)
    set(USE_SSH OFF CACHE STRING "" FORCE)
    set(USE_HTTPS OFF CACHE STRING "" FORCE)
    set(USE_SHA1 CollisionDetection CACHE STRING "" FORCE)
    set(USE_SHA256 builtin CACHE STRING "" FORCE)
    set(USE_HTTP_PARSER builtin CACHE STRING "" FORCE)
    set(USE_THREADS ON CACHE BOOL "" FORCE)
    set(USE_BUNDLED_ZLIB OFF CACHE BOOL "" FORCE)
    set(USE_NSEC OFF CACHE BOOL "" FORCE)
    set(ENABLE_WERROR OFF CACHE BOOL "" FORCE)
    set(SONAME OFF CACHE BOOL "" FORCE)

    FetchContent_Declare(libgit2_upstream
        GIT_REPOSITORY https://github.com/libgit2/libgit2.git
        GIT_TAG 0060d9cf5666f015b1067129bd874c6cc4c9c7ac
    )

    FetchContent_MakeAvailable(libgit2_upstream)
endfunction()

function(jcode_fetch_yaml_cpp)
    set(YAML_CPP_BUILD_TESTS OFF CACHE BOOL "" FORCE)
    set(YAML_CPP_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
    set(YAML_CPP_BUILD_CONTRIB OFF CACHE BOOL "" FORCE)
    set(YAML_CPP_INSTALL OFF CACHE BOOL "" FORCE)
    set(YAML_BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

    FetchContent_Declare(yaml_cpp_upstream
        GIT_REPOSITORY https://github.com/jbeder/yaml-cpp.git
        GIT_TAG 0.8.0
    )

    FetchContent_MakeAvailable(yaml_cpp_upstream)
endfunction()

if(JCODE_NATIVE_MODULE STREQUAL "buffer")
    add_library(jcode_native_buffer SHARED
        "${CMAKE_CURRENT_LIST_DIR}/buffer/src/piece_tree.cpp"
        "${CMAKE_CURRENT_LIST_DIR}/buffer/src/jni_buffer.cpp"
        "${JCODE_STUB_SOURCE}"
    )
    target_include_directories(jcode_native_buffer PRIVATE
        "${CMAKE_CURRENT_LIST_DIR}/buffer/src"
    )
    target_link_libraries(jcode_native_buffer PRIVATE log)
    jcode_configure_target(jcode_native_buffer jcodebuffer)
elseif(JCODE_NATIVE_MODULE STREQUAL "editor-render")
    jcode_add_stub_library(jcode_native_editor_render jcodernd)
elseif(JCODE_NATIVE_MODULE STREQUAL "tree-sitter")
    jcode_fetch_tree_sitter()
    FetchContent_GetProperties(tree_sitter_upstream)
    jcode_add_stub_library(jcode_native_tree_sitter treesitter)
    target_sources(jcode_native_tree_sitter PRIVATE
        "${CMAKE_CURRENT_LIST_DIR}/common/jcode_tree_sitter_anchor.c"
        "${CMAKE_CURRENT_LIST_DIR}/tree-sitter/src/jni_treesitter.c"
    )
    target_include_directories(jcode_native_tree_sitter PRIVATE
        "${tree_sitter_upstream_SOURCE_DIR}/lib/include"
        "${CMAKE_CURRENT_LIST_DIR}/tree-sitter/src"
    )
    target_link_libraries(jcode_native_tree_sitter PRIVATE
        "$<TARGET_FILE:tree-sitter>"
        log
        dl
    )
elseif(JCODE_NATIVE_MODULE STREQUAL "libgit2")
    jcode_fetch_mbedtls()
    jcode_fetch_libssh2()
    jcode_fetch_libgit2()

    jcode_add_stub_library(jcode_native_libgit2 git2_ffi)
    target_link_libraries(jcode_native_libgit2 PRIVATE
        "$<LINK_LIBRARY:WHOLE_ARCHIVE,libgit2package>"
        "$<LINK_LIBRARY:WHOLE_ARCHIVE,libssh2_static>"
        MbedTLS::mbedtls
        MbedTLS::mbedx509
        MbedTLS::mbedcrypto
    )
elseif(JCODE_NATIVE_MODULE STREQUAL "ripgrep-ffi")
    jcode_add_stub_library(jcode_native_ripgrep ripgrep_ffi)
elseif(JCODE_NATIVE_MODULE STREQUAL "pty")
    add_library(jcode_native_pty SHARED
        "${CMAKE_CURRENT_LIST_DIR}/pty/src/pty.cpp"
        "${CMAKE_CURRENT_LIST_DIR}/pty/src/jni_pty.cpp"
        "${JCODE_STUB_SOURCE}"
    )
    target_include_directories(jcode_native_pty PRIVATE
        "${CMAKE_CURRENT_LIST_DIR}/pty/src"
    )
    target_link_libraries(jcode_native_pty PRIVATE log)
    jcode_configure_target(jcode_native_pty pty)
elseif(JCODE_NATIVE_MODULE STREQUAL "vt")
    add_library(jcode_native_vt SHARED
        "${CMAKE_CURRENT_LIST_DIR}/vt/src/vt_parser.c"
        "${CMAKE_CURRENT_LIST_DIR}/vt/src/jni_vt.c"
        "${JCODE_STUB_SOURCE}"
    )
    target_include_directories(jcode_native_vt PRIVATE
        "${CMAKE_CURRENT_LIST_DIR}/vt/src"
    )
    target_link_libraries(jcode_native_vt PRIVATE log)
    jcode_configure_target(jcode_native_vt jcode_vt)
elseif(JCODE_NATIVE_MODULE STREQUAL "wasmtime-ffi")
    jcode_add_stub_library(jcode_native_wasmtime jcode_wasm)
elseif(JCODE_NATIVE_MODULE STREQUAL "core")
    jcode_fetch_yaml_cpp()
    FetchContent_GetProperties(yaml_cpp_upstream)
    
    add_library(jcode_native_core SHARED
        "${CMAKE_CURRENT_LIST_DIR}/core/src/main/cpp/jni_core.cpp"
        "${CMAKE_CURRENT_LIST_DIR}/core/src/main/cpp/jni_bridge.cpp"
        "${CMAKE_CURRENT_LIST_DIR}/core/src/main/cpp/event_loop.cpp"
        "${CMAKE_CURRENT_LIST_DIR}/core/src/main/cpp/resource_manager.cpp"
        "${CMAKE_CURRENT_LIST_DIR}/core/src/main/cpp/logger.cpp"
        "${CMAKE_CURRENT_LIST_DIR}/core/src/main/cpp/config_service.cpp"
        "${CMAKE_CURRENT_LIST_DIR}/core/src/main/cpp/jni_config.cpp"
        "${CMAKE_CURRENT_LIST_DIR}/core/src/main/cpp/editor_state.cpp"
        "${CMAKE_CURRENT_LIST_DIR}/core/src/main/cpp/undo_manager.cpp"
        "${CMAKE_CURRENT_LIST_DIR}/core/src/main/cpp/jni_editor_state.cpp"
        "${JCODE_STUB_SOURCE}"
    )
    target_include_directories(jcode_native_core PRIVATE
        "${CMAKE_CURRENT_LIST_DIR}/core/src/main/cpp"
        "${yaml_cpp_upstream_SOURCE_DIR}/include"
    )
    target_link_libraries(jcode_native_core PRIVATE 
        log
        yaml-cpp
    )
    jcode_configure_target(jcode_native_core jcode_core)
else()
    message(FATAL_ERROR "Unknown JCODE_NATIVE_MODULE='${JCODE_NATIVE_MODULE}'")
endif()
