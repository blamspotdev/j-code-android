# Placeholder CMakeLists.txt for :native:proot
#
# proot is a standalone executable bundled as an asset, not a shared
# library. This file is reserved for future use if we switch to
# compiling proot from source within the Gradle build.
#
# Current workflow: pre-compile proot with the NDK toolchain and
# place the binary in src/main/assets/bin/.

cmake_minimum_required(VERSION 3.22.1)
project(proot C)

# Future: add proot source compilation here
