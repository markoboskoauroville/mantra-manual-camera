cmake_minimum_required(VERSION 3.22.1)
project("ndi_bridge")

add_library(ndi_bridge SHARED
        ndi_bridge.cpp
        ndi_recv_bridge.cpp)

# NDI Advanced SDK for Android. The headers live in cpp/ndi/include and the
# runtime in jniLibs/<abi>/libndi_advanced.so — note the name, the Android
# build ships libndi_advanced.so rather than libndi.so.
target_include_directories(ndi_bridge PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/ndi/include)

add_library(ndi_advanced SHARED IMPORTED)
set_target_properties(ndi_advanced PROPERTIES
        IMPORTED_LOCATION ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libndi_advanced.so)

find_library(log-lib log)

target_link_libraries(ndi_bridge ndi_advanced ${log-lib})

# v148: 16 KB pages (Android 15+ asks it of every app: "This app isn't 16 KB compatible ... libndi_bridge.so: LOAD
# segment not aligned"). NDK r27 links at 4 KB unless told; libndi_advanced.so already ships at 0x4000.
target_link_options(ndi_bridge PRIVATE "-Wl,-z,max-page-size=16384")

# v160: THE TRACKER. libmv's TrackRegion (Blender's tracker, MIT) with Ceres Solver (BSD-3) and Eigen (MPL-2, built
# MPL2-only), in third_party/; see third_party/libmv/mantra_tracker.h. Ceres as Android builds it: miniglog, no
# gflags, SuiteSparse, LAPACK, CUDA, tests or examples.
set(EIGEN3_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/third_party/eigen)
set(Eigen3_DIR ${CMAKE_CURRENT_SOURCE_DIR}/third_party/eigen/cmake)
foreach(opt GFLAGS SUITESPARSE LAPACK USE_CUDA ACCELERATESPARSE EIGENSPARSE EIGENMETIS SCHUR_SPECIALIZATIONS
            BUILD_TESTING BUILD_EXAMPLES BUILD_BENCHMARKS BUILD_DOCUMENTATION PROVIDE_UNINSTALL_TARGET EXPORT_BUILD_DIR)
  set(${opt} OFF CACHE BOOL "" FORCE)
endforeach()
set(MINIGLOG ON CACHE BOOL "" FORCE)
set(MINIGLOG_MAX_LOG_LEVEL -2 CACHE STRING "" FORCE)
add_compile_definitions(EIGEN_MPL2_ONLY)
add_subdirectory(third_party/ceres EXCLUDE_FROM_ALL)
set(LIBMV ${CMAKE_CURRENT_SOURCE_DIR}/third_party/libmv)
add_library(mantra_tracker SHARED
        mantra_tracker_jni.cpp
        ${LIBMV}/mantra_tracker.cc
        ${LIBMV}/libmv/tracking/track_region.cc
        ${LIBMV}/libmv/image/convolve.cc
        ${LIBMV}/libmv/image/array_nd.cc
        ${LIBMV}/libmv/multiview/homography.cc
        ${LIBMV}/libmv/multiview/conditioning.cc
        ${LIBMV}/libmv/multiview/projection.cc
        ${LIBMV}/libmv/numeric/numeric.cc
        ${LIBMV}/libmv/base/aligned_malloc.cc)
target_include_directories(mantra_tracker PRIVATE ${LIBMV}
        ${CMAKE_CURRENT_SOURCE_DIR}/third_party/ceres/internal/ceres/miniglog)
target_compile_definitions(mantra_tracker PRIVATE MAX_LOG_LEVEL=-2)
target_compile_options(mantra_tracker PRIVATE -O3)
target_link_libraries(mantra_tracker Ceres::ceres Eigen3::Eigen ${log-lib})
target_link_options(mantra_tracker PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,--strip-all")
