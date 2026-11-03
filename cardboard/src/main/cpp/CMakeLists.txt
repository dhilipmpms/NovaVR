# NovaVR - trimmed build of the Google Cardboard SDK (Apache-2.0) plus the
# NovaVR JNI bridge. Only the Android + OpenGL ES paths are compiled; Vulkan,
# Unity, iOS and the camera based QR-code scanner are intentionally excluded.

cmake_minimum_required(VERSION 3.22.1)
project(novavr_cardboard VERSION 1.0.0 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED True)
add_compile_options(-Wall -Wextra -Wno-unused-parameter)

find_library(ANDROID_LIB android)
find_library(GLESv2_LIB GLESv2)
find_library(GLESv3_LIB GLESv3)
find_library(LOG_LIB log)

set(SDK_DIR ${CMAKE_CURRENT_SOURCE_DIR}/sdk)

file(GLOB cardboard_srcs
    "${SDK_DIR}/*.cc"
    "${SDK_DIR}/qrcode/cardboard_v1/*.cc"
    "${SDK_DIR}/qrcode/android/*.cc"
    "${SDK_DIR}/sensors/*.cc"
    "${SDK_DIR}/sensors/android/*.cc"
    "${SDK_DIR}/jni_utils/android/*.cc"
    "${SDK_DIR}/util/*.cc"
    "${SDK_DIR}/screen_params/android/*.cc"
    "${SDK_DIR}/device_params/android/*.cc"
    "${SDK_DIR}/rendering/opengl_*.cc"
)

add_library(novavr_cardboard SHARED
    ${cardboard_srcs}
    novavr_cardboard_jni.cc
)

target_include_directories(novavr_cardboard PRIVATE ${SDK_DIR})

target_link_libraries(novavr_cardboard
    PRIVATE
    ${ANDROID_LIB}
    ${GLESv2_LIB}
    ${GLESv3_LIB}
    ${LOG_LIB}
)

# Only export JNI entry points; everything else stays hidden so the linker can
# strip unused code.
set_property(TARGET novavr_cardboard APPEND_STRING PROPERTY LINK_FLAGS
    " -Wl,--version-script=\"${CMAKE_CURRENT_SOURCE_DIR}/exports.lds\" -Wl,--gc-sections")
set_property(TARGET novavr_cardboard APPEND PROPERTY LINK_DEPENDS
    "${CMAKE_CURRENT_SOURCE_DIR}/exports.lds")
# 16 KB page size support (required for new Play submissions, harmless otherwise).
target_link_options(novavr_cardboard PRIVATE "-Wl,-z,max-page-size=16384")
