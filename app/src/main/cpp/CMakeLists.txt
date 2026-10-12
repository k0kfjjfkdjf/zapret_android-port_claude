cmake_minimum_required(VERSION 3.22.1)

project(zapretdroid C)

# ByeDPI engine, built as a library: its main() is called from JNI on a worker thread.
file(GLOB BYEDPI_SRC ${CMAKE_CURRENT_SOURCE_DIR}/engine/*.c)

add_library(byedpi SHARED ${BYEDPI_SRC} byedpi-jni.c native-log.c)
target_include_directories(byedpi PRIVATE engine)
target_compile_options(byedpi PRIVATE -std=gnu99 -O2 -Wall -Wextra -Wno-unused -Wno-unused-parameter)
# Without ANDROID_APP ByeDPI logs to stderr, honours --debug and skips per-packet logging at level 0.
# native-log.c collects stderr into the session log.
target_link_libraries(byedpi PRIVATE log)
# daemon() would fork the app process; the JNI layer replaces it with a stub.
target_link_options(byedpi PRIVATE
    "-Wl,--wrap=daemon"
    "-Wl,-z,max-page-size=16384"
    "-Wl,-z,common-page-size=16384")
