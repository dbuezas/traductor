cmake_minimum_required(VERSION 3.22)
project(traductor C CXX)

# Debug builds of ggml are far too slow to be usable.
set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)
set(CMAKE_C_FLAGS   "${CMAKE_C_FLAGS} -O3 -march=armv8.2-a+dotprod+fp16")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -march=armv8.2-a+dotprod+fp16")

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP       OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE       OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE    ON  CACHE BOOL "" FORCE)

# My changes to whisper.cpp (patches/whisper.patch) are applied to the submodule on the first
# build. "git apply --reverse --check" succeeding means they are already applied.
set(WHISPER_PATCH ${CMAKE_CURRENT_SOURCE_DIR}/../../../../patches/whisper.patch)
execute_process(COMMAND git apply --reverse --check ${WHISPER_PATCH}
                WORKING_DIRECTORY ${WHISPER_DIR} RESULT_VARIABLE PATCHED ERROR_QUIET)
if (NOT PATCHED EQUAL 0)
    execute_process(COMMAND git apply ${WHISPER_PATCH} WORKING_DIRECTORY ${WHISPER_DIR} RESULT_VARIABLE APPLY_FAILED)
    if (APPLY_FAILED)
        message(FATAL_ERROR "patches/whisper.patch does not apply to ${WHISPER_DIR}")
    endif()
endif()

# llama.cpp first: it defines the ggml target, which whisper.cpp then reuses.
add_subdirectory(${LLAMA_DIR} llama)
add_subdirectory(${WHISPER_DIR} whisper)

add_library(traductor SHARED native.cpp)
target_link_libraries(traductor whisper llama android log)
