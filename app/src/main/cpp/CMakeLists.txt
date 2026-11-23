cmake_minimum_required(VERSION 3.22.1)
project("jarvis_llama_jni" C CXX)

set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -march=armv8-a -mtune=cortex-a73 -O3 -DNDEBUG -fPIC -fvectorize")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -march=armv8-a -mtune=cortex-a73 -O3 -DNDEBUG -fPIC -fvectorize")

set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_MTMD ON CACHE BOOL "" FORCE)
set(MTMD_VIDEO OFF CACHE BOOL "" FORCE)
# The CPU code is built once per ARM feature level (plain NEON, dot product, fp16, int8 matmul, ...) as
# libggml-cpu-android_*.so; at run time ggml loads the best one this processor supports.
set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL ON CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS ON CACHE BOOL "" FORCE)

set(LLAMA_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/llama.cpp")
# Jarvis builds llama.cpp v1.0.0 plus the changes in patches/llama.cpp; stop early if they are missing.
file(READ "${LLAMA_DIR}/ggml/src/ggml-cpu/repack.cpp" JARVIS_REPACK_SRC)
file(READ "${LLAMA_DIR}/ggml/src/ggml-cpu/arch/arm/quants.c" JARVIS_QUANTS_SRC)
string(FIND "${JARVIS_REPACK_SRC}" "GGML_REPACK_FILE_DIR" JARVIS_PATCH_0002)
string(FIND "${JARVIS_QUANTS_SRC}" "GGML_Q4_0_NEON_2X2" JARVIS_PATCH_0003)
if(JARVIS_PATCH_0002 EQUAL -1 OR JARVIS_PATCH_0003 EQUAL -1)
    message(FATAL_ERROR "third_party/llama.cpp is missing Jarvis's patches. Run scripts/setup-llama.sh first.")
endif()
add_subdirectory("${LLAMA_DIR}" llama.cpp)

add_library(llama_jni SHARED llama_jni.cpp simd_audio.cpp)
target_include_directories(llama_jni PRIVATE
    "${LLAMA_DIR}/include"
    "${LLAMA_DIR}/ggml/include"
    "${LLAMA_DIR}/tools/mtmd"
)
target_link_libraries(llama_jni PRIVATE mtmd llama ggml android log)
