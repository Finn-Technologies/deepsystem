cmake_minimum_required(VERSION 3.20)
project(finn_napi LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Locate DeepSystem root (openharmony/entry/src/main/cpp -> root)
get_filename_component(FINN_ROOT_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../.." ABSOLUTE)
message(STATUS "DeepSystem root: ${FINN_ROOT_DIR}")

# Locate OpenHarmony / Node NAPI headers (optional for host builds)
find_path(NAPI_INCLUDE_DIR
    NAMES napi/native_api.h
    PATHS
        $ENV{OHOS_SDK_HOME}/native/sysroot/usr/include
        $ENV{OHOS_SDK}/native/sysroot/usr/include
        /opt/ohos-sdk/native/sysroot/usr/include
        /usr/local/include
        /usr/include
)
if (NOT NAPI_INCLUDE_DIR)
    message(WARNING "napi/native_api.h not found; skipping finn_napi build (OpenHarmony SDK required).")
    return()
endif()

# Disable llama extras
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON ON CACHE BOOL "" FORCE)
set(LLAMA_BUILD_MTMD ON CACHE BOOL "" FORCE)
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)

add_subdirectory("${FINN_ROOT_DIR}" finn_core_build EXCLUDE_FROM_ALL)

add_library(finn_napi SHARED finn_napi.cpp)
target_include_directories(finn_napi PRIVATE
    ${NAPI_INCLUDE_DIR}
    ${FINN_ROOT_DIR}/include
)
target_link_libraries(finn_napi PRIVATE finn_core)
set_target_properties(finn_napi PROPERTIES
    OUTPUT_NAME "finn_napi"
    PREFIX "lib"
)
