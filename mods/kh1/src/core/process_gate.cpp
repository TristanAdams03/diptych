#include "core/process_gate.h"

namespace diptych::core {

namespace {
constexpr std::wstring_view kKh1Exe = L"KINGDOM HEARTS FINAL MIX.exe";

wchar_t fold(wchar_t c) { return (c >= L'A' && c <= L'Z') ? static_cast<wchar_t>(c - L'A' + L'a') : c; }
}  // namespace

bool is_kh1_process(std::wstring_view exePath) {
    auto slash = exePath.find_last_of(L"\\/");
    std::wstring_view base = slash == std::wstring_view::npos ? exePath : exePath.substr(slash + 1);
    if (base.size() != kKh1Exe.size()) return false;
    for (size_t i = 0; i < base.size(); ++i)
        if (fold(base[i]) != fold(kKh1Exe[i])) return false;
    return true;
}

}  // namespace diptych::core
