#pragma once

#include <cstdint>
#include <cstddef>
#include <cmath>
#include <algorithm>

namespace finn {

class Vad {
public:
    virtual ~Vad() = default;
    virtual void reset() = 0;
    virtual bool process_frame(const int16_t* samples, size_t count) = 0;
    virtual bool is_speech_active() const = 0;
};

class EnergyVad : public Vad {
public:
    explicit EnergyVad(
        float energy_threshold = 450.0f,
        int speech_onset_frames = 2,
        int silence_hangover_frames = 25 // 25 frames * 20ms = 500ms
    ) : energy_threshold_(energy_threshold),
        speech_onset_frames_(speech_onset_frames),
        silence_hangover_frames_(silence_hangover_frames) {}

    void reset() override {
        consecutive_speech_frames_ = 0;
        consecutive_silence_frames_ = 0;
        is_speech_active_ = false;
        just_started_speech_ = false;
        just_ended_speech_ = false;
    }

    void set_threshold(float threshold) {
        energy_threshold_ = threshold;
    }

    float get_threshold() const {
        return energy_threshold_;
    }

    void set_silence_hangover_frames(int frames) {
        silence_hangover_frames_ = frames;
    }

    // Process a frame of 16-bit mono PCM samples
    bool process_frame(const int16_t* samples, size_t count) override {
        just_started_speech_ = false;
        just_ended_speech_ = false;

        if (!samples || count == 0) return is_speech_active_;

        double sum_sq = 0.0;
        for (size_t i = 0; i < count; ++i) {
            sum_sq += static_cast<double>(samples[i]) * samples[i];
        }
        float rms = static_cast<float>(std::sqrt(sum_sq / count));
        last_rms_ = rms;

        bool frame_is_loud = (rms >= energy_threshold_);

        if (frame_is_loud) {
            consecutive_speech_frames_++;
            consecutive_silence_frames_ = 0;

            if (!is_speech_active_ && consecutive_speech_frames_ >= speech_onset_frames_) {
                is_speech_active_ = true;
                just_started_speech_ = true;
            }
        } else {
            consecutive_silence_frames_++;
            consecutive_speech_frames_ = 0;

            if (is_speech_active_ && consecutive_silence_frames_ >= silence_hangover_frames_) {
                is_speech_active_ = false;
                just_ended_speech_ = true;
            }
        }

        return is_speech_active_;
    }

    bool is_speech_active() const override {
        return is_speech_active_;
    }

    bool just_started_speech() const {
        return just_started_speech_;
    }

    bool just_ended_speech() const {
        return just_ended_speech_;
    }

    float get_last_rms() const {
        return last_rms_;
    }

    int get_consecutive_speech_frames() const {
        return consecutive_speech_frames_;
    }

    int get_consecutive_silence_frames() const {
        return consecutive_silence_frames_;
    }

private:
    float energy_threshold_;
    int speech_onset_frames_;
    int silence_hangover_frames_;

    int consecutive_speech_frames_ = 0;
    int consecutive_silence_frames_ = 0;
    bool is_speech_active_ = false;
    bool just_started_speech_ = false;
    bool just_ended_speech_ = false;
    float last_rms_ = 0.0f;
};

} // namespace finn
