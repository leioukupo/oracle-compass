cmake_minimum_required(VERSION 3.18)
project(bagua_tts C CXX)

set(ESPEAK_INCLUDE ${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/espeak-ng/src/include)
set(PRE ${CMAKE_CURRENT_LIST_DIR}/prebuilt/${ANDROID_ABI})

add_library(bagua_tts SHARED bagua_tts.cpp)
target_include_directories(bagua_tts PRIVATE ${ESPEAK_INCLUDE})
target_link_libraries(bagua_tts
        ${PRE}/libespeak-ng.a
        ${PRE}/libspeechPlayer.a
        ${PRE}/libucd.a
        log m)
set_target_properties(bagua_tts PROPERTIES LINKER_LANGUAGE CXX)

# 屏幕推流 RGBA->YUV 原生转换（Java 循环在本机过慢）
add_library(truthyuv SHARED yuv_convert.c)
target_link_libraries(truthyuv log m)
