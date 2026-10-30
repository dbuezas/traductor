# The benchmark binary: the app's native code (same flags, same patched whisper.cpp) as a
# command line program for the phone. Built by run.sh.
cmake_minimum_required(VERSION 3.22)
project(bench C CXX)

add_subdirectory(../../app/src/main/cpp app)
add_executable(bench bench.cpp)
target_compile_options(bench PRIVATE -O3)
target_link_libraries(bench whisper llama android log)
