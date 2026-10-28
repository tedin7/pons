cmake_minimum_required(VERSION 3.22.1)
project(pons_native C)

add_library(pons_ahb SHARED pons_ahb.c)
target_link_libraries(pons_ahb android nativewindow EGL GLESv2 log dl)
