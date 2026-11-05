#include "win/xinput_hook.h"

#include <windows.h>
#include <xinput.h>

#include <atomic>
#include <cstdint>
#include <cstdio>

#include "core/pe_imports.h"
#include "win/module_reader.h"
#include "win/seh.h"

namespace diptych::win {

namespace {

using GetStateFn = DWORD(WINAPI*)(DWORD, XINPUT_STATE*);

core::InjectState g_inject;
std::atomic<GetStateFn> g_real{nullptr};

// The game thread's XInputGetState. No locks, no allocation, nothing that can throw.
DWORD WINAPI hooked_get_state(DWORD userIndex, XINPUT_STATE* state) noexcept {
    const GetStateFn real = g_real.load(std::memory_order_acquire);
    if (!real) return ERROR_DEVICE_NOT_CONNECTED;
    const DWORD r = real(userIndex, state);
    if (userIndex != 0) return r;
    if (r == ERROR_SUCCESS && state) {
        uint32_t packet = state->dwPacketNumber;
        uint16_t buttons = state->Gamepad.wButtons;
        uint8_t lt = state->Gamepad.bLeftTrigger, rt = state->Gamepad.bRightTrigger;
        g_inject.apply(packet, buttons, lt, rt);
        state->dwPacketNumber = packet;
        state->Gamepad.wButtons = buttons;
        state->Gamepad.bLeftTrigger = lt;
        state->Gamepad.bRightTrigger = rt;
    } else if (r == ERROR_DEVICE_NOT_CONNECTED && g_inject.active()) {
        g_inject.note_no_pad();  // no controller, no injection; the runtime logs it once
    }
    return r;
}

static_assert(sizeof(XINPUT_STATE) == 16, "XINPUT_STATE layout");

bool fail(std::string& line, const std::string& why) {
    line = "xinput hook not installed: " + why + "; pressInput disabled";
    return false;
}

bool executable(DWORD protect) {
    return (protect & (PAGE_EXECUTE | PAGE_EXECUTE_READ | PAGE_EXECUTE_READWRITE | PAGE_EXECUTE_WRITECOPY)) != 0;
}

}  // namespace

core::InjectState& xinput_inject_state() { return g_inject; }

bool install_xinput_hook(const void* moduleBase, std::string& line) {
    static std::atomic<bool> tried{false};
    if (tried.exchange(true)) return fail(line, "already attempted");
    const auto base = reinterpret_cast<uintptr_t>(moduleBase);
    const uint32_t size = module_image_size(moduleBase);
    if (!base || size == 0) return fail(line, "main module headers unreadable");
    const core::ImageReader read = [base, size](uint32_t rva, void* out, size_t n) {
        if (rva >= size || n > size - rva) return false;
        return seh_copy(reinterpret_cast<const void*>(base + rva), out, n);
    };
    const core::ImportSlot slot = core::find_import_by_ordinal(read, "XINPUT1_4.dll", 2);
    if (!slot.found) return fail(line, slot.why);
    if (slot.iatRva % sizeof(void*) != 0 || slot.iatRva > size - sizeof(void*)) return fail(line, "IAT slot misaligned");

    void** p = reinterpret_cast<void**>(base + slot.iatRva);
    void* real = nullptr;
    if (!seh_copy(p, &real, sizeof real) || !real) return fail(line, "IAT slot unreadable or empty");
    MEMORY_BASIC_INFORMATION mbi{};
    if (VirtualQuery(p, &mbi, sizeof mbi) != sizeof mbi || mbi.State != MEM_COMMIT)
        return fail(line, "IAT page not committed");
    // Keep execute if the page had it, so code sharing the page (unusual for an IAT) still runs.
    const DWORD want = executable(mbi.Protect) ? PAGE_EXECUTE_READWRITE : PAGE_READWRITE;
    // The hook reads g_real; it must be set before the slot points at the hook.
    g_real.store(reinterpret_cast<GetStateFn>(real), std::memory_order_release);
    DWORD old = 0;
    if (!VirtualProtect(p, sizeof(void*), want, &old)) return fail(line, "VirtualProtect failed");
    // Swap only if nobody changed the slot since we read it; one aligned 8-byte store either way.
    void* prev = InterlockedCompareExchangePointer(p, reinterpret_cast<void*>(&hooked_get_state), real);
    DWORD ignored = 0;
    const bool restored = VirtualProtect(p, sizeof(void*), old, &ignored) != 0;
    if (prev != real) return fail(line, "IAT slot changed during install");
    char buf[160];
    std::snprintf(buf, sizeof buf, "xinput hook installed (slot +0x%lx, was %p%s)", static_cast<unsigned long>(slot.iatRva),
                  real, restored ? "" : "; protection restore failed");
    line = buf;
    return true;
}

}  // namespace diptych::win
