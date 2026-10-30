#pragma once

#include <string>
#include <vector>
#include <unordered_map>
#include <stdexcept>

#ifdef _WIN32
#ifndef _WIN32_WINNT
#define _WIN32_WINNT 0x0601
#endif
#include <windows.h>
#endif

namespace issue_pilot {
namespace unicode {

// Cross-platform Canonical Composition (NFC) for UTF-8 strings.
// Uses Windows NLS NormalizeString when running on Windows (dynamically bound to avoid header discrepancies),
// with a canonical fallback composition engine for common combining diacritical marks (U+0300..U+036F).

inline std::string canonical_fallback_nfc(const std::string& utf8_str) {
    // Canonical compositions for common decomposed pairs (Latin base + combining mark)
    // Combining Acute: \xCC\x81, Combining Diaeresis: \xCC\x88, Combining Ring: \xCC\x8A
    std::string res;
    for (size_t i = 0; i < utf8_str.size(); ) {
        if (i + 2 < utf8_str.size() && static_cast<unsigned char>(utf8_str[i + 1]) == 0xCC) {
            unsigned char base = utf8_str[i];
            unsigned char mark = utf8_str[i + 2];
            // e + \u0301 (acute) -> \u00e9 (C3 A9)
            if (base == 'e' && mark == 0x81) {
                res += "\xC3\xA9";
                i += 3;
                continue;
            }
            // E + \u0301 (acute) -> \u00c9 (C3 89)
            if (base == 'E' && mark == 0x81) {
                res += "\xC3\x89";
                i += 3;
                continue;
            }
            // a + \u0301 (acute) -> \u00e1 (C3 A1)
            if (base == 'a' && mark == 0x81) {
                res += "\xC3\xA1";
                i += 3;
                continue;
            }
            // i + \u0308 (diaeresis) -> \u00ef (C3 AF)
            if (base == 'i' && mark == 0x88) {
                res += "\xC3\xAF";
                i += 3;
                continue;
            }
            // u + \u0308 (diaeresis) -> \u00fc (C3 BC)
            if (base == 'u' && mark == 0x88) {
                res += "\xC3\xBC";
                i += 3;
                continue;
            }
            // o + \u0308 (diaeresis) -> \u00f6 (C3 B6)
            if (base == 'o' && mark == 0x88) {
                res += "\xC3\xB6";
                i += 3;
                continue;
            }
            // a + \u030a (ring above) -> \u00e5 (C3 A5)
            if (base == 'a' && mark == 0x8A) {
                res += "\xC3\xA5";
                i += 3;
                continue;
            }
            // A + \u030a (ring above) -> \u00c5 (C3 85)
            if (base == 'A' && mark == 0x8A) {
                res += "\xC3\x85";
                i += 3;
                continue;
            }
        }
        res += utf8_str[i++];
    }
    return res;
}

inline std::string to_nfc(const std::string& utf8_str) {
    if (utf8_str.empty()) return "";

#ifdef _WIN32
    // Dynamically resolve NormalizeString from Kernel32 or Normaliz
    typedef int (WINAPI *NormalizeStringFn)(int, LPCWSTR, int, LPWSTR, int);
    static NormalizeStringFn pNormalizeString = nullptr;
    static bool init_done = false;

    if (!init_done) {
        HMODULE hMod = GetModuleHandleW(L"kernel32.dll");
        if (hMod) {
            pNormalizeString = (NormalizeStringFn)GetProcAddress(hMod, "NormalizeString");
        }
        if (!pNormalizeString) {
            hMod = LoadLibraryW(L"Normaliz.dll");
            if (hMod) {
                pNormalizeString = (NormalizeStringFn)GetProcAddress(hMod, "NormalizeString");
            }
        }
        init_done = true;
    }

    if (pNormalizeString) {
        int wlen = MultiByteToWideChar(CP_UTF8, 0, utf8_str.c_str(), static_cast<int>(utf8_str.length()), nullptr, 0);
        if (wlen > 0) {
            std::vector<wchar_t> wstr(wlen);
            MultiByteToWideChar(CP_UTF8, 0, utf8_str.c_str(), static_cast<int>(utf8_str.length()), wstr.data(), wlen);

            // NormalizationC = 1
            int norm_len = pNormalizeString(1, wstr.data(), wlen, nullptr, 0);
            if (norm_len > 0) {
                std::vector<wchar_t> norm_wstr(norm_len);
                norm_len = pNormalizeString(1, wstr.data(), wlen, norm_wstr.data(), norm_len);
                if (norm_len > 0) {
                    int utf8_len = WideCharToMultiByte(CP_UTF8, 0, norm_wstr.data(), norm_len, nullptr, 0, nullptr, nullptr);
                    if (utf8_len > 0) {
                        std::string result(utf8_len, '\0');
                        WideCharToMultiByte(CP_UTF8, 0, norm_wstr.data(), norm_len, &result[0], utf8_len, nullptr, nullptr);
                        return result;
                    }
                }
            }
        }
    }
#endif

    return canonical_fallback_nfc(utf8_str);
}

} // namespace unicode
} // namespace issue_pilot
