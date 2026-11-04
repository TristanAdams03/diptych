#pragma once
#include <cstddef>
#include <cstdint>
#include <string>
#include <string_view>
namespace diptych::net {
struct WsFrame {
    uint8_t opcode = 0;
    bool fin = false;
    std::string payload;
};
enum class DecodeStatus { NeedMore, Ok, ProtocolError, TooBig };
// Consumes one client frame from buf on Ok. Client frames must be masked.
DecodeStatus ws_decode(std::string& buf, WsFrame& out, size_t maxPayload = 1 << 20);
// Server frame: FIN set, unmasked.
std::string ws_encode(uint8_t opcode, std::string_view payload);
}
