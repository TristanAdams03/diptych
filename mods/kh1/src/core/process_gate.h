#pragma once
#include <string_view>

namespace diptych::core {

// True only when the basename of exePath (after the last '\' or '/') equals
// "KINGDOM HEARTS FINAL MIX.exe", ignoring ASCII case. The launcher, KH2, BBS and ReCoM share
// the folder and also load dinput8.dll; for them the mod must stay a pure passthrough.
bool is_kh1_process(std::wstring_view exePath);

}  // namespace diptych::core
