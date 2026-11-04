// Windows-only: the dinput8 forwarder with an unloadable target, plus the guarded module reader.
#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include <doctest/doctest.h>

#include <windows.h>

#include <string>

#include "win/forwarder.h"
#include "win/log.h"
#include "win/module_reader.h"
#include "win/seh.h"

namespace {
std::wstring missing_path() { return L"C:\\diptych-does-not-exist\\no_such_dinput8.dll"; }

bool ring_contains(const char* needle) {
    for (const auto& l : diptych::win::log_ring().last(500))
        if (l.find(needle) != std::string::npos) return true;
    return false;
}
}  // namespace

TEST_CASE("forwarder_missing_target") {
    // Must run before any other export call in this process: the target resolves once.
    diptych::win::set_target_path_fn(&missing_path);
    void* out = reinterpret_cast<void*>(1);
    GUID iid{};
    HRESULT hr = DirectInput8Create(GetModuleHandleW(nullptr), 0x0800, iid, &out, nullptr);
    CHECK(static_cast<unsigned long>(hr) == 0x80070015UL);
    CHECK(out == nullptr);
    CHECK(ring_contains("DirectInput8Create"));

    CHECK(DllCanUnloadNow() == S_FALSE);
    void* cls = reinterpret_cast<void*>(1);
    CHECK(DllGetClassObject(iid, iid, &cls) == CLASS_E_CLASSNOTAVAILABLE);
    CHECK(cls == nullptr);
    CHECK(DllRegisterServer() == E_FAIL);
    CHECK(DllUnregisterServer() == E_FAIL);
    CHECK(GetdfDIJoystick() == nullptr);
}

TEST_CASE("system_dinput8_path points into the system directory") {
    std::wstring p = diptych::win::system_dinput8_path();
    wchar_t sys[MAX_PATH];
    UINT n = GetSystemDirectoryW(sys, MAX_PATH);
    REQUIRE(n > 0);
    CHECK(p == std::wstring(sys, n) + L"\\dinput8.dll");
}

TEST_CASE("module_reader: bounds-checked against SizeOfImage") {
    auto base = reinterpret_cast<const unsigned char*>(GetModuleHandleW(nullptr));
    auto size = diptych::win::module_image_size(GetModuleHandleW(nullptr));
    REQUIRE(size > 0x1000);
    auto r = diptych::win::module_byte_reader(GetModuleHandleW(nullptr));
    auto mz = r(0);
    REQUIRE(mz.has_value());
    CHECK(*mz == 'M');
    CHECK(*r(1) == 'Z');
    CHECK(*r(size - 1) == base[size - 1]);
    CHECK_FALSE(r(size).has_value());
    CHECK_FALSE(r(static_cast<uintptr_t>(-1)).has_value());
}

TEST_CASE("seh_read_byte: a fault yields false instead of a crash") {
    unsigned char b = 0xCC;
    CHECK_FALSE(diptych::win::seh_read_byte(reinterpret_cast<const void*>(uintptr_t{8}), &b));
    unsigned char x = 0x5A;
    CHECK(diptych::win::seh_read_byte(&x, &b));
    CHECK(b == 0x5A);
}
