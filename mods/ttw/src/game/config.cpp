#include "game/config.h"
#include <algorithm>
#include <cctype>

namespace ttwds::game {

namespace {

std::string_view trim(std::string_view s) {
    while (!s.empty() && std::isspace(static_cast<unsigned char>(s.front()))) s.remove_prefix(1);
    while (!s.empty() && std::isspace(static_cast<unsigned char>(s.back()))) s.remove_suffix(1);
    return s;
}

std::string lower(std::string_view s) {
    std::string o(s);
    for (auto& c : o) c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
    return o;
}

// 127.a.b.c with each octet 0-255.
bool is_loopback_literal(std::string_view s) {
    int octets = 0;
    size_t i = 0;
    while (i <= s.size()) {
        size_t j = s.find('.', i);
        if (j == std::string_view::npos) j = s.size();
        std::string_view part = s.substr(i, j - i);
        if (part.empty() || part.size() > 3) return false;
        int v = 0;
        for (char c : part) {
            if (c < '0' || c > '9') return false;
            v = v * 10 + (c - '0');
        }
        if (v > 255 || (octets == 0 && v != 127)) return false;
        ++octets;
        i = j + 1;
        if (j == s.size()) break;
    }
    return octets == 4;
}

// Ports other local tools on the device own (global constraints).
bool reserved_port(long p) {
    return (p >= 7786 && p <= 7788) || p == 8765 || p == 27000 || p == 28000;
}

}  // namespace

Config parse_config(std::string_view text) {
    Config c;
    std::string section;
    size_t pos = 0;
    while (pos < text.size()) {
        size_t eol = text.find('\n', pos);
        if (eol == std::string_view::npos) eol = text.size();
        std::string_view line = text.substr(pos, eol - pos);
        pos = eol + 1;

        line = trim(line);
        if (line.empty() || line.front() == ';' || line.front() == '#') continue;
        if (line.front() == '[') {
            const size_t close = line.find(']');
            section = lower(trim(line.substr(1, close == std::string_view::npos ? line.size() - 1 : close - 1)));
            continue;
        }
        const size_t eq = line.find('=');
        if (eq == std::string_view::npos) continue;
        const std::string key = lower(trim(line.substr(0, eq)));
        std::string_view value = line.substr(eq + 1);
        const size_t cmt = value.find_first_of(";#");
        if (cmt != std::string_view::npos) value = value.substr(0, cmt);
        value = trim(value);

        if (section == "server" && key == "bindaddress") {
            if (is_loopback_literal(value))
                c.bind = std::string(value);
            else
                c.warnings.push_back("BindAddress '" + std::string(value) + "' is not 127.x.x.x; using 127.0.0.1");
        } else if (section == "server" && key == "port") {
            long p = -1;
            if (!value.empty() && value.size() <= 5 &&
                std::all_of(value.begin(), value.end(), [](char ch) { return ch >= '0' && ch <= '9'; }))
                p = std::stol(std::string(value));
            if (p >= 1 && p <= 65535 && !reserved_port(p))
                c.port = static_cast<uint16_t>(p);
            else
                c.warnings.push_back("Port '" + std::string(value) + "' rejected; using 7960");
        } else if (section == "debug" && key == "loglevel") {
            const std::string v = lower(value);
            if (v == "off") c.logLevel = LogLevel::Off;
            else if (v == "info") c.logLevel = LogLevel::Info;
            else if (v == "debug") c.logLevel = LogLevel::Debug;
            else c.warnings.push_back("LogLevel '" + std::string(value) + "' unknown; using info");
        }
    }
    return c;
}

}  // namespace ttwds::game
