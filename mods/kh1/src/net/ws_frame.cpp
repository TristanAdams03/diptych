#include "net/ws_frame.h"

namespace diptych::net {
DecodeStatus ws_decode(std::string& buf, WsFrame& out, size_t maxPayload) {
    if (buf.size() < 2) return DecodeStatus::NeedMore;
    const auto b0 = static_cast<uint8_t>(buf[0]);
    const auto b1 = static_cast<uint8_t>(buf[1]);
    const bool fin = (b0 & 0x80) != 0;
    const auto opcode = static_cast<uint8_t>(b0 & 0x0F);
    if ((opcode >= 3 && opcode <= 7) || opcode >= 0xB) return DecodeStatus::ProtocolError;  // reserved
    if (b0 & 0x70) return DecodeStatus::ProtocolError;  // RSV bits, no extensions negotiated
    if (!(b1 & 0x80)) return DecodeStatus::ProtocolError;  // client frames must be masked
    uint64_t len = static_cast<uint64_t>(b1 & 0x7F);
    if (opcode >= 8 && (!fin || len > 125)) return DecodeStatus::ProtocolError;

    size_t pos = 2;
    if (len == 126) {
        if (buf.size() < pos + 2) return DecodeStatus::NeedMore;
        len = (uint64_t(static_cast<uint8_t>(buf[2])) << 8) | static_cast<uint8_t>(buf[3]);
        pos += 2;
    } else if (len == 127) {
        if (buf.size() < pos + 8) return DecodeStatus::NeedMore;
        len = 0;
        for (int i = 0; i < 8; ++i) len = (len << 8) | static_cast<uint8_t>(buf[pos + i]);
        if (len >> 63) return DecodeStatus::ProtocolError;
        pos += 8;
    }
    if (len > maxPayload) return DecodeStatus::TooBig;
    if (buf.size() < pos + 4) return DecodeStatus::NeedMore;
    const size_t maskPos = pos;
    pos += 4;
    if (buf.size() - pos < len) return DecodeStatus::NeedMore;

    out.opcode = opcode;
    out.fin = fin;
    out.payload.resize(static_cast<size_t>(len));
    for (size_t i = 0; i < static_cast<size_t>(len); ++i)
        out.payload[i] = static_cast<char>(static_cast<uint8_t>(buf[pos + i]) ^
                                           static_cast<uint8_t>(buf[maskPos + (i & 3)]));
    buf.erase(0, pos + static_cast<size_t>(len));
    return DecodeStatus::Ok;
}

std::string ws_encode(uint8_t opcode, std::string_view payload) {
    if (opcode >= 8 && payload.size() > 125) payload = payload.substr(0, 125);  // control frame limit
    std::string out;
    out.reserve(payload.size() + 10);
    out.push_back(static_cast<char>(static_cast<uint8_t>(0x80 | (opcode & 0x0F))));
    const uint64_t n = payload.size();
    if (n < 126) {
        out.push_back(static_cast<char>(static_cast<uint8_t>(n)));
    } else if (n <= 0xFFFF) {
        out.push_back(126);
        out.push_back(static_cast<char>(static_cast<uint8_t>(n >> 8)));
        out.push_back(static_cast<char>(static_cast<uint8_t>(n & 0xFF)));
    } else {
        out.push_back(127);
        for (int i = 7; i >= 0; --i) out.push_back(static_cast<char>(static_cast<uint8_t>((n >> (8 * i)) & 0xFF)));
    }
    out.append(payload);
    return out;
}
}  // namespace diptych::net
