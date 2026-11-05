#include <doctest/doctest.h>

#include <cstring>
#include <set>
#include <string>
#include <vector>

#include "core/pe_imports.h"
#include "core/xinput_inject.h"

using namespace diptych::core;

TEST_CASE("ps2_to_xinput: byte 1 face buttons, shoulders and triggers") {
    CHECK(ps2_to_xinput(1, 0x40) == PadInject{kXiA, 0, 0});
    CHECK(ps2_to_xinput(1, 0x20) == PadInject{kXiB, 0, 0});
    CHECK(ps2_to_xinput(1, 0x80) == PadInject{kXiX, 0, 0});
    CHECK(ps2_to_xinput(1, 0x10) == PadInject{kXiY, 0, 0});
    CHECK(ps2_to_xinput(1, 0x04) == PadInject{kXiLeftShoulder, 0, 0});
    CHECK(ps2_to_xinput(1, 0x08) == PadInject{kXiRightShoulder, 0, 0});
    CHECK(ps2_to_xinput(1, 0x01) == PadInject{0, 255, 0});
    CHECK(ps2_to_xinput(1, 0x02) == PadInject{0, 0, 255});
    CHECK(ps2_to_xinput(1, 0xFF) ==
          PadInject{static_cast<uint16_t>(kXiA | kXiB | kXiX | kXiY | kXiLeftShoulder | kXiRightShoulder), 255, 255});
    CHECK(ps2_to_xinput(1, 0) == PadInject{});
}

TEST_CASE("ps2_to_xinput: byte 0 d-pad, select, sticks; start refused") {
    CHECK(ps2_to_xinput(0, 0x01) == PadInject{kXiBack, 0, 0});
    CHECK(ps2_to_xinput(0, 0x02) == PadInject{kXiLeftThumb, 0, 0});
    CHECK(ps2_to_xinput(0, 0x04) == PadInject{kXiRightThumb, 0, 0});
    CHECK(ps2_to_xinput(0, 0x10) == PadInject{kXiDpadUp, 0, 0});
    CHECK(ps2_to_xinput(0, 0x20) == PadInject{kXiDpadRight, 0, 0});
    CHECK(ps2_to_xinput(0, 0x40) == PadInject{kXiDpadDown, 0, 0});
    CHECK(ps2_to_xinput(0, 0x80) == PadInject{kXiDpadLeft, 0, 0});
    CHECK_FALSE(ps2_to_xinput(0, 0x08).has_value());
    CHECK_FALSE(ps2_to_xinput(0, 0xFF).has_value());
    CHECK(ps2_to_xinput(0, 0xF7).has_value());
    // No mapping ever produces START.
    for (int b = 0; b < 2; ++b)
        for (int m = 0; m < 256; ++m)
            if (auto p = ps2_to_xinput(b, m)) CHECK((p->buttons & kXiStart) == 0);
}

TEST_CASE("ps2_to_xinput: other bytes and masks out of range") {
    for (int b : {-1, 2, 3, 13, 17, 31}) CHECK_FALSE(ps2_to_xinput(b, 0x40).has_value());
    CHECK_FALSE(ps2_to_xinput(1, 256).has_value());
    CHECK_FALSE(ps2_to_xinput(1, -1).has_value());
}

TEST_CASE("InjectState: passthrough when idle, ORs while set, packet changes with every change") {
    InjectState s;
    uint32_t packet = 100;
    uint16_t buttons = kXiDpadUp;
    uint8_t lt = 7, rt = 0;
    s.apply(packet, buttons, lt, rt);
    CHECK(packet == 100);
    CHECK(buttons == kXiDpadUp);
    CHECK(lt == 7);
    CHECK_FALSE(s.active());

    s.set(PadInject{kXiA, 0, 255});
    CHECK(s.active());
    CHECK(s.generation() == 1);
    packet = 100, buttons = kXiDpadUp, lt = 7, rt = 0;
    s.apply(packet, buttons, lt, rt);
    CHECK(packet == 101);  // the real packet did not change, ours does
    CHECK(buttons == (kXiDpadUp | kXiA));
    CHECK(lt == 7);  // untouched trigger keeps the real value
    CHECK(rt == 255);

    s.set(PadInject{kXiA, 0, 255});  // unchanged: no bump
    CHECK(s.generation() == 1);

    s.clear();
    CHECK_FALSE(s.active());
    CHECK(s.generation() == 2);
    packet = 100, buttons = 0, lt = 0, rt = 0;
    s.apply(packet, buttons, lt, rt);
    CHECK(packet == 102);  // the release is a new packet too
    CHECK(buttons == 0);
    CHECK(rt == 0);
    s.clear();
    CHECK(s.generation() == 2);
}

TEST_CASE("InjectState: no-pad note is reported once") {
    InjectState s;
    CHECK_FALSE(s.take_no_pad_once());
    s.note_no_pad();
    CHECK(s.take_no_pad_once());
    s.note_no_pad();
    CHECK_FALSE(s.take_no_pad_once());
}

namespace {
// A mapped PE32+ image in a byte buffer (RVA == offset).
struct FakePe {
    std::vector<uint8_t> img = std::vector<uint8_t>(0x2000, 0);
    std::set<uint32_t> faults;  // RVAs whose reads fail, like an SEH-caught fault

    template <class T>
    void put(uint32_t rva, T v) {
        std::memcpy(img.data() + rva, &v, sizeof v);
    }
    void str(uint32_t rva, const char* s) { std::memcpy(img.data() + rva, s, std::strlen(s) + 1); }
    void desc(int i, uint32_t oft, uint32_t name, uint32_t ft) {
        const uint32_t d = 0x1000 + 20u * static_cast<uint32_t>(i);
        put<uint32_t>(d, oft);
        put<uint32_t>(d + 12, name);
        put<uint32_t>(d + 16, ft);
    }
    FakePe() {
        put<uint16_t>(0, 0x5A4D);
        put<uint32_t>(0x3C, 0x80);
        put<uint32_t>(0x80, 0x00004550);
        put<uint16_t>(0x98, 0x20B);
        put<uint32_t>(0x98 + 108, 16);
        put<uint32_t>(0x98 + 120, 0x1000);
        put<uint32_t>(0x98 + 124, 60);
        desc(0, 0x1300, 0x1200, 0x1400);
        str(0x1200, "KERNEL32.dll");
        put<uint64_t>(0x1300, 0x1800);  // by name
        put<uint64_t>(0x1400, 0x7FF000001111ull);
        desc(1, 0x1500, 0x1220, 0x1600);
        str(0x1220, "xinput1_4.DLL");
        put<uint64_t>(0x1500, (1ull << 63) | 3);
        put<uint64_t>(0x1508, (1ull << 63) | 2);
        put<uint64_t>(0x1600, 0x7FF000003333ull);
        put<uint64_t>(0x1608, 0x7FF000002222ull);
    }
    ImageReader reader() {
        return [this](uint32_t rva, void* out, size_t n) {
            if (rva >= img.size() || n > img.size() - rva) return false;
            for (size_t i = 0; i < n; ++i)
                if (faults.count(rva + static_cast<uint32_t>(i))) return false;
            std::memcpy(out, img.data() + rva, n);
            return true;
        };
    }
};
}  // namespace

TEST_CASE("find_import_by_ordinal finds XINPUT1_4 ordinal 2 (case-insensitive)") {
    FakePe pe;
    const ImportSlot s = find_import_by_ordinal(pe.reader(), "XINPUT1_4.dll", 2);
    REQUIRE(s.found);
    CHECK(s.iatRva == 0x1608);
    const ImportSlot s3 = find_import_by_ordinal(pe.reader(), "XINPUT1_4.dll", 3);
    REQUIRE(s3.found);
    CHECK(s3.iatRva == 0x1600);
}

TEST_CASE("find_import_by_ordinal: missing module, missing ordinal, name imports ignored") {
    FakePe pe;
    CHECK_FALSE(find_import_by_ordinal(pe.reader(), "XINPUT1_3.dll", 2).found);
    CHECK(find_import_by_ordinal(pe.reader(), "XINPUT1_3.dll", 2).why == "XINPUT1_3.dll not imported");
    const ImportSlot s = find_import_by_ordinal(pe.reader(), "XINPUT1_4.dll", 4);
    CHECK_FALSE(s.found);
    CHECK(s.why == "XINPUT1_4.dll ordinal 4 not imported");
    // A by-name thunk whose low 16 bits happen to be 2 is not an ordinal import.
    pe.put<uint64_t>(0x1500, 0x0002);
    pe.put<uint64_t>(0x1508, 0);
    CHECK_FALSE(find_import_by_ordinal(pe.reader(), "XINPUT1_4.dll", 2).found);
    // A prefix of the name does not match.
    CHECK_FALSE(find_import_by_ordinal(pe.reader(), "XINPUT1_4", 2).found);
}

TEST_CASE("find_import_by_ordinal: a second descriptor for the same DLL is searched") {
    FakePe pe;
    pe.put<uint64_t>(0x1508, 0);  // first xinput descriptor: only ordinal 3
    pe.desc(2, 0x1700, 0x1240, 0x1780);
    pe.str(0x1240, "XINPUT1_4.dll");
    pe.put<uint64_t>(0x1700, (1ull << 63) | 2);
    const ImportSlot s = find_import_by_ordinal(pe.reader(), "XINPUT1_4.dll", 2);
    REQUIRE(s.found);
    CHECK(s.iatRva == 0x1780);
}

TEST_CASE("find_import_by_ordinal: faults and malformed headers end the walk with a reason") {
    SUBCASE("no MZ") {
        FakePe pe;
        pe.put<uint16_t>(0, 0);
        CHECK(find_import_by_ordinal(pe.reader(), "XINPUT1_4.dll", 2).why == "no MZ header");
    }
    SUBCASE("PE32, not PE32+") {
        FakePe pe;
        pe.put<uint16_t>(0x98, 0x10B);
        CHECK(find_import_by_ordinal(pe.reader(), "XINPUT1_4.dll", 2).why == "not a PE32+ image");
    }
    SUBCASE("faulting descriptor") {
        FakePe pe;
        pe.faults.insert(0x1014);
        const ImportSlot s = find_import_by_ordinal(pe.reader(), "XINPUT1_4.dll", 2);
        CHECK_FALSE(s.found);
        CHECK(s.why == "import descriptor unreadable");
    }
    SUBCASE("faulting thunk") {
        FakePe pe;
        pe.faults.insert(0x1508);
        CHECK(find_import_by_ordinal(pe.reader(), "XINPUT1_4.dll", 2).why == "thunk unreadable");
    }
    SUBCASE("faulting IAT slot") {
        FakePe pe;
        pe.faults.insert(0x1608);
        CHECK(find_import_by_ordinal(pe.reader(), "XINPUT1_4.dll", 2).why == "IAT slot unreadable");
    }
    SUBCASE("no OriginalFirstThunk") {
        FakePe pe;
        pe.desc(1, 0, 0x1220, 0x1600);
        CHECK(find_import_by_ordinal(pe.reader(), "XINPUT1_4.dll", 2).why == "XINPUT1_4.dll has no OriginalFirstThunk");
    }
    SUBCASE("import directory past the image") {
        FakePe pe;
        pe.put<uint32_t>(0x98 + 120, 0x7FFFFFF0);
        CHECK_FALSE(find_import_by_ordinal(pe.reader(), "XINPUT1_4.dll", 2).found);
    }
    SUBCASE("unterminated descriptor table stays bounded") {
        FakePe pe;
        // Every reader call succeeds with non-zero junk: the walk must still end.
        const ImageReader junk = [&pe](uint32_t rva, void* out, size_t n) {
            if (rva < 0x1000) return pe.reader()(rva, out, n);
            std::memset(out, 0x41, n);
            return true;
        };
        CHECK_FALSE(find_import_by_ordinal(junk, "XINPUT1_4.dll", 2).found);
    }
}
