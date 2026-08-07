cmake_minimum_required(VERSION 3.22.1)
project(lodestone C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_compile_options(-Wall -Wextra -Wno-unused-parameter)

# Release builds run inside the game's frame loop, so favour speed and let the linker drop
# everything the shims do not export.
if(CMAKE_BUILD_TYPE STREQUAL "Release" OR CMAKE_BUILD_TYPE STREQUAL "RelWithDebInfo")
    add_compile_options(-O3 -ffunction-sections -fdata-sections)
    add_link_options(-Wl,--gc-sections -Wl,--exclude-libs,ALL)
endif()

add_library(lodestone_jvm SHARED
    jvm/jvm_bridge.cpp
    jvm/stdio_pump.cpp
)

target_include_directories(lodestone_jvm PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

# `dl` is needed to open the runtime's libjvm.so by path; `log` routes the VM's stdio to logcat.
target_link_libraries(lodestone_jvm dl log android)
