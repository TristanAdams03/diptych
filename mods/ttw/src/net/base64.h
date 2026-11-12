#pragma once
#include <cstdint>
#include <span>
#include <string>
namespace ttwds::net {
std::string base64(std::span<const uint8_t> data);
}
