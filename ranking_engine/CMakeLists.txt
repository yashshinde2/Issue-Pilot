cmake_minimum_required(VERSION 3.10)
project(IssuePilotRankingEngine VERSION 1.0.0 LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED OFF)
set(CMAKE_EXPORT_COMPILE_COMMANDS ON)

add_definitions(-DUTF8PROC_STATIC)

include_directories(include)

add_library(ranking_engine_lib STATIC
    src/ranking_engine.cpp
    src/evaluation.cpp
    src/json_adapter.cpp
    src/vote_affinity.cpp
    src/technology_registry.cpp
    src/utf8proc.c
)

add_executable(issue_pilot_ranking_engine
    src/main.cpp
)
target_link_libraries(issue_pilot_ranking_engine PRIVATE ranking_engine_lib)

add_executable(test_ranking
    tests/test_ranking.cpp
)
target_link_libraries(test_ranking PRIVATE ranking_engine_lib)

add_executable(test_affinity
    tests/test_affinity.cpp
)
target_link_libraries(test_affinity PRIVATE ranking_engine_lib)

enable_testing()
add_test(NAME UnitTests COMMAND test_ranking)
add_test(NAME AffinityTests COMMAND test_affinity)
