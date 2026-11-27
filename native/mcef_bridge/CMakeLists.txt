# libmcef —— MCEF 的 Android native 桥接层
# Copyright (C) 2026 Rain_Clay and contributors
#
# This program is free software: you can redistribute it and/or modify
# it under the terms of the GNU General Public License as published by
# the Free Software Foundation, either version 3 of the License, or
# (at your option) any later version.
#
# This program is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
# GNU General Public License for more details.
#
# You should have received a copy of the GNU General Public License
# along with this program.  If not, see <https://www.gnu.org/licenses/>.

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
