cmake_minimum_required(VERSION 3.22)
project(mcef_bridge LANGUAGES CXX)

add_library(mcef SHARED
    src/mcef_bridge.cpp
    src/mcef_bridge_jni.cpp
    src/mcef_ipc_client.cpp
)

target_include_directories(mcef PUBLIC include)
target_compile_features(mcef PRIVATE cxx_std_17)
target_compile_options(mcef PRIVATE -Wall -Wextra -Wpedantic -fvisibility=hidden)

# Android builds should be driven by the consuming APK/AAR project. libcef.so is
# intentionally not linked here yet: this bridge can work as a stable client ABI
# over IPC, and a later in-process Cefrium backend can dlopen/link libcef.so when
# Android Context/Service plumbing is available.
if(ANDROID)
    find_library(log-lib log)
    target_link_libraries(mcef PRIVATE ${log-lib})
endif()
