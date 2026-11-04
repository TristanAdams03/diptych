// Windows-only: WinMemory bounds and SEH behaviour on real pages.
#include <doctest/doctest.h>

#include <windows.h>

#include "win/memory.h"

using diptych::win::WinMemory;

namespace {
// Two committed pages, the first read/write and the second no-access, plus a read-only third.
struct Pages {
    size_t page = 0;
    unsigned char* p = nullptr;
    Pages() {
        SYSTEM_INFO si;
        GetSystemInfo(&si);
        page = si.dwPageSize;
        p = static_cast<unsigned char*>(VirtualAlloc(nullptr, 3 * page, MEM_RESERVE | MEM_COMMIT, PAGE_READWRITE));
        REQUIRE(p != nullptr);
        p[0] = 0x11;
        p[1] = 0x22;
        p[2] = 0x33;
        p[3] = 0x44;
        p[2 * page] = 0x5A;
        DWORD old = 0;
        REQUIRE(VirtualProtect(p + page, page, PAGE_NOACCESS, &old));
        REQUIRE(VirtualProtect(p + 2 * page, page, PAGE_READONLY, &old));
    }
    ~Pages() { VirtualFree(p, 0, MEM_RELEASE); }
};
}  // namespace

TEST_CASE("win_memory_reads_and_bounds") {
    Pages pg;
    WinMemory m(pg.p, 3 * pg.page);
    CHECK(m.image_size() == 3 * pg.page);
    CHECK(m.u8(0) == 0x11);
    CHECK(m.u32(0) == 0x44332211u);
    auto b = m.bytes(1, 3);
    REQUIRE(b.has_value());
    CHECK(*b == std::vector<uint8_t>{0x22, 0x33, 0x44});
    CHECK(m.u8(2 * pg.page) == 0x5A);
    // Out of the image.
    CHECK_FALSE(m.u8(3 * pg.page).has_value());
    CHECK_FALSE(m.u32(3 * pg.page - 2).has_value());
    CHECK_FALSE(m.bytes(3 * pg.page - 1, 2).has_value());
    CHECK_FALSE(m.bytes(0, WinMemory::kMaxRead + 1).has_value());
    CHECK_FALSE(m.write_u8(3 * pg.page, 1));
    CHECK_FALSE(m.u8(~uintptr_t(0)).has_value());
    // Inside the image but faulting: SEH turns the access violation into nullopt.
    CHECK_FALSE(m.u8(pg.page).has_value());
    CHECK_FALSE(m.u32(pg.page - 2).has_value());
    CHECK_FALSE(m.bytes(pg.page - 1, 2).has_value());
    // Still usable afterwards (per-reader disabling is DisablingMemory's job).
    CHECK(m.u8(0) == 0x11);
}

TEST_CASE("win_memory_writes") {
    Pages pg;
    WinMemory m(pg.p, 3 * pg.page);
    CHECK(m.write_u8(1, 0xAB));
    CHECK(pg.p[1] == 0xAB);
    CHECK_FALSE(m.write_u8(pg.page, 1));       // no-access page
    CHECK_FALSE(m.write_u8(2 * pg.page, 1));   // read-only page
    CHECK(pg.p[2 * pg.page] == 0x5A);
}

TEST_CASE("win_memory_for_module") {
    WinMemory m = WinMemory::for_module(GetModuleHandleW(nullptr));
    REQUIRE(m.image_size() > 0x1000);
    CHECK(m.u8(0) == 'M');
    CHECK(m.u8(1) == 'Z');
    CHECK_FALSE(m.u8(m.image_size()).has_value());
    WinMemory none(nullptr, 0);
    CHECK_FALSE(none.u8(0).has_value());
    CHECK_FALSE(none.write_u8(0, 0));
}
