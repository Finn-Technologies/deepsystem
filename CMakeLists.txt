cmake_minimum_required(VERSION 3.20)
if (POLICY CMP0135)
    cmake_policy(SET CMP0135 NEW)
endif()

project(finn_deepsystem VERSION 0.1.0 LANGUAGES C CXX)

find_package(Threads REQUIRED)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)

set(CMAKE_EXPORT_COMPILE_COMMANDS ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Configure llama.cpp dependency
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON ON CACHE BOOL "" FORCE)
set(LLAMA_BUILD_MTMD ON CACHE BOOL "" FORCE)

# Embed llama.cpp
add_subdirectory(third_party/llama.cpp EXCLUDE_FROM_ALL)

# Add SQLite amalgamation
add_library(finn_sqlite STATIC third_party/sqlite/sqlite3.c)
target_include_directories(finn_sqlite PUBLIC third_party/sqlite)
target_compile_definitions(finn_sqlite PRIVATE
    SQLITE_THREADSAFE=1
    SQLITE_ENABLE_FTS5
    SQLITE_ENABLE_JSON1
)

# Finn Core Library (Pure C++ shared core)
add_library(finn_core STATIC
    core/src/storage.cpp
    core/src/conversation.cpp
    core/src/llama_engine.cpp
    core/src/finn_c.cpp
)

target_include_directories(finn_core
    PUBLIC
        $<BUILD_INTERFACE:${CMAKE_CURRENT_SOURCE_DIR}/include>
        $<INSTALL_INTERFACE:include>
    PRIVATE
        third_party/llama.cpp/include
        third_party/llama.cpp/ggml/include
        third_party/llama.cpp/tools/mtmd
        third_party/sqlite
)

target_link_libraries(finn_core
    PUBLIC
        llama
        mtmd
        finn_sqlite
    PRIVATE
        Threads::Threads
)

# Finn CLI Host Tool
if (NOT ANDROID)
    add_executable(finn_cli tools/finn_cli/main.cpp)
    target_link_libraries(finn_cli PRIVATE finn_core)
    target_include_directories(finn_cli PRIVATE include)
endif()

# Unit Testing
include(CTest)
if (BUILD_TESTING)
    include(FetchContent)
    FetchContent_Declare(
        googletest
        URL https://github.com/google/googletest/archive/refs/tags/v1.14.0.tar.gz
    )
    set(gtest_force_shared_crt ON CACHE BOOL "" FORCE)
    set(BUILD_GMOCK OFF CACHE BOOL "" FORCE)
    FetchContent_MakeAvailable(googletest)

    add_executable(core_tests
        core/tests/test_fake_engine.cpp
        core/tests/test_storage.cpp
        core/tests/test_c_abi.cpp
    )
    target_link_libraries(core_tests
        PRIVATE
            finn_core
            GTest::gtest_main
    )
    target_include_directories(core_tests PRIVATE include)
    add_test(NAME core_tests COMMAND core_tests)
endif()
