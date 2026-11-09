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
set(OPN_DIR ${CMAKE_CURRENT_SOURCE_DIR}/opn)
set(OPM_DIR ${CMAKE_CURRENT_SOURCE_DIR}/opm)
set(SMS_DIR ${CMAKE_CURRENT_SOURCE_DIR}/sms)
set(EMU2413_DIR ${CMAKE_CURRENT_SOURCE_DIR}/third_party/emu2413)
set(TIA_DIR ${CMAKE_CURRENT_SOURCE_DIR}/tia)
set(STELLA_TIA_DIR ${CMAKE_CURRENT_SOURCE_DIR}/third_party/stella-tia)
set(POKEY_DIR ${CMAKE_CURRENT_SOURCE_DIR}/pokey)
set(AY_DIR ${CMAKE_CURRENT_SOURCE_DIR}/ay)
set(PCE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/pce)
set(MEDNAFEN_PSG_DIR ${CMAKE_CURRENT_SOURCE_DIR}/third_party/mednafen-pce-psg)
set(SCC_DIR ${CMAKE_CURRENT_SOURCE_DIR}/scc)

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

# ymfm (C++): the YM2612, the EPSM's YMF288 and the YM2203 / YM2608. ym/ym_ymfm.cpp (the YM2612's C interface)
# compiles ymfm_opn.cpp itself and instantiates the OPN and OPNA templates that nes/nes_epsm_ymfm.cpp (the YMF288's C
# interface) and opn/opn_ymfm.cpp (the YM2203's and YM2608's) use; link these wrappers and the OPM wrapper. ym2608_rhythm_rom.c (C) is the one
# rhythm ROM both rhythm chips read, kept in this library beside its readers.
add_library(ymfm STATIC
    ${YM_DIR}/ym_ymfm.cpp
    ${NES_DIR}/nes_epsm_ymfm.cpp
    ${OPM_DIR}/opm_ymfm.cpp
    ${OPN_DIR}/opn_ymfm.cpp
    ym2608_rhythm_rom.c
    ${YMFM_DIR}/ymfm_adpcm.cpp
    ${YMFM_DIR}/ymfm_ssg.cpp
)
target_include_directories(ymfm PRIVATE ${CMAKE_CURRENT_SOURCE_DIR} ${YMFM_DIR} ${YM_DIR} ${NES_DIR} ${OPN_DIR} ${OPM_DIR})
target_compile_definitions(ymfm PRIVATE NDEBUG=1)
target_compile_options(ymfm PRIVATE -O2 -fPIC -w)
set_target_properties(ymfm PROPERTIES POSITION_INDEPENDENT_CODE ON)

# The chip machines behind chip_engine.h. huge_machine.c includes binjgb's emulator.c; the NES and
# SID machines share chips' m6502.h (header-only, implemented once in cpu6502.c); the YM2612 machine's Z80 is
# chips' z80.h (implemented once in cpuz80.c); the NES VRC7 build's FM chip is emu2413 (C, MIT); the SCC machine's
# K051649 / K052539 is our port of MAME's k051649 (scc/scc_k051649.c, BSD-3-Clause).
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
    ${NES_DIR}/nes_vrc6.c
    ${NES_DIR}/nes_vrc7.c
    ${NES_DIR}/nes_n163.c
    ${NES_DIR}/nes_fds.c
    ${NES_DIR}/nes_s5b.c
    ${NES_DIR}/nes_mmc5.c
    ${NES_DIR}/nes_mmc5_pcm.c
    ${NES_DIR}/nes_epsm_unit.c
    ${EMU2413_DIR}/emu2413.c
    chip_opll.c
    ${NES_DIR}/nes_players.c
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
    ${TIA_DIR}/tia_chip.c
    ${TIA_DIR}/tia_machine.c
    ${TIA_DIR}/tia_player.c
    ${TIA_DIR}/tia_image.c
    ${TIA_DIR}/tia_stella.cpp
    ${POKEY_DIR}/pokey_engine.c
    ${POKEY_DIR}/pokey_machine.c
    ${POKEY_DIR}/pokey_player.c
    ${POKEY_DIR}/pokey_chip.c
    ${POKEY_DIR}/pokey_image.c
    ${POKEY_DIR}/pokey_vgm.c
    ${OPN_DIR}/opn_chip.c
    ${OPN_DIR}/opn_machine.c
    ${OPN_DIR}/opn_player.c
    ${OPN_DIR}/opn_image.c
    ${OPN_DIR}/opn_vgm.c
    ${OPM_DIR}/opm_chip.c
    ${OPM_DIR}/opm_machine.c
    ${OPM_DIR}/opm_player.c
    ${OPM_DIR}/opm_image.c
    ${OPM_DIR}/opm_vgm.c
    ${SMS_DIR}/sms_chip.c
    ${SMS_DIR}/sms_machine.c
    ${SMS_DIR}/sms_image.c
    ${SMS_DIR}/sms_vgm.c
    ${AY_DIR}/ay_chip.c
    ${AY_DIR}/ay_machine.c
    ${AY_DIR}/ay_player.c
    ${AY_DIR}/ay_psg.c
    ${AY_DIR}/ay_image.c
    ${AY_DIR}/ay_vgm.c
    ${PCE_DIR}/pce_chip.c
    ${PCE_DIR}/pce_machine.c
    ${PCE_DIR}/pce_player.c
    ${PCE_DIR}/pce_image.c
    ${PCE_DIR}/pce_vgm.c
    ${PCE_DIR}/pce_psg.cpp
    ${SCC_DIR}/scc_chip.c
    ${SCC_DIR}/scc_machine.c
    ${SCC_DIR}/scc_player.c
    ${SCC_DIR}/scc_image.c
    ${SCC_DIR}/scc_vgm.c
    ${SCC_DIR}/scc_k051649.c
)
target_include_directories(chip_engines PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}
    PRIVATE ${BINJGB_DIR} ${NES_DIR} ${SID_DIR} ${OPL3_DIR} ${NUKED_DIR} ${YM_DIR} ${OPN_DIR} ${OPM_DIR} ${SMS_DIR} ${AY_DIR} ${PCE_DIR} ${SCC_DIR} ${BLIP_DIR} ${TIA_DIR} ${POKEY_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/third_party/chips ${MEDNAFEN_PSG_DIR}/shim ${STELLA_TIA_DIR} ${STELLA_TIA_DIR}/shim)
target_compile_definitions(chip_engines PRIVATE NDEBUG=1)
target_compile_options(chip_engines PRIVATE -O2 -fPIC -Wno-unused-function -Wno-unused-variable
    -Wno-unused-but-set-variable -Wno-sign-compare -Wno-missing-field-initializers -Wno-enum-compare)
# pce/pce_psg.cpp (C++) compiles Mednafen's PSG, unmodified, against its shim: vendored code, warnings off.
set_source_files_properties(${PCE_DIR}/pce_psg.cpp PROPERTIES COMPILE_OPTIONS "-w")
# Stella's unmodified save()/load() use try/catch.
set_source_files_properties(${TIA_DIR}/tia_stella.cpp PROPERTIES COMPILE_OPTIONS "-w;-fexceptions")
set_target_properties(chip_engines PROPERTIES POSITION_INDEPENDENT_CODE ON)
target_link_libraries(chip_engines PUBLIC resid ymfm)

add_library(thortracker_audio SHARED
    audio_engine.cpp
    audio_jni.cpp
)
target_compile_options(thortracker_audio PRIVATE -O2 -Wall -Wextra -Wno-unused-parameter)
target_link_libraries(thortracker_audio PRIVATE chip_engines aaudio log m dl)
