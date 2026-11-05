#include "net/ws_handshake.h"

#include "net/base64.h"
#include "net/sha1.h"

namespace diptych::net {
namespace {
char lower(char c) { return (c >= 'A' && c <= 'Z') ? char(c - 'A' + 'a') : c; }

bool iequals(std::string_view a, std::string_view b) {
    if (a.size() != b.size()) return false;
    for (size_t i = 0; i < a.size(); ++i)
        if (lower(a[i]) != lower(b[i])) return false;
    return true;
}

bool icontains(std::string_view hay, std::string_view needle) {
    if (needle.size() > hay.size()) return false;
    for (size_t i = 0; i + needle.size() <= hay.size(); ++i)
        if (iequals(hay.substr(i, needle.size()), needle)) return true;
    return false;
}

std::string_view trim(std::string_view s) {
    while (!s.empty() && (s.front() == ' ' || s.front() == '\t')) s.remove_prefix(1);
    while (!s.empty() && (s.back() == ' ' || s.back() == '\t' || s.back() == '\r')) s.remove_suffix(1);
    return s;
}
}  // namespace

std::optional<std::string> ws_accept_response(std::string_view req) {
    // Must be a complete request (terminated header block).
    if (req.find("\r\n\r\n") == std::string_view::npos) return std::nullopt;
    // Request line must be a GET.
    size_t eol = req.find('\n');
    if (eol == std::string_view::npos) return std::nullopt;
    if (req.substr(0, 4) != "GET ") return std::nullopt;

    bool upgrade = false;
    bool hostOk = false;
    std::string_view key;
    size_t pos = eol + 1;
    while (pos < req.size()) {
        size_t end = req.find('\n', pos);
        std::string_view line = req.substr(pos, end == std::string_view::npos ? req.size() - pos : end - pos);
        pos = (end == std::string_view::npos) ? req.size() : end + 1;
        line = trim(line);
        if (line.empty()) break;  // end of headers
        size_t colon = line.find(':');
        if (colon == std::string_view::npos) continue;
        std::string_view name = trim(line.substr(0, colon));
        std::string_view value = trim(line.substr(colon + 1));
        if (iequals(name, "Upgrade")) {
            if (icontains(value, "websocket")) upgrade = true;
        } else if (iequals(name, "Origin")) {
            return std::nullopt;  // browsers send Origin; our client does not
        } else if (iequals(name, "Host")) {
            std::string_view h = value;
            if (!h.empty() && h.front() == '[') {
                hostOk = false;  // IPv6 literals are not accepted
            } else {
                size_t c = h.find(':');
                if (c != std::string_view::npos) h = h.substr(0, c);
                hostOk = iequals(h, "127.0.0.1") || iequals(h, "localhost");
            }
        } else if (iequals(name, "Sec-WebSocket-Key")) {
            key = value;
        }
    }
    if (!upgrade || !hostOk || key.empty()) return std::nullopt;

    std::string combined(key);
    combined += "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    auto digest = sha1(combined);
    std::string accept = base64(std::span<const uint8_t>(digest.data(), digest.size()));

    std::string resp =
        "HTTP/1.1 101 Switching Protocols\r\n"
        "Upgrade: websocket\r\n"
        "Connection: Upgrade\r\n"
        "Sec-WebSocket-Accept: ";
    resp += accept;
    resp += "\r\n\r\n";
    return resp;
}
}  // namespace diptych::net
