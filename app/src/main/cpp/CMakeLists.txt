cmake_minimum_required(VERSION 3.22.1)
project(thortracker_audio C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_EXTENSIONS ON)       # binjgb uses GNU C (anonymous structs, __builtin_expect)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(BINJGB_DIR ${CMAKE_CURRENT_SOURCE_DIR}/third_party/binjgb/src)
set(NES_DIR ${CMAKE_CURRENT_SOURCE_DIR}/nes)
set(BLIP_DIR ${CMAKE_CURRENT_SOURCE_DIR}/third_party/blip_buf)

# The chip machines behind chip_engine.h. huge_machine.c includes binjgb's emulator.c;
# nes_machine.c includes chips' m6502.h (header-only).
add_library(chip_engines STATIC
    chip_engine.c
    huge_chip.c
    huge_machine.c
    ${BINJGB_DIR}/common.c
    ${NES_DIR}/nes_chip.c
    ${NES_DIR}/nes_machine.c
    ${NES_DIR}/nes_apu.c
    ${BLIP_DIR}/blip_buf.c
)
target_include_directories(chip_engines PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}
    PRIVATE ${BINJGB_DIR} ${NES_DIR} ${BLIP_DIR} ${CMAKE_CURRENT_SOURCE_DIR}/third_party/chips)
target_compile_definitions(chip_engines PRIVATE NDEBUG=1)
target_compile_options(chip_engines PRIVATE -O2 -fPIC -Wno-unused-function -Wno-unused-variable
    -Wno-unused-but-set-variable -Wno-sign-compare -Wno-missing-field-initializers -Wno-enum-compare)
set_target_properties(chip_engines PROPERTIES POSITION_INDEPENDENT_CODE ON)

add_library(thortracker_audio SHARED
    audio_engine.cpp
    audio_jni.cpp
)
target_compile_options(thortracker_audio PRIVATE -O2 -Wall -Wextra -Wno-unused-parameter)
target_link_libraries(thortracker_audio PRIVATE chip_engines aaudio log m dl)
