#pragma once
// dinput8.dll proxy: the six exports forward to the real system dinput8.dll, which is loaded
// lazily and once, on the first export call (never in DllMain).
#include <windows.h>
#include <unknwn.h>

#include <string>

namespace diptych::win {

using TargetPathFn = std::wstring (*)();

// GetSystemDirectoryW() + L"\\dinput8.dll".
std::wstring system_dinput8_path();

// Overrides where the real DLL is loaded from (tests). Only effective before the first export
// call: the target is resolved exactly once per process.
void set_target_path_fn(TargetPathFn fn);

// DIERR_NOTINITIALIZED: returned by DirectInput8Create when the real DLL is unavailable.
inline constexpr HRESULT kDierrNotInitialized = static_cast<HRESULT>(0x80070015L);

}  // namespace diptych::win

// Exported undecorated via exports.def.
extern "C" {
HRESULT WINAPI DirectInput8Create(HINSTANCE hinst, DWORD dwVersion, REFIID riidltf, LPVOID* ppvOut,
                                  LPUNKNOWN punkOuter);
HRESULT WINAPI DllCanUnloadNow(void);
HRESULT WINAPI DllGetClassObject(REFCLSID rclsid, REFIID riid, LPVOID* ppv);
HRESULT WINAPI DllRegisterServer(void);
HRESULT WINAPI DllUnregisterServer(void);
const void* WINAPI GetdfDIJoystick(void);
}
