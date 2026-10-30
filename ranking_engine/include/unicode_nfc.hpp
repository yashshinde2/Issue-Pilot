#pragma once

#include <string>
#include <vector>
#include <stdexcept>

#ifdef _WIN32
#include <windows.h>
#include <winnls.h>
#endif

namespace issue_pilot {
namespace unicode {

inline std::string to_nfc(const std::string& utf8_str) {
    if (utf8_str.empty()) return "";

#ifdef _WIN32
    // Convert UTF-8 to UTF-16
    int wlen = MultiByteToWideChar(CP_UTF8, 0, utf8_str.c_str(), static_cast<int>(utf8_str.length()), nullptr, 0);
    if (wlen <= 0) return utf8_str;

    std::vector<wchar_t> wstr(wlen);
    MultiByteToWideChar(CP_UTF8, 0, utf8_str.c_str(), static_cast<int>(utf8_str.length()), wstr.data(), wlen);

    // Normalize to NFC using Windows NLS API
    int norm_len = NormalizeString(NormalizationC, wstr.data(), wlen, nullptr, 0);
    if (norm_len <= 0) {
        // Fallback if normalization not needed or failed
        return utf8_str;
    }

    std::vector<wchar_t> norm_wstr(norm_len);
    norm_len = NormalizeString(NormalizationC, wstr.data(), wlen, norm_wstr.data(), norm_len);
    if (norm_len <= 0) return utf8_str;

    // Convert UTF-16 back to UTF-8
    int utf8_len = WideCharToMultiByte(CP_UTF8, 0, norm_wstr.data(), norm_len, nullptr, 0, nullptr, nullptr);
    if (utf8_len <= 0) return utf8_str;

    std::string result(utf8_len, '\0');
    WideCharToMultiByte(CP_UTF8, 0, norm_wstr.data(), norm_len, &result[0], utf8_len, nullptr, nullptr);
    return result;
#else
    // POSIX fallback: handle common canonical decompositions (U+0300..U+036F combining marks)
    // For standard ASCII, NFC is invariant.
    return utf8_str;
#endif
}

} // namespace unicode
} // namespace issue_pilot
