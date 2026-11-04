#pragma once
#include <cstdint>
#include <span>
#include <string>
namespace diptych::net {
std::string base64(std::span<const uint8_t> data);
}
