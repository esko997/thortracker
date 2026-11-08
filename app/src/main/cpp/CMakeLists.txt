cmake_minimum_required(VERSION 3.22.1)
project(thortracker_audio C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_EXTENSIONS ON)       # binjgb uses GNU C (anonymous structs, __builtin_expect)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Gradle's worker limit does not constrain Ninja's per-ABI compiler fan-out.
set_property(GLOBAL APPEND PROPERTY JOB_POOLS thor_compile=2)
set(CMAKE_JOB_POOL_COMPILE thor_compile)

set(BINJGB_DIR ${CMAKE_CURRENT_SOURCE_DIR}/third_party/binjgb/src)
set(NES_DIR ${CMAKE_CURRENT_SOURCE_DIR}/nes)
set(SID_DIR ${CMAKE_CURRENT_SOURCE_DIR}/sid)
set(BLIP_DIR ${CMAKE_CURRENT_SOURCE_DIR}/third_party/blip_buf)
set(RESID_DIR ${CMAKE_CURRENT_SOURCE_DIR}/third_party/resid-1.0)
set(OPL3_DIR ${CMAKE_CURRENT_SOURCE_DIR}/opl3)
set(NUKED_DIR ${CMAKE_CURRENT_SOURCE_DIR}/third_party/nuked-opl3)
set(YM_DIR ${CMAKE_CURRENT_SOURCE_DIR}/ym)
set(YMFM_DIR ${CMAKE_CURRENT_SOURCE_DIR}/third_party/ymfm)
set(SMS_DIR ${CMAKE_CURRENT_SOURCE_DIR}/sms)
set(EMU2413_DIR ${CMAKE_CURRENT_SOURCE_DIR}/third_party/emu2413)

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

# ymfm (C++), the YM2612; only ym/ym_ymfm.cpp (a C interface, which compiles ymfm_opn.cpp itself) talks to it.
add_library(ymfm STATIC
    ${YM_DIR}/ym_ymfm.cpp
    ${YMFM_DIR}/ymfm_adpcm.cpp
    ${YMFM_DIR}/ymfm_ssg.cpp
)
target_include_directories(ymfm PRIVATE ${YMFM_DIR} ${YM_DIR})
target_compile_definitions(ymfm PRIVATE NDEBUG=1)
target_compile_options(ymfm PRIVATE -O2 -fPIC -w)
set_target_properties(ymfm PROPERTIES POSITION_INDEPENDENT_CODE ON)

# The chip machines behind chip_engine.h. huge_machine.c includes binjgb's emulator.c; the NES and
# SID machines share chips' m6502.h (header-only, implemented once in cpu6502.c); the YM2612 machine's Z80 is
# chips' z80.h (implemented once in cpuz80.c).
add_library(chip_engines STATIC
    chip_engine.c
    chip_wav.c
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
    ${OPL3_DIR}/opl3_chip.c
    ${OPL3_DIR}/opl3_machine.c
    ${OPL3_DIR}/opl3_player.c
    ${OPL3_DIR}/opl3_image.c
    ${OPL3_DIR}/opl3_vgm.c
    ${NUKED_DIR}/opl3.c
    vgm_gd3.c
    cpuz80.c
    ${YM_DIR}/ym_chip.c
    ${YM_DIR}/ym_machine.c
    ${YM_DIR}/ym_player.c
    ${YM_DIR}/ym_image.c
    ${YM_DIR}/ym_xgm.c
    ${YM_DIR}/ym_vgm.c
    ${YM_DIR}/ym_psg.c
    ${SMS_DIR}/sms_chip.c
    ${SMS_DIR}/sms_machine.c
    ${SMS_DIR}/sms_image.c
    ${SMS_DIR}/sms_vgm.c
    ${EMU2413_DIR}/emu2413.c
)
target_include_directories(chip_engines PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}
    PRIVATE ${BINJGB_DIR} ${NES_DIR} ${SID_DIR} ${OPL3_DIR} ${NUKED_DIR} ${YM_DIR} ${SMS_DIR} ${BLIP_DIR} ${CMAKE_CURRENT_SOURCE_DIR}/third_party/chips)
target_compile_definitions(chip_engines PRIVATE NDEBUG=1)
target_compile_options(chip_engines PRIVATE -O2 -fPIC -Wno-unused-function -Wno-unused-variable
    -Wno-unused-but-set-variable -Wno-sign-compare -Wno-missing-field-initializers -Wno-enum-compare)
set_target_properties(chip_engines PROPERTIES POSITION_INDEPENDENT_CODE ON)
target_link_libraries(chip_engines PUBLIC resid ymfm)

add_library(thortracker_audio SHARED
    audio_engine.cpp
    audio_jni.cpp
)
target_compile_options(thortracker_audio PRIVATE -O2 -Wall -Wextra -Wno-unused-parameter)
target_link_libraries(thortracker_audio PRIVATE chip_engines aaudio log m dl)
