cmake_minimum_required(VERSION 3.22.1)
project(grooverider LANGUAGES CXX)

# Oboe arrives through Prefab from the com.google.oboe:oboe AAR.
find_package(oboe REQUIRED CONFIG)

add_library(grooverider SHARED
        engine/Engine.cpp
        engine/GrainScheduler.cpp
        engine/ModEngine.cpp
        engine/OfflineRenderer.cpp
        dsp/Resampler.cpp
        io/MicRecorder.cpp
        jni/jni_bridge.cpp
)

target_include_directories(grooverider PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
target_compile_features(grooverider PRIVATE cxx_std_17)

target_compile_options(grooverider PRIVATE
        -Wall
        -Wextra
        -Wno-unused-parameter
        # Never enable -ffast-math here: it reassociates float ops, which
        # breaks the bit-identical guarantee the Seed system rests on (spec 3.1).
        -ffp-contract=off
)

target_link_libraries(grooverider PRIVATE
        oboe::oboe
        android
        log
)
