cmake_minimum_required(VERSION 3.22.1)
project(pinguplay_native C)

add_library(pinguplay_ahb SHARED pinguplay_ahb.c)
target_link_libraries(pinguplay_ahb android nativewindow EGL GLESv2 log dl)
