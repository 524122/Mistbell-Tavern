cmake_minimum_required(VERSION 3.22.1)

project(litehtmlbridge)

# litehtml v0.10 (9bc84b8)，BSD-3-Clause；捆绑 gumbo 为 Apache-2.0。
# 布局和 CSS 用上游。缺的 CSS 再改 third_party/litehtml。像素由 AndroidContainer 画。
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(LITEHTML_BUILD_TESTING OFF CACHE BOOL "" FORCE)

add_subdirectory(
    ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/litehtml
    ${CMAKE_CURRENT_BINARY_DIR}/litehtml
)

add_library(litehtmlbridge SHARED
    android_container.cpp
    litehtml_jni.cpp
)

target_compile_features(litehtmlbridge PRIVATE cxx_std_17)
target_link_libraries(litehtmlbridge PRIVATE litehtml log)
