#pragma once

#include "utf8proc.h"
#include <string>
#include <cstdlib>

namespace issue_pilot {
namespace unicode {

/**
 * Authoritative, standards-compliant Unicode Normalization Form C (NFC) via utf8proc.
 * Conforms to Unicode Standard Annex #15 (UAX #15):
 * - Full canonical decomposition
 * - Canonical combining-class reordering (multiple stacked diacritical marks)
 * - Composition exclusions
 * - Full Hangul algorithmic composition/decomposition (modern and archaic Jamo syllables)
 * - Supplementary plane Unicode characters (e.g. mathematical symbols, emojis, SMP/SIP codepoints)
 */
inline std::string normalize_to_nfc(const std::string& input) {
    if (input.empty()) return "";
    utf8proc_uint8_t* normalized = utf8proc_NFC(reinterpret_cast<const utf8proc_uint8_t*>(input.c_str()));
    if (!normalized) {
        return input;
    }
    std::string result(reinterpret_cast<const char*>(normalized));
    std::free(normalized);
    return result;
}

inline std::string to_nfc(const std::string& input) {
    return normalize_to_nfc(input);
}

} // namespace unicode
} // namespace issue_pilot
