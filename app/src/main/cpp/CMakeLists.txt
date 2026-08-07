cmake_minimum_required(VERSION 3.22.1)
project(lodestone C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_compile_options(-Wall -Wextra -Wno-unused-parameter)

# Release builds sit inside the game's frame loop, so favour speed and let the linker drop
# everything the shims do not export.
if(CMAKE_BUILD_TYPE STREQUAL "Release" OR CMAKE_BUILD_TYPE STREQUAL "RelWithDebInfo")
    add_compile_options(-O3 -ffunction-sections -fdata-sections)
    add_link_options(-Wl,--gc-sections)
endif()

# Boots a HotSpot VM in-process and pumps its stdio into the app log.
add_library(lodestone_jvm SHARED
    jvm/jvm_bridge.cpp
    jvm/stdio_pump.cpp
)
target_include_directories(lodestone_jvm PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
target_link_libraries(lodestone_jvm dl log android)

# glibc symbols the JDK references that bionic does not provide. Separate from lodestone_jvm so it
# can be opened with RTLD_GLOBAL, which is the only way the runtime's libraries can resolve against it.
add_library(lodestone_compat SHARED jvm/bionic_compat.cpp)
target_link_libraries(lodestone_compat log)

# Stands in for GLFW. LWJGL resolves these symbols by name, so unlike everything else here the
# library keeps default visibility for its glfw* exports.
add_library(lodestone_glfw SHARED
    glfw/glfw_api.cpp
    glfw/glfw_shim.cpp
)
target_include_directories(lodestone_glfw PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
target_link_libraries(lodestone_glfw EGL GLESv3 android log)
