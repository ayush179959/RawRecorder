cmake_minimum_required(VERSION 3.22.1)
project("rawcompressor")

add_library(rawcompressor SHARED
    lz4.c
    raw_compressor_jni.cpp
)

target_include_directories(rawcompressor PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

# Force O3 optimization even in Debug builds from Android Studio
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O3 -DNDEBUG")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -DNDEBUG")
set(CMAKE_C_FLAGS_DEBUG "${CMAKE_C_FLAGS_DEBUG} -O3 -DNDEBUG")
set(CMAKE_CXX_FLAGS_DEBUG "${CMAKE_CXX_FLAGS_DEBUG} -O3 -DNDEBUG")

find_library(log-lib log)
target_link_libraries(rawcompressor ${log-lib})
