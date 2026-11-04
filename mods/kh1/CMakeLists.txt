cmake_minimum_required(VERSION 3.25)
project(diptych_kh1 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
# Static CRT (/MT) everywhere so the mod DLL and its static libs agree.
set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")
if(MSVC)
  add_compile_options(/utf-8 /permissive-)
endif()
# KINGDOM HEARTS FINAL MIX.exe is a 64-bit process; so is everything we build for Windows.
if(WIN32 AND NOT CMAKE_SIZEOF_VOID_P EQUAL 8)
  message(FATAL_ERROR "the KH1 mod must be 64-bit: configure with -A x64")
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
function(kh1_warnings target)
  if(MSVC)
    target_compile_options(${target} PRIVATE /W4 /WX)
  else()
    target_compile_options(${target} PRIVATE -Wall -Wextra -Wpedantic -Werror)
  endif()
endfunction()

file(GLOB KH1_NET_SRC CONFIGURE_DEPENDS src/net/*.cpp)
file(GLOB KH1_PROTO_SRC CONFIGURE_DEPENDS src/proto/*.cpp)
file(GLOB KH1_CORE_SRC CONFIGURE_DEPENDS src/core/*.cpp)
set(KH1_SERVER_SRC)
if(WIN32)
  file(GLOB KH1_SERVER_SRC CONFIGURE_DEPENDS src/server/*.cpp)
endif()
add_library(kh1_core STATIC ${KH1_NET_SRC} ${KH1_PROTO_SRC} ${KH1_CORE_SRC} ${KH1_SERVER_SRC})
target_include_directories(kh1_core PUBLIC src)
target_link_libraries(kh1_core PUBLIC nlohmann_json::nlohmann_json)
if(WIN32)
  set(KH1_WIN_DEFS WIN32_LEAN_AND_MEAN NOMINMAX _WIN32_WINNT=0x0601)
  target_compile_definitions(kh1_core PRIVATE ${KH1_WIN_DEFS})
  target_link_libraries(kh1_core PUBLIC ws2_32)
endif()
kh1_warnings(kh1_core)

set(FIXTURES_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../protocol/kh1/fixtures" CACHE PATH "KH1 protocol fixtures dir")
if(NOT EXISTS "${FIXTURES_DIR}/hello.json")
  message(FATAL_ERROR "protocol fixtures not found at FIXTURES_DIR=${FIXTURES_DIR}")
endif()
enable_testing()
add_executable(kh1_tests tests/main.cpp tests/test_net.cpp tests/test_envelope.cpp tests/test_gates.cpp)
target_compile_definitions(kh1_tests PRIVATE FIXTURES_DIR="${FIXTURES_DIR}")
target_link_libraries(kh1_tests PRIVATE kh1_core doctest::doctest)
kh1_warnings(kh1_tests)
add_test(NAME kh1_tests COMMAND kh1_tests)
set_tests_properties(kh1_tests PROPERTIES TIMEOUT 60)

if(WIN32)
  add_executable(kh1_server_tests tests/test_server_win.cpp)
  target_compile_definitions(kh1_server_tests PRIVATE FIXTURES_DIR="${FIXTURES_DIR}" ${KH1_WIN_DEFS})
  target_link_libraries(kh1_server_tests PRIVATE kh1_core doctest::doctest)
  kh1_warnings(kh1_server_tests)
  add_test(NAME kh1_server_tests COMMAND kh1_server_tests)
  set_tests_properties(kh1_server_tests PROPERTIES TIMEOUT 120)
endif()

if(WIN32)
  # Windows layer: forwarder, SEH guard, module reader, file log. DllMain lives only in the DLL.
  add_library(kh1_win STATIC src/win/forwarder.cpp src/win/log.cpp src/win/module_reader.cpp src/win/seh.cpp)
  target_compile_definitions(kh1_win PUBLIC ${KH1_WIN_DEFS})
  target_link_libraries(kh1_win PUBLIC kh1_core)
  kh1_warnings(kh1_win)

  # The mod: an x64 dinput8.dll proxy dropped next to KINGDOM HEARTS FINAL MIX.exe.
  add_library(dinput8 SHARED src/win/dllmain.cpp exports.def)
  set_target_properties(dinput8 PROPERTIES OUTPUT_NAME dinput8 PREFIX "")
  target_link_libraries(dinput8 PRIVATE kh1_win)
  kh1_warnings(dinput8)

  add_executable(kh1_win_tests tests/test_forwarder_win.cpp)
  target_link_libraries(kh1_win_tests PRIVATE kh1_win doctest::doctest)
  kh1_warnings(kh1_win_tests)
  add_test(NAME kh1_win_tests COMMAND kh1_win_tests)
  set_tests_properties(kh1_win_tests PROPERTIES TIMEOUT 60)
endif()
