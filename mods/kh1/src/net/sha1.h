#pragma once
#include <array>
#include <cstdint>
#include <string_view>
namespace diptych::net {
std::array<uint8_t, 20> sha1(std::string_view data);
}
