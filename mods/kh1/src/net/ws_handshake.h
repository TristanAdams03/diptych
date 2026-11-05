#pragma once
#include <optional>
#include <string>
#include <string_view>
namespace diptych::net {
// Full "101 Switching Protocols" response, or nullopt if not a valid upgrade.
std::optional<std::string> ws_accept_response(std::string_view httpRequest);
}
