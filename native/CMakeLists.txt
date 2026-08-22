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

# A placeholder `lib<output_name>.so` so a machine without cargo still links and runs, with the
# feature degraded rather than the build broken.
#
# `JCODE_REAL_LIB_PRESENT` says cargo has already produced the genuine library for this variant, and
# then the stub must not exist *as a shared library at all*. A SHARED stub is written into the
# CMake object directory under the very name AGP takes the cargo library from, and the native-library
# merger cannot choose between them — it fails the build:
#
#   2 files found with path 'lib/arm64-v8a/libripgrep_ffi.so'
#     - …/merged_jni_libs/release/…/libripgrep_ffi.so     (3.9 MB, cargo)
#     - …/intermediates/cxx/RelWithDebInfo/…/obj/…         (4 KB, this stub)
#
# Dropping the target entirely would leave the module's CMake project with nothing to build, so it
# is built STATIC instead: AGP still has a target, and a `.a` is never packaged.
function(jcode_add_stub_library target output_name)
    if(JCODE_REAL_LIB_PRESENT)
        add_library(${target} STATIC "${JCODE_STUB_SOURCE}")
        target_compile_definitions(${target} PRIVATE JCODE_STUB_MODULE="${output_name}")
        return()
    endif()
    add_library(${target} SHARED "${JCODE_STUB_SOURCE}")
    target_compile_definitions(${target} PRIVATE JCODE_STUB_MODULE="${output_name}")
    jcode_configure_target(${target} ${output_name})
endfunction()






if(JCODE_NATIVE_MODULE STREQUAL "buffer")
    add_library(jcode_native_buffer SHARED
        "${CMAKE_CURRENT_LIST_DIR}/buffer/src/piece_tree.cpp"
        "${CMAKE_CURRENT_LIST_DIR}/buffer/src/jni_buffer.cpp"
        "${CMAKE_CURRENT_LIST_DIR}/buffer/src/highlight.cpp"
        "${CMAKE_CURRENT_LIST_DIR}/buffer/src/jni_highlight.cpp"
        "${CMAKE_CURRENT_LIST_DIR}/buffer/src/wrap_map.cpp"
        "${CMAKE_CURRENT_LIST_DIR}/buffer/src/jni_wrap.cpp"
        "${JCODE_STUB_SOURCE}"
    )
    target_include_directories(jcode_native_buffer PRIVATE
        "${CMAKE_CURRENT_LIST_DIR}/buffer/src"
    )
    target_link_libraries(jcode_native_buffer PRIVATE log)
    # The text buffer is on the per-keystroke path; keep it optimized even in debug builds,
    # where the AGP default -O0 makes it slower than the Kotlin fallback it replaces.
    target_compile_options(jcode_native_buffer PRIVATE -O2)
    jcode_configure_target(jcode_native_buffer jcodebuffer)
elseif(JCODE_NATIVE_MODULE STREQUAL "editor-render")
    jcode_add_stub_library(jcode_native_editor_render jcodernd)
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
    # The PTY shim sits on the same per-chunk terminal drain path as the VT parser; keep it
    # optimized even in debug builds, where the AGP default -O0 cripples per-byte native code.
    target_compile_options(jcode_native_pty PRIVATE -O2)
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
    # The VT parser walks every PTY output byte; keep it optimized even in debug builds, where the
    # AGP default -O0 cripples per-byte native parsers.
    target_compile_options(jcode_native_vt PRIVATE -O2)
    jcode_configure_target(jcode_native_vt jcode_vt)
elseif(JCODE_NATIVE_MODULE STREQUAL "wasmtime-ffi")
    jcode_add_stub_library(jcode_native_wasmtime jcode_wasm)
else()
    message(FATAL_ERROR "Unknown JCODE_NATIVE_MODULE='${JCODE_NATIVE_MODULE}'")
endif()
