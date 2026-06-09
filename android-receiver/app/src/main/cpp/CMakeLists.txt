cmake_minimum_required(VERSION 3.22.1)
project(audioshare)

set(CMAKE_CXX_STANDARD 17)

# --- Opus (prebuilt via prefab or manual path) ---
# Option A: use opus via Android dependency (add to build.gradle: implementation 'com.arthenica:opus-android:...')
# Option B: build from source in src/main/cpp/opus/
# For MVP we assume it's available via find_package or manual include.

# If you placed libopus prebuilt here:
set(OPUS_INCLUDE "${CMAKE_SOURCE_DIR}/opus/include")
set(OPUS_LIB     "${CMAKE_SOURCE_DIR}/opus/lib/${ANDROID_ABI}/libopus.a")

add_library(audioshare SHARED
    audioshare_bridge.cpp
)

target_include_directories(audioshare PRIVATE
    ${OPUS_INCLUDE}
)

target_link_libraries(audioshare
    ${OPUS_LIB}
    aaudio
    log
    android
)
