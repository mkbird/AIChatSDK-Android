# Single CMake project building libaichat_native.so containing:
#   1. libopus 1.4 (encoder/decoder)
#   2. WebRTC AudioProcessing (AEC3 + NS + AGC2)
#   3. JNI shim (NativeBridge)
#
# Sources are pulled in as git submodules under third_party/:
#   third_party/opus              -> https://github.com/xiph/opus.git           (tag: v1.4)
#   third_party/webrtc-apm        -> https://gitlab.freedesktop.org/pulseaudio/webrtc-audio-processing.git (tag: v1.3)
#
# Run once after clone:
#   git submodule add https://github.com/xiph/opus.git aisdk/src/main/cpp/third_party/opus
#   git submodule add https://gitlab.freedesktop.org/pulseaudio/webrtc-audio-processing.git aisdk/src/main/cpp/third_party/webrtc-apm
#   cd aisdk/src/main/cpp/third_party/opus && git checkout v1.4
#   cd ../webrtc-apm && git checkout v1.3
#
# If the third_party sources are not present, only the JNI stub is built and
# every native call returns failure — Kotlin layer detects and falls back to PCM
# plus PTT-only mode (see NativeBridge.isAvailable()).

cmake_minimum_required(VERSION 3.22.1)
project(aichat_native LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(THIRD_PARTY ${CMAKE_CURRENT_SOURCE_DIR}/third_party)
set(OPUS_DIR ${THIRD_PARTY}/opus)
set(APM_DIR  ${THIRD_PARTY}/webrtc-apm)

set(HAVE_OPUS FALSE)
set(HAVE_APM  FALSE)

if(EXISTS ${OPUS_DIR}/CMakeLists.txt)
    set(HAVE_OPUS TRUE)
    set(OPUS_BUILD_TESTING OFF CACHE BOOL "" FORCE)
    set(OPUS_BUILD_PROGRAMS OFF CACHE BOOL "" FORCE)
    set(OPUS_BUILD_SHARED_LIBRARY OFF CACHE BOOL "" FORCE)
    add_subdirectory(${OPUS_DIR} ${CMAKE_BINARY_DIR}/opus EXCLUDE_FROM_ALL)
endif()

if(EXISTS ${APM_DIR}/webrtc/modules/audio_processing/include/audio_processing.h
   OR EXISTS ${APM_DIR}/webrtc-audio-processing-1/webrtc/modules/audio_processing/include/audio_processing.h)
    set(HAVE_APM TRUE)
    # webrtc-audio-processing uses meson upstream; for Android we add a thin
    # CMake glue file under third_party/webrtc-apm-cmake/CMakeLists.txt that
    # lists the required *.cc files (AEC3 + NS + AGC2 + common). See README.
    if(EXISTS ${THIRD_PARTY}/webrtc-apm-cmake/CMakeLists.txt)
        add_subdirectory(${THIRD_PARTY}/webrtc-apm-cmake ${CMAKE_BINARY_DIR}/apm EXCLUDE_FROM_ALL)
    else()
        set(HAVE_APM FALSE)
    endif()
endif()

add_library(aichat_native SHARED jni_bridge.cpp)
target_include_directories(aichat_native PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

if(HAVE_OPUS)
    target_link_libraries(aichat_native PRIVATE opus)
    target_compile_definitions(aichat_native PRIVATE AICHAT_HAVE_OPUS=1)
    target_include_directories(aichat_native PRIVATE ${OPUS_DIR}/include)
endif()

if(HAVE_APM)
    target_link_libraries(aichat_native PRIVATE webrtc_apm)
    target_compile_definitions(aichat_native PRIVATE AICHAT_HAVE_APM=1)
endif()

target_link_libraries(aichat_native PRIVATE log android)
target_compile_options(aichat_native PRIVATE -Wall -Wno-unused-parameter)

# 16 KB page size alignment (required for Google Play targeting Android 15+)
target_link_options(aichat_native PRIVATE "-Wl,-z,max-page-size=16384")
