cmake_minimum_required(VERSION 3.25)
project(ttwds LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
# Static CRT (/MT) everywhere so the plugin DLL and its static libs agree.
set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")
if(MSVC)
  add_compile_options(/utf-8 /permissive-)
endif()

include(FetchContent)
FetchContent_Declare(doctest
  URL https://github.com/doctest/doctest/archive/refs/tags/v2.4.11.tar.gz
  URL_HASH SHA256=632ed2c05a7f53fa961381497bf8069093f0d6628c5f26286161fbd32a560186
  DOWNLOAD_EXTRACT_TIMESTAMP TRUE
  SYSTEM)
FetchContent_Declare(nlohmann_json
  URL https://github.com/nlohmann/json/releases/download/v3.11.3/json.tar.xz
  URL_HASH SHA256=d6c65aca6b1ed68e7a182f4757257b107ae403032760ed6ef121c9d55e81757d
  DOWNLOAD_EXTRACT_TIMESTAMP TRUE
  SYSTEM)
# doctest 2.4.11 declares cmake_minimum_required(3.0); CMake 4 refuses that without this.
set(CMAKE_POLICY_VERSION_MINIMUM 3.5)
FetchContent_MakeAvailable(doctest nlohmann_json)
unset(CMAKE_POLICY_VERSION_MINIMUM)

# Warnings are errors on our own targets only (third-party headers are SYSTEM).
function(ttwds_warnings target)
  if(MSVC)
    target_compile_options(${target} PRIVATE /W4 /WX)
  else()
    target_compile_options(${target} PRIVATE -Wall -Wextra -Wpedantic -Werror)
  endif()
endfunction()

file(GLOB TTWDS_NET_SRC CONFIGURE_DEPENDS src/net/*.cpp)
file(GLOB TTWDS_PROTO_SRC CONFIGURE_DEPENDS src/proto/*.cpp)
# Engine-free parts of the game layer (unit-tested on Linux).
set(TTWDS_GAME_PORTABLE_SRC src/game/config.cpp src/game/log.cpp src/game/format.cpp)
set(TTWDS_SERVER_SRC)
if(WIN32)
  file(GLOB TTWDS_SERVER_SRC CONFIGURE_DEPENDS src/server/*.cpp)
endif()
add_library(ttwds_core STATIC ${TTWDS_NET_SRC} ${TTWDS_PROTO_SRC} ${TTWDS_GAME_PORTABLE_SRC}
  ${TTWDS_SERVER_SRC})
target_include_directories(ttwds_core PUBLIC src)
target_link_libraries(ttwds_core PUBLIC nlohmann_json::nlohmann_json)
if(WIN32)
  # PRIVATE: the plugin DLL also includes xNVSE headers, which bring their own <windows.h> setup.
  set(TTWDS_WIN_DEFS WIN32_LEAN_AND_MEAN NOMINMAX _WIN32_WINNT=0x0601)
  target_compile_definitions(ttwds_core PRIVATE ${TTWDS_WIN_DEFS})
  target_link_libraries(ttwds_core PUBLIC ws2_32)
endif()
ttwds_warnings(ttwds_core)

set(FIXTURES_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../protocol/ttw/fixtures" CACHE PATH "protocol fixtures dir")
if(NOT EXISTS "${FIXTURES_DIR}/hello.json")
  message(FATAL_ERROR "protocol fixtures not found at FIXTURES_DIR=${FIXTURES_DIR}")
endif()
enable_testing()
add_executable(ttwds_tests tests/main.cpp tests/test_net.cpp tests/test_proto.cpp
  tests/test_dispatch_fake.cpp tests/test_config.cpp)
target_compile_definitions(ttwds_tests PRIVATE FIXTURES_DIR="${FIXTURES_DIR}"
  INI_PATH="${CMAKE_CURRENT_SOURCE_DIR}/ttw_dualscreen.ini")
target_link_libraries(ttwds_tests PRIVATE ttwds_core doctest::doctest)
ttwds_warnings(ttwds_tests)
add_test(NAME ttwds_tests COMMAND ttwds_tests)

if(WIN32)
  add_executable(ttwds_server_tests tests/test_server_win.cpp)
  target_compile_definitions(ttwds_server_tests PRIVATE FIXTURES_DIR="${FIXTURES_DIR}" ${TTWDS_WIN_DEFS})
  target_link_libraries(ttwds_server_tests PRIVATE ttwds_core doctest::doctest)
  ttwds_warnings(ttwds_server_tests)
  add_test(NAME ttwds_server_tests COMMAND ttwds_server_tests)
  set_tests_properties(ttwds_server_tests PROPERTIES TIMEOUT 120)
endif()

# ---- xNVSE plugin DLL (Windows / MSVC Win32 only) --------------------------------------------
if(WIN32 AND MSVC)
  if(NOT CMAKE_SIZEOF_VOID_P EQUAL 4)
    message(FATAL_ERROR "ttw_dualscreen.dll must be 32-bit: configure with -A Win32")
  endif()
  # Pinned to the latest xNVSE release (gh api repos/xNVSE/NVSE/releases/latest, 2026-11-03).
  set(TTWDS_XNVSE_TAG "6.4.9")
  FetchContent_Declare(xnvse
    URL https://github.com/xNVSE/NVSE/archive/refs/tags/${TTWDS_XNVSE_TAG}.tar.gz
    URL_HASH SHA256=870d1ae5aace69188f4540661b83686e1dfda186f1be1c4d442f298d1a149b97
    DOWNLOAD_EXTRACT_TIMESTAMP TRUE
    SOURCE_SUBDIR no-cmake-project)  # headers + sources only; nothing to add_subdirectory
  FetchContent_MakeAvailable(xnvse)
  set(XNVSE_ROOT "${xnvse_SOURCE_DIR}")
  set(XNVSE_SRC "${XNVSE_ROOT}/nvse/nvse")

  # The minimal source set of nvse_plugin_example.vcxproj (ClCompile list) plus common/.
  set(XNVSE_SOURCES
    containers.cpp GameAPI.cpp GameBSExtraData.cpp GameData.cpp GameExtraData.cpp GameForms.cpp
    GameObjects.cpp GameOSDepend.cpp GameProcess.cpp GameRTTI.cpp GameScript.cpp GameSettings.cpp
    GameTasks.cpp GameTiles.cpp GameTypes.cpp NiTypes.cpp printf.cpp SafeWrite.cpp Utilities.cpp
    utility.cpp)
  list(TRANSFORM XNVSE_SOURCES PREPEND "${XNVSE_SRC}/")
  file(GLOB XNVSE_COMMON_SOURCES "${XNVSE_ROOT}/common/*.cpp")

  # Defines and flags of the example project: RUNTIME build for FNV 1.4.0.525, prefix.h forced.
  set(XNVSE_DEFS RUNTIME RUNTIME_VERSION=0x040020D0 _CRT_SECURE_NO_WARNINGS WIN32 _WINDOWS _USRDLL)
  set(XNVSE_FLAGS /permissive /bigobj "/FInvse/prefix.h")

  add_library(xnvse_sdk STATIC ${XNVSE_SOURCES} ${XNVSE_COMMON_SOURCES})
  target_include_directories(xnvse_sdk SYSTEM PUBLIC "${XNVSE_ROOT}" "${XNVSE_ROOT}/nvse")
  target_compile_definitions(xnvse_sdk PUBLIC ${XNVSE_DEFS})
  target_compile_options(xnvse_sdk PRIVATE ${XNVSE_FLAGS} /W0)

  add_library(ttw_dualscreen SHARED
    src/game/nvse_main.cpp src/game/readers.cpp src/game/commands.cpp src/game/seh.cpp
    src/game/exports.def)
  target_link_libraries(ttw_dualscreen PRIVATE ttwds_core xnvse_sdk)
  target_compile_options(ttw_dualscreen PRIVATE ${XNVSE_FLAGS})
  ttwds_warnings(ttw_dualscreen)
  set_target_properties(ttw_dualscreen PROPERTIES OUTPUT_NAME ttw_dualscreen PREFIX "")
endif()
