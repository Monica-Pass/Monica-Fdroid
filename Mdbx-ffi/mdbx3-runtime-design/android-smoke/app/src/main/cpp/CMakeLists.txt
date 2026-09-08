cmake_minimum_required(VERSION 3.22.1)
project(mdbx3_smoke)

add_library(mdbx3_smoke SHARED smoke.cpp)
target_link_libraries(mdbx3_smoke log dl)
