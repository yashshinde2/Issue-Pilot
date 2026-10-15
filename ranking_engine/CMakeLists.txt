cmake_minimum_required(VERSION 3.10)
project(IssuePilotRankingEngine VERSION 1.0.0 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 14)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_EXPORT_COMPILE_COMMANDS ON)

include_directories(include)

add_library(ranking_engine_lib STATIC
    src/ranking_engine.cpp
    src/evaluation.cpp
    src/json_adapter.cpp
)

add_executable(issue_pilot_ranking_engine
    src/main.cpp
)
target_link_libraries(issue_pilot_ranking_engine PRIVATE ranking_engine_lib)

add_executable(test_ranking
    tests/test_ranking.cpp
)
target_link_libraries(test_ranking PRIVATE ranking_engine_lib)

enable_testing()
add_test(NAME UnitTests COMMAND test_ranking)
