#include "finn/llama_engine.h"
#include "finn/conversation.h"
#include <iostream>
#include <fstream>
#include <vector>
#include <string>
#include <chrono>
#include <cstring>

static void print_usage(const char* prog) {
    std::cout << "Finn DeepSystem Host CLI\n"
              << "Usage: " << prog << " [options]\n\n"
              << "Options:\n"
              << "  -m, --model <path>      Path to GGUF model (default: models/LFM3-Omni-2B-Q4_K_M.gguf)\n"
              << "  --mmproj <path>         Path to multimodal projector (e.g. models/mmproj-LFM3-Omni-2B-Q4_K_M.gguf)\n"
              << "  -p, --prompt <text>     Text prompt\n"
              << "  --image <path>          Path to input image file (JPEG, PNG)\n"
              << "  --audio <path>          Path to input audio file (WAV)\n"
              << "  --tts <text>            Generate speech audio for text\n"
              << "  --voice <name>          Voice name for TTS\n"
              << "  -o, --output <path>     Output file for TTS audio (e.g. output.wav)\n"
              << "  --max-tokens <n>        Maximum tokens to generate (default: 256)\n"
              << "  --threads <n>           Thread count (default: 4)\n"
              << "  --temp <float>          Temperature (default: 0.7)\n"
              << "  --benchmark             Run benchmark and output timing metrics\n"
              << "  -h, --help              Show this help message\n";
}

static std::vector<uint8_t> read_binary_file(const std::string& path) {
    std::ifstream file(path, std::ios::binary | std::ios::ate);
    if (!file.is_open()) {
        std::cerr << "Failed to open file: " << path << std::endl;
        return {};
    }
    std::streamsize size = file.tellg();
    file.seekg(0, std::ios::beg);
    std::vector<uint8_t> buffer(size);
    if (file.read(reinterpret_cast<char*>(buffer.data()), size)) {
        return buffer;
    }
    return {};
}

static void write_wav_file(const std::string& path, const int16_t* pcm_samples, size_t sample_count, int sample_rate) {
    std::ofstream out(path, std::ios::binary);
    if (!out.is_open()) {
        std::cerr << "Failed to open output file: " << path << std::endl;
        return;
    }

    uint32_t pcm_bytes = static_cast<uint32_t>(sample_count * sizeof(int16_t));
    uint32_t total_data_len = pcm_bytes + 36;
    uint32_t byte_rate = sample_rate * 1 * 2;
    uint16_t block_align = 2;
    uint16_t bits_per_sample = 16;
    uint32_t subchunk1_size = 16;
    uint16_t audio_format = 1;
    uint16_t num_channels = 1;
    uint32_t srate = sample_rate;

    out.write("RIFF", 4);
    out.write(reinterpret_cast<const char*>(&total_data_len), 4);
    out.write("WAVEfmt ", 8);
    out.write(reinterpret_cast<const char*>(&subchunk1_size), 4);
    out.write(reinterpret_cast<const char*>(&audio_format), 2);
    out.write(reinterpret_cast<const char*>(&num_channels), 2);
    out.write(reinterpret_cast<const char*>(&srate), 4);
    out.write(reinterpret_cast<const char*>(&byte_rate), 4);
    out.write(reinterpret_cast<const char*>(&block_align), 2);
    out.write(reinterpret_cast<const char*>(&bits_per_sample), 2);
    out.write("data", 4);
    out.write(reinterpret_cast<const char*>(&pcm_bytes), 4);
    out.write(reinterpret_cast<const char*>(pcm_samples), pcm_bytes);
    out.close();

    std::cout << "[finn_cli] Wrote " << pcm_bytes + 44 << " bytes WAV to " << path << std::endl;
}

int main(int argc, char** argv) {
    std::string model_path = "models/LFM3-Omni-2B-Q4_K_M.gguf";
    std::string mmproj_path = "";
    std::string prompt = "Hello! Tell me in two sentences what you are capable of.";
    std::string image_path = "";
    std::string audio_path = "";
    std::string tts_text = "";
    std::string tts_voice = "";
    std::string out_audio = "output.wav";
    int max_tokens = 256;
    int threads = 4;
    float temperature = 0.7f;
    bool benchmark = false;

    for (int i = 1; i < argc; ++i) {
        std::string arg = argv[i];
        if (arg == "-m" || arg == "--model") {
            if (++i < argc) model_path = argv[i];
        } else if (arg == "--mmproj") {
            if (++i < argc) mmproj_path = argv[i];
        } else if (arg == "-p" || arg == "--prompt") {
            if (++i < argc) prompt = argv[i];
        } else if (arg == "--image") {
            if (++i < argc) image_path = argv[i];
        } else if (arg == "--audio") {
            if (++i < argc) audio_path = argv[i];
        } else if (arg == "--tts") {
            if (++i < argc) tts_text = argv[i];
        } else if (arg == "--voice") {
            if (++i < argc) tts_voice = argv[i];
        } else if (arg == "-o" || arg == "--output") {
            if (++i < argc) out_audio = argv[i];
        } else if (arg == "--max-tokens") {
            if (++i < argc) max_tokens = std::stoi(argv[i]);
        } else if (arg == "--threads") {
            if (++i < argc) threads = std::stoi(argv[i]);
        } else if (arg == "--temp") {
            if (++i < argc) temperature = std::stof(argv[i]);
        } else if (arg == "--benchmark") {
            benchmark = true;
        } else if (arg == "-h" || arg == "--help") {
            print_usage(argv[0]);
            return 0;
        }
    }

    std::cout << "[finn_cli] Loading model: " << model_path << "\n";
    if (!mmproj_path.empty()) {
        std::cout << "[finn_cli] Loading mmproj: " << mmproj_path << "\n";
    }

    finn::LlamaEngine engine;
    finn::EngineLoadConfig load_cfg;
    load_cfg.model_path = model_path;
    load_cfg.mmproj_path = mmproj_path;
    load_cfg.n_threads = threads;
    load_cfg.n_ctx = 4096;
    load_cfg.n_gpu_layers = 99; // Offload to Metal on macOS

    auto t_load_start = std::chrono::steady_clock::now();
    if (!engine.load(load_cfg)) {
        std::cerr << "[finn_cli] Failed to load engine!\n";
        return 1;
    }
    auto t_load_end = std::chrono::steady_clock::now();
    double load_time_s = std::chrono::duration<double>(t_load_end - t_load_start).count();
    std::cout << "[finn_cli] Model loaded in " << load_time_s << "s\n";

    if (!tts_text.empty()) {
        std::cout << "[finn_cli] Synthesizing speech for: \"" << tts_text << "\"\n";
        std::vector<int16_t> collected_pcm;
        int target_sample_rate = 24000;

        auto t_tts_start = std::chrono::steady_clock::now();
        bool ok = engine.speak(tts_text, [&](const int16_t* samples, size_t count, int srate) {
            target_sample_rate = srate;
            collected_pcm.insert(collected_pcm.end(), samples, samples + count);
            return true;
        }, tts_voice);
        auto t_tts_end = std::chrono::steady_clock::now();
        double tts_s = std::chrono::duration<double>(t_tts_end - t_tts_start).count();

        if (ok && !collected_pcm.empty()) {
            std::cout << "[finn_cli] Generated " << collected_pcm.size() << " samples in " << tts_s << "s\n";
            write_wav_file(out_audio, collected_pcm.data(), collected_pcm.size(), target_sample_rate);
        } else {
            std::cerr << "[finn_cli] TTS synthesis returned false or no samples.\n";
        }
        return 0;
    }

    std::vector<finn::ChatMessage> chat_history;
    finn::ChatMessage user_msg;
    user_msg.role = finn::MessageRole::User;

    if (!image_path.empty()) {
        auto img_bytes = read_binary_file(image_path);
        if (!img_bytes.empty()) {
            std::cout << "[finn_cli] Attached image: " << image_path << " (" << img_bytes.size() << " bytes)\n";
            user_msg.parts.push_back(finn::InputPart::from_image(img_bytes));
        }
    }
    if (!audio_path.empty()) {
        auto aud_bytes = read_binary_file(audio_path);
        if (!aud_bytes.empty()) {
            std::cout << "[finn_cli] Attached audio: " << audio_path << " (" << aud_bytes.size() << " bytes)\n";
            user_msg.parts.push_back(finn::InputPart::from_audio_pcm16k(aud_bytes));
        }
    }
    user_msg.parts.push_back(finn::InputPart::from_text(prompt));
    chat_history.push_back(user_msg);

    // Multimodal input parts
    std::vector<finn::InputPart> parts;
    if (!image_path.empty()) {
        auto img_bytes = read_binary_file(image_path);
        parts.push_back(finn::InputPart::from_image(img_bytes));
    }
    if (!audio_path.empty()) {
        auto aud_bytes = read_binary_file(audio_path);
        parts.push_back(finn::InputPart::from_audio_pcm16k(aud_bytes));
    }

    // Format chat prompt using Finn chat template
    std::string formatted_prompt = finn::ChatTemplate::format(chat_history);
    parts.push_back(finn::InputPart::from_text(formatted_prompt));

    finn::GenerationConfig gen_cfg;
    gen_cfg.temperature = temperature;
    gen_cfg.max_tokens = max_tokens;

    finn::EngineStats stats{};
    std::cout << "\n[finn_cli] Output:\n----------------------------------------\n";

    bool success = engine.generate(parts, gen_cfg, [](const std::string& tok) {
        std::cout << tok << std::flush;
        return true;
    }, &stats);

    std::cout << "\n----------------------------------------\n";

    if (benchmark || success) {
        std::cout << "\n[finn_cli] Benchmark Stats:\n"
                  << "  Prompt tokens:     " << stats.prompt_tokens << "\n"
                  << "  Completion tokens: " << stats.completion_tokens << "\n"
                  << "  Time to first tok: " << stats.ttft_ms << " ms\n"
                  << "  Generation speed:  " << stats.tokens_per_second << " tokens/sec\n"
                  << "  Total time:        " << stats.total_time_ms << " ms\n";
    }

    return success ? 0 : 1;
}
