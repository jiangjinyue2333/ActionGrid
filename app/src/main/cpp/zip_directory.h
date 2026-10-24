#pragma once

#include <cstddef>
#include <cstdint>

namespace actiongrid::zip {

// Return size for a missing/truncated footer; the comment must end at EOF.
constexpr size_t endRecordOffset(const uint8_t *bytes, size_t size) {
    if (size < 22) return size;
    size_t offset = size - 22;
    for (;;) {
        if (bytes[offset] == 0x50 && bytes[offset + 1] == 0x4b
                && bytes[offset + 2] == 0x05 && bytes[offset + 3] == 0x06) {
            const size_t commentSize = bytes[offset + 20]
                    | static_cast<size_t>(bytes[offset + 21]) << 8U;
            if (offset + 22 + commentSize == size) return offset;
        }
        if (offset == 0) return size;
        --offset;
    }
}

} // namespace actiongrid::zip
