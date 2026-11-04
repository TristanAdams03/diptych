#include "win/forwarder.h"

#include <atomic>
#include <mutex>

#include "win/log.h"

namespace diptych::win {

std::wstring system_dinput8_path() {
    wchar_t dir[MAX_PATH];
    UINT n = GetSystemDirectoryW(dir, MAX_PATH);
    if (n == 0 || n >= MAX_PATH) return {};
    return std::wstring(dir, n) + L"\\dinput8.dll";
}

namespace {
using DI8CreateFn = HRESULT(WINAPI*)(HINSTANCE, DWORD, REFIID, LPVOID*, LPUNKNOWN);
using CanUnloadFn = HRESULT(WINAPI*)();
using GetClassObjectFn = HRESULT(WINAPI*)(REFCLSID, REFIID, LPVOID*);
using RegisterFn = HRESULT(WINAPI*)();
using GetdfFn = const void*(WINAPI*)();

struct Real {
    DI8CreateFn create = nullptr;
    CanUnloadFn canUnload = nullptr;
    GetClassObjectFn getClassObject = nullptr;
    RegisterFn reg = nullptr;
    RegisterFn unreg = nullptr;
    GetdfFn getdf = nullptr;
};

std::atomic<TargetPathFn> g_pathFn{&system_dinput8_path};
std::once_flag g_once;
Real g_real;

template <class F>
F resolve(HMODULE m, const char* name) {
    FARPROC p = GetProcAddress(m, name);
    if (!p) log_line(std::string("forwarder: real dinput8.dll lacks ") + name);
    return reinterpret_cast<F>(p);
}

void load_real() {
    const std::wstring path = g_pathFn.load()();
    if (path.empty()) {
        log_line("forwarder: no system directory; real dinput8.dll unavailable");
        return;
    }
    HMODULE m = LoadLibraryW(path.c_str());
    if (!m) {
        log_line("forwarder: LoadLibraryW(" + narrow(path) + ") failed, error " + std::to_string(GetLastError()));
        return;
    }
    HMODULE self = nullptr;
    GetModuleHandleExW(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS | GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT,
                       reinterpret_cast<LPCWSTR>(&load_real), &self);
    if (m == self) {  // the loader handed us back ourselves: forwarding would recurse forever
        FreeLibrary(m);
        log_line("forwarder: " + narrow(path) + " resolved to this proxy; real dinput8.dll unavailable");
        return;
    }
    g_real.create = resolve<DI8CreateFn>(m, "DirectInput8Create");
    g_real.canUnload = resolve<CanUnloadFn>(m, "DllCanUnloadNow");
    g_real.getClassObject = resolve<GetClassObjectFn>(m, "DllGetClassObject");
    g_real.reg = resolve<RegisterFn>(m, "DllRegisterServer");
    g_real.unreg = resolve<RegisterFn>(m, "DllUnregisterServer");
    g_real.getdf = resolve<GetdfFn>(m, "GetdfDIJoystick");
    log_line("forwarder: loaded " + narrow(path));
    // m is never freed: the proxy forwards to it for the life of the process.
}

const Real& real() {
    std::call_once(g_once, load_real);
    return g_real;
}
}  // namespace

void set_target_path_fn(TargetPathFn fn) { g_pathFn.store(fn ? fn : &system_dinput8_path); }

}  // namespace diptych::win

using diptych::win::real;

extern "C" HRESULT WINAPI DirectInput8Create(HINSTANCE hinst, DWORD dwVersion, REFIID riidltf, LPVOID* ppvOut,
                                             LPUNKNOWN punkOuter) {
    if (auto fn = real().create) return fn(hinst, dwVersion, riidltf, ppvOut, punkOuter);
    if (ppvOut) *ppvOut = nullptr;
    diptych::win::log_line("DirectInput8Create: real dinput8.dll unavailable, returning DIERR_NOTINITIALIZED");
    return diptych::win::kDierrNotInitialized;
}

extern "C" HRESULT WINAPI DllCanUnloadNow(void) {
    if (auto fn = real().canUnload) return fn();
    return S_FALSE;
}

extern "C" HRESULT WINAPI DllGetClassObject(REFCLSID rclsid, REFIID riid, LPVOID* ppv) {
    if (auto fn = real().getClassObject) return fn(rclsid, riid, ppv);
    if (ppv) *ppv = nullptr;
    return CLASS_E_CLASSNOTAVAILABLE;
}

extern "C" HRESULT WINAPI DllRegisterServer(void) {
    if (auto fn = real().reg) return fn();
    return E_FAIL;
}

extern "C" HRESULT WINAPI DllUnregisterServer(void) {
    if (auto fn = real().unreg) return fn();
    return E_FAIL;
}

extern "C" const void* WINAPI GetdfDIJoystick(void) {
    if (auto fn = real().getdf) return fn();
    return nullptr;
}
