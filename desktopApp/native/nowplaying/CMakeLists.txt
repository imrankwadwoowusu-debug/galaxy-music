# macOS Now Playing / Control Center / media keys via MediaPlayer.framework.
cmake_minimum_required(VERSION 3.16)
project(bitchord_nowplaying LANGUAGES CXX OBJCXX)

foreach(suffix "" _DEBUG _RELEASE _RELWITHDEBINFO _MINSIZEREL)
    set(CMAKE_LIBRARY_OUTPUT_DIRECTORY${suffix} ${CMAKE_BINARY_DIR})
    set(CMAKE_RUNTIME_OUTPUT_DIRECTORY${suffix} ${CMAKE_BINARY_DIR})
endforeach()

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_OBJCXX_STANDARD 17)
set(CMAKE_OBJCXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

add_library(bitchord_nowplaying SHARED
    ${CMAKE_CURRENT_SOURCE_DIR}/nowplaying_jni.mm
)

set_source_files_properties(
    ${CMAKE_CURRENT_SOURCE_DIR}/nowplaying_jni.mm
    PROPERTIES COMPILE_FLAGS "-fobjc-arc"
)

if(DEFINED ENV{JAVA_HOME})
    target_include_directories(bitchord_nowplaying PRIVATE
        $ENV{JAVA_HOME}/include
        $ENV{JAVA_HOME}/include/darwin
    )
else()
    find_package(JNI REQUIRED)
    target_include_directories(bitchord_nowplaying PRIVATE ${JNI_INCLUDE_DIRS})
endif()

target_compile_options(bitchord_nowplaying PRIVATE -O2)
target_link_libraries(bitchord_nowplaying PRIVATE
    "-framework MediaPlayer"
    "-framework Foundation"
    "-framework AppKit"
)
