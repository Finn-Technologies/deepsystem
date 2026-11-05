cmake_minimum_required(VERSION 3.20)
project(finn_jni LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Ensure optimized Release compilation with ARM vectorization
set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)
set(CMAKE_CXX_FLAGS_RELEASE "-O3 -DNDEBUG -fvectorize" CACHE STRING "" FORCE)
set(CMAKE_C_FLAGS_RELEASE "-O3 -DNDEBUG -fvectorize" CACHE STRING "" FORCE)

if (EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../CMakeLists.txt")
    get_filename_component(FINN_ROOT_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../.." ABSOLUTE)
elseif (EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/../../../CMakeLists.txt")
    get_filename_component(FINN_ROOT_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../.." ABSOLUTE)
else()
    message(FATAL_ERROR "Could not find DeepSystem root CMakeLists.txt")
endif()

message(STATUS "DeepSystem root directory: ${FINN_ROOT_DIR}")

# Disable unnecessary llama.cpp targets
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON ON CACHE BOOL "" FORCE)
set(LLAMA_BUILD_MTMD ON CACHE BOOL "" FORCE)
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)

add_subdirectory("${FINN_ROOT_DIR}" core EXCLUDE_FROM_ALL)

add_library(finn_jni SHARED
    finn_jni.cpp
)

target_include_directories(finn_jni PRIVATE
    ${FINN_ROOT_DIR}/include
)

find_library(log-lib log)

target_link_libraries(finn_jni
    PRIVATE
        finn_core
        ${log-lib}
)
