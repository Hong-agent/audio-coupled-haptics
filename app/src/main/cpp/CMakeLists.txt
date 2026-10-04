cmake_minimum_required(VERSION 3.22.1)
project(hapticogg C CXX)

set(CMAKE_C_STANDARD 99)

set(OGG_DIR ${CMAKE_CURRENT_SOURCE_DIR}/third_party/ogg)
set(VORBIS_DIR ${CMAKE_CURRENT_SOURCE_DIR}/third_party/vorbis)

file(GLOB OGG_SOURCES ${OGG_DIR}/src/*.c)
file(GLOB VORBIS_SOURCES ${VORBIS_DIR}/lib/*.c)

# 上游 lib/ 目录里混放了几个带 main() 的调试程序与解码用的 vorbisfile，
# 我们只需要编码链路，直接把它们从构建中剔除。
list(REMOVE_ITEM VORBIS_SOURCES
        ${VORBIS_DIR}/lib/psytune.c
        ${VORBIS_DIR}/lib/barkmel.c
        ${VORBIS_DIR}/lib/tone.c
        ${VORBIS_DIR}/lib/vorbisfile.c)

add_library(hapticogg SHARED
        haptic_ogg.cpp
        ${OGG_SOURCES}
        ${VORBIS_SOURCES})

target_include_directories(hapticogg PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}
        ${OGG_DIR}/include
        ${VORBIS_DIR}/include
        ${VORBIS_DIR}/include/vorbis
        ${VORBIS_DIR}/lib)

find_library(log-lib log)
target_link_libraries(hapticogg ${log-lib})

target_compile_options(hapticogg PRIVATE
        -Wall
        -Wno-unused-function
        -Wno-unused-variable
        -Wno-unused-but-set-variable
        -ffunction-sections
        -fdata-sections)

target_link_options(hapticogg PRIVATE -Wl,--gc-sections)
