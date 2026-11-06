cmake_minimum_required(VERSION 3.20)
project(finn_linux LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)

# Locate DeepSystem root (this file lives in linux/)
if (EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/../CMakeLists.txt")
    get_filename_component(FINN_ROOT_DIR "${CMAKE_CURRENT_SOURCE_DIR}/.." ABSOLUTE)
else()
    message(FATAL_ERROR "Could not find DeepSystem root CMakeLists.txt")
endif()
message(STATUS "DeepSystem root: ${FINN_ROOT_DIR}")

# Disable llama extras / tests when pulling in the core
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON ON CACHE BOOL "" FORCE)
set(LLAMA_BUILD_MTMD ON CACHE BOOL "" FORCE)
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)

add_subdirectory("${FINN_ROOT_DIR}" finn_core_build EXCLUDE_FROM_ALL)

# Optional GTK4 / libadwaita / WebKitGTK detection
find_package(PkgConfig QUIET)
set(FINN_HAVE_GTK4 OFF)
set(FINN_HAVE_DBUS OFF)
set(FINN_HAVE_WEBKIT OFF)
if (PkgConfig_FOUND)
    pkg_check_modules(GTK4 QUIET gtk4)
    pkg_check_modules(ADW QUIET libadwaita-1)
    pkg_check_modules(DBUS QUIET dbus-1)
    pkg_check_modules(WEBKIT QUIET webkit2gtk-4.1)
    if (GTK4_FOUND AND ADW_FOUND)
        set(FINN_HAVE_GTK4 ON)
    endif()
    if (DBUS_FOUND)
        set(FINN_HAVE_DBUS ON)
    endif()
    if (WEBKIT_FOUND)
        set(FINN_HAVE_WEBKIT ON)
    endif()
endif()

# Common sources
set(LINUX_COMMON_SRC
    src/linux_sandbox.cpp
    src/desktop_assistant.cpp
)

# Executable
add_executable(finn_linux
    src/main.cpp
    ${LINUX_COMMON_SRC}
)
if (FINN_HAVE_GTK4)
    target_sources(finn_linux PRIVATE src/gtk_ui.cpp)
    target_compile_definitions(finn_linux PRIVATE FINN_HAVE_GTK4=1)
    target_include_directories(finn_linux PRIVATE ${GTK4_INCLUDE_DIRS} ${ADW_INCLUDE_DIRS})
    target_link_libraries(finn_linux PRIVATE ${GTK4_LIBRARIES} ${ADW_LIBRARIES})
    target_link_directories(finn_linux PRIVATE ${GTK4_LIBRARY_DIRS} ${ADW_LIBRARY_DIRS})
    if (FINN_HAVE_WEBKIT)
        target_compile_definitions(finn_linux PRIVATE FINN_HAVE_WEBKIT=1)
        target_include_directories(finn_linux PRIVATE ${WEBKIT_INCLUDE_DIRS})
        target_link_libraries(finn_linux PRIVATE ${WEBKIT_LIBRARIES})
        target_link_directories(finn_linux PRIVATE ${WEBKIT_LIBRARY_DIRS})
    endif()
endif()
if (FINN_HAVE_DBUS)
    target_compile_definitions(finn_linux PRIVATE FINN_HAVE_DBUS=1)
    target_include_directories(finn_linux PRIVATE ${DBUS_INCLUDE_DIRS})
    target_link_libraries(finn_linux PRIVATE ${DBUS_LIBRARIES})
    target_link_directories(finn_linux PRIVATE ${DBUS_LIBRARY_DIRS})
endif()

target_include_directories(finn_linux PRIVATE
    ${FINN_ROOT_DIR}/include
    ${CMAKE_CURRENT_SOURCE_DIR}/src
)
target_link_libraries(finn_linux PRIVATE finn_core)
target_compile_definitions(finn_linux PRIVATE
    FINN_LINUX_APP=1
)

# Test
add_executable(linux_tests
    src/linux_tests.cpp
    ${LINUX_COMMON_SRC}
)
target_include_directories(linux_tests PRIVATE
    ${FINN_ROOT_DIR}/include
    ${CMAKE_CURRENT_SOURCE_DIR}/src
)
target_link_libraries(linux_tests PRIVATE finn_core)
if (FINN_HAVE_DBUS)
    target_compile_definitions(linux_tests PRIVATE FINN_HAVE_DBUS=1)
    target_include_directories(linux_tests PRIVATE ${DBUS_INCLUDE_DIRS})
    target_link_libraries(linux_tests PRIVATE ${DBUS_LIBRARIES})
    target_link_directories(linux_tests PRIVATE ${DBUS_LIBRARY_DIRS})
endif()
set(BUILD_TESTING ON CACHE BOOL "" FORCE)
include(CTest)
enable_testing()
add_test(NAME linux_tests COMMAND linux_tests)
