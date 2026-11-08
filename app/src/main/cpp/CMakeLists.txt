cmake_minimum_required(VERSION 3.22.1)
project(thortracker_audio C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_EXTENSIONS ON)       # binjgb uses GNU C (anonymous structs, __builtin_expect)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(BINJGB_DIR ${CMAKE_CURRENT_SOURCE_DIR}/third_party/binjgb/src)
set(NES_DIR ${CMAKE_CURRENT_SOURCE_DIR}/nes)
set(SID_DIR ${CMAKE_CURRENT_SOURCE_DIR}/sid)
set(BLIP_DIR ${CMAKE_CURRENT_SOURCE_DIR}/third_party/blip_buf)
set(RESID_DIR ${CMAKE_CURRENT_SOURCE_DIR}/third_party/resid-1.0)

# reSID 1.0 (C++), the SID chip; only sid/sid_resid.cpp (a C interface) talks to it.
add_library(resid STATIC
    ${RESID_DIR}/sid.cc
    ${RESID_DIR}/voice.cc
    ${RESID_DIR}/wave.cc
    ${RESID_DIR}/envelope.cc
    ${RESID_DIR}/filter.cc
    ${RESID_DIR}/dac.cc
    ${RESID_DIR}/extfilt.cc
    ${RESID_DIR}/pot.cc
    ${SID_DIR}/sid_resid.cpp
)
target_include_directories(resid PRIVATE ${RESID_DIR} ${CMAKE_CURRENT_SOURCE_DIR})
target_compile_definitions(resid PRIVATE NDEBUG=1)
target_compile_options(resid PRIVATE -O2 -fPIC -w)
set_target_properties(resid PROPERTIES POSITION_INDEPENDENT_CODE ON)

# The chip machines behind chip_engine.h. huge_machine.c includes binjgb's emulator.c; the NES and
# SID machines share chips' m6502.h (header-only, implemented once in cpu6502.c).
add_library(chip_engines STATIC
    chip_engine.c
    cpu6502.c
    huge_chip.c
    huge_machine.c
    ${BINJGB_DIR}/common.c
    ${NES_DIR}/nes_chip.c
    ${NES_DIR}/nes_machine.c
    ${NES_DIR}/nes_image.c
    ${NES_DIR}/nes_apu.c
    ${BLIP_DIR}/blip_buf.c
    ${SID_DIR}/sid_chip.c
    ${SID_DIR}/sid_machine.c
    ${SID_DIR}/sid_image.c
)
target_include_directories(chip_engines PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}
    PRIVATE ${BINJGB_DIR} ${NES_DIR} ${SID_DIR} ${BLIP_DIR} ${CMAKE_CURRENT_SOURCE_DIR}/third_party/chips)
target_compile_definitions(chip_engines PRIVATE NDEBUG=1)
target_compile_options(chip_engines PRIVATE -O2 -fPIC -Wno-unused-function -Wno-unused-variable
    -Wno-unused-but-set-variable -Wno-sign-compare -Wno-missing-field-initializers -Wno-enum-compare)
set_target_properties(chip_engines PROPERTIES POSITION_INDEPENDENT_CODE ON)
target_link_libraries(chip_engines PUBLIC resid)

add_library(thortracker_audio SHARED
    audio_engine.cpp
    audio_jni.cpp
)
target_compile_options(thortracker_audio PRIVATE -O2 -Wall -Wextra -Wno-unused-parameter)
target_link_libraries(thortracker_audio PRIVATE chip_engines aaudio log m dl)
