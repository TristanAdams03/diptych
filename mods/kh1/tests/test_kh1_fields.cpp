#include <doctest/doctest.h>

#include <fstream>
#include <sstream>
#include <string>

#include <nlohmann/json.hpp>

#include "core/kh1_fields.h"
#include "fake_memory.h"

using namespace diptych::core;
using nlohmann::json;

namespace {
json fixture(const char* name) {
    std::ifstream f(std::string(FIXTURES_DIR) + "/" + name, std::ios::binary);
    std::stringstream ss;
    ss << f.rdbuf();
    return json::parse(ss.str());
}

FakeMemory spike_memory() {
    FakeMemory m;
    m.set(kWorldOffset, 0x03);
    m.set(kRoomOffset, 0x02);
    m.set(kWorldAltOffset, 0x03);
    m.set(kSoraHpOffset, 52);
    m.set(kSoraHpMaxOffset, 60);
    return m;
}

const char* const kGameFields[] = {"world",   "room",       "worldAlt", "soraHp",    "soraHpMax",
                                   "inMenu",  "inCutscene", "inGummi",  "inputBytes"};
}  // namespace

TEST_CASE("offsets: PROTOCOL.md address table (Steam 1.0.0.2)") {
    CHECK(kWorldOffset == 0x233FE94);
    CHECK(kRoomOffset == 0x233FE8C);
    CHECK(kWorldAltOffset == 0x233FE84);
    CHECK(kSoraHpOffset == 0x2D5CC4C);
    CHECK(kSoraHpMaxOffset == 0x2DE9366);
    CHECK(kMenuOffset == 0x232DFA0);
    CHECK(kCutsceneOffset == 0x23AB2D0);
    CHECK(kGummiOffset == 0x5075A8);
    CHECK(kInputOffset == 0x23407B4);
}

TEST_CASE("state_reads_fields") {
    FakeMemory m = spike_memory();
    const json data = kh1_spike_state(m, 42, 205750, true);
    json want = fixture("state_spike.json");
    CHECK(data == want["data"]);
    // The full frame matches the fixture once seq/ms agree.
    const json frame = json::parse(spike_state_frame(kh1_spike_state(m, 9, 10, true)));
    want["data"]["seq"] = 9;
    want["data"]["ms"] = 10;
    CHECK(frame == want);
}

TEST_CASE("state: flags, input bytes and widths") {
    FakeMemory m = spike_memory();
    m.set(kMenuOffset, 1);
    m.set(kCutsceneOffset + 3, 0x01);  // only the top byte of the 4-byte read is set
    m.set32(kGummiOffset, 0x80000000u);  // non-zero (negative as an int) still means in Gummi
    m.set32(kInputOffset, 0x0a0b0c10u);
    m.set(kSoraHpMaxOffset + 1, 0x7f);  // soraHpMax is a u8 read: neighbours do not leak in
    const json d = kh1_spike_state(m, 1, 2, true);
    CHECK(d["inMenu"] == true);
    CHECK(d["inCutscene"] == true);
    CHECK(d["inGummi"] == true);
    CHECK(d["inputBytes"] == "100c0b0a");
    CHECK(d["soraHpMax"] == 60);
}

TEST_CASE("unsupported_build_blocks_all") {
    FakeMemory m = spike_memory();
    const json d = kh1_spike_state(m, 7, 41200, false);
    CHECK(d == fixture("state_unsupported.json")["data"]);
    for (const char* k : kGameFields) {
        REQUIRE(d.contains(k));
        CHECK(d[k].is_null());
    }
    CHECK(m.reads == 0);  // not even read
}

TEST_CASE("reader_fault_nulls_field") {
    SUBCASE("nullopt") {
        FakeMemory m = spike_memory();
        m.faults.insert(kRoomOffset);
        const json d = kh1_spike_state(m, 1, 2, true);
        CHECK(d["room"].is_null());
        CHECK(d["world"] == 3);
        CHECK(d["soraHp"] == 52);
        CHECK(d["inputBytes"] == "00000000");
    }
    SUBCASE("throws") {
        FakeMemory m = spike_memory();
        m.throws.insert(kRoomOffset);
        m.throws.insert(kInputOffset + 2);
        const json d = kh1_spike_state(m, 1, 2, true);
        CHECK(d["room"].is_null());
        CHECK(d["inputBytes"].is_null());
        CHECK(d["world"] == 3);
        CHECK(d["inMenu"] == false);
        for (const char* k : kGameFields) CHECK(d.contains(k));
    }
}

TEST_CASE("safe_now: menu, cutscene and Gummi all readable and clear") {
    FakeMemory m;
    CHECK(safe_now(m));
    SUBCASE("menu") { m.set(kMenuOffset, 1); CHECK_FALSE(safe_now(m)); }
    SUBCASE("cutscene any byte") { m.set(kCutsceneOffset + 2, 0x10); CHECK_FALSE(safe_now(m)); }
    SUBCASE("gummi") { m.set32(kGummiOffset, 1); CHECK_FALSE(safe_now(m)); }
    SUBCASE("unreadable menu") { m.faults.insert(kMenuOffset); CHECK_FALSE(safe_now(m)); }
    SUBCASE("unreadable gummi throws") { m.throws.insert(kGummiOffset); CHECK_FALSE(safe_now(m)); }
}

TEST_CASE("DisablingMemory: a fault turns that reader off for the session") {
    FakeMemory m = spike_memory();
    m.faults.insert(kRoomOffset);
    std::vector<uintptr_t> faulted;
    DisablingMemory dm(m, [&](uintptr_t off) { faulted.push_back(off); });
    CHECK(kh1_spike_state(dm, 1, 2, true)["room"].is_null());
    m.faults.clear();  // the memory recovers, the reader stays off
    const json d = kh1_spike_state(dm, 2, 3, true);
    CHECK(d["room"].is_null());
    CHECK(d["world"] == 3);
    REQUIRE(faulted.size() == 1);
    CHECK(faulted[0] == kRoomOffset);
    // A throwing reader is turned off too, and the exception does not escape.
    m.throws.insert(kWorldOffset);
    CHECK_FALSE(dm.u8(kWorldOffset).has_value());
    m.throws.clear();
    CHECK_FALSE(dm.u8(kWorldOffset).has_value());
    CHECK(faulted.size() == 2);
    // Writes pass through; image_size too.
    CHECK(dm.write_u8(kInputOffset, 1));
    CHECK(dm.image_size() == m.size);
}

TEST_CASE("RawWatch: logs raw inCutscene and maxHP bytes when they change") {
    FakeMemory m = spike_memory();
    RawWatch w;
    auto first = w.poll(m);
    REQUIRE(first.size() == 2);
    CHECK(first[0].find("inCutscene raw u32 0x00000000") != std::string::npos);
    CHECK(first[1].find("maxHP raw bytes") != std::string::npos);
    CHECK(first[1].find("003c") != std::string::npos);  // byte at maxHP-1, then maxHP = 60
    CHECK(w.poll(m).empty());
    m.set(kCutsceneOffset, 0x02);
    auto next = w.poll(m);
    REQUIRE(next.size() == 1);
    CHECK(next[0].find("0x00000002") != std::string::npos);
}
