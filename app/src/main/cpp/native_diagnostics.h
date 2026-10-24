#pragma once

#include <algorithm>
#include <cstdint>
#include <deque>
#include <mutex>
#include <string>

namespace actiongrid::diagnostics {

// Producers only copy bounded text. The worker owns all Binder/file work.
class Buffer {
public:
    static constexpr size_t kMaxLines = 256;
    static constexpr size_t kMaxLineBytes = 1024;
    static constexpr size_t kMaxBatchBytes = 24 * 1024;
    static constexpr size_t kMaxBatchLines = 64;
    struct Batch {
        int64_t session = 0;
        uint64_t through = 0;
        uint64_t dropped = 0;
        std::string text;
    };

    bool setSession(int64_t value) {
        std::lock_guard lock(mutex_);
        value = std::max<int64_t>(0, value);
        if (session_ == value) return false;
        session_ = value;
        lines_.clear();
        dropped_ = 0;
        return true;
    }

    int64_t session() const {
        std::lock_guard lock(mutex_);
        return session_;
    }

    void append(std::string text, int64_t expectedSession) {
        std::lock_guard lock(mutex_);
        if (session_ == 0 || session_ != expectedSession) return;
        // Keep complete UTF-8 codepoints when cutting a long message.
        if (text.size() > kMaxLineBytes) {
            size_t end = kMaxLineBytes - 3;
            while (end > 0 && (static_cast<unsigned char>(text[end]) & 0xc0U) == 0x80U) --end;
            text.resize(end);
            text += "...";
        }
        for (char &c : text) if (c == '\n' || c == '\r' || c == '\0') c = ' ';
        if (lines_.size() == kMaxLines) {
            lines_.pop_front();
            ++dropped_;
        }
        lines_.push_back({++sequence_, std::move(text)});
    }

    Batch batch() const {
        std::lock_guard lock(mutex_);
        Batch result{session_, 0, dropped_, {}};
        if (session_ == 0) return result;
        if (dropped_ != 0) result.text = "buffer overflow: dropped=" + std::to_string(dropped_) + "\n";
        size_t count = dropped_ != 0 ? 1 : 0;
        for (const auto &line : lines_) {
            std::string entry = "#" + std::to_string(line.sequence) + " " + line.text + "\n";
            if (count == kMaxBatchLines || result.text.size() + entry.size() > kMaxBatchBytes) break;
            ++count;
            result.text += entry;
            result.through = line.sequence;
        }
        return result;
    }

    void acknowledge(const Batch &batch) {
        std::lock_guard lock(mutex_);
        if (batch.session != session_) return;
        while (!lines_.empty() && lines_.front().sequence <= batch.through) lines_.pop_front();
        dropped_ -= std::min(dropped_, batch.dropped);
    }

private:
    struct Line { uint64_t sequence; std::string text; };
    mutable std::mutex mutex_;
    std::deque<Line> lines_;
    int64_t session_ = 0;
    uint64_t sequence_ = 0;
    uint64_t dropped_ = 0;
};

} // namespace actiongrid::diagnostics
