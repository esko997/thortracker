cmake_minimum_required(VERSION 3.22.1)
project(thortracker_audio C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_EXTENSIONS ON)       # binjgb uses GNU C (anonymous structs, __builtin_expect)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(BINJGB_DIR ${CMAKE_CURRENT_SOURCE_DIR}/third_party/binjgb/src)

# The hUGEDriver machine: one C translation unit that includes binjgb's emulator.c.
add_library(huge_machine STATIC
    huge_machine.c
    ${BINJGB_DIR}/common.c
)
target_include_directories(huge_machine PUBLIC ${CMAKE_CURRENT_SOURCE_DIR} PRIVATE ${BINJGB_DIR})
target_compile_definitions(huge_machine PRIVATE NDEBUG=1)
target_compile_options(huge_machine PRIVATE -O2 -fPIC -Wno-unused-function -Wno-unused-variable
    -Wno-unused-but-set-variable -Wno-sign-compare -Wno-missing-field-initializers)
set_target_properties(huge_machine PROPERTIES POSITION_INDEPENDENT_CODE ON)

add_library(thortracker_audio SHARED
    huge_engine.cpp
    huge_jni.cpp
)
target_compile_options(thortracker_audio PRIVATE -O2 -Wall -Wextra -Wno-unused-parameter)
target_link_libraries(thortracker_audio PRIVATE huge_machine aaudio log m dl)
