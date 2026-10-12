cmake_minimum_required(VERSION 3.22.1)

project(zapretdroid C)

# ByeDPI engine, built as a library: its main() is called from JNI on a worker thread.
file(GLOB BYEDPI_SRC ${CMAKE_CURRENT_SOURCE_DIR}/byedpi/*.c)
list(REMOVE_ITEM BYEDPI_SRC ${CMAKE_CURRENT_SOURCE_DIR}/byedpi/win_service.c)

add_library(byedpi SHARED ${BYEDPI_SRC} byedpi-jni.c)
target_include_directories(byedpi PRIVATE byedpi)
target_compile_options(byedpi PRIVATE -std=gnu99 -O2 -Wall -Wextra -Wno-unused -Wno-unused-parameter)
target_compile_definitions(byedpi PRIVATE ANDROID_APP)
target_link_libraries(byedpi PRIVATE log)
# daemon() would fork the app process; the JNI layer replaces it with a stub.
target_link_options(byedpi PRIVATE
    "-Wl,--wrap=daemon"
    "-Wl,-z,max-page-size=16384"
    "-Wl,-z,common-page-size=16384")
