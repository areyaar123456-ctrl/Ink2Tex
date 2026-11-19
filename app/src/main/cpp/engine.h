#pragma once
#include <string>
#include <vector>
#include <atomic>
#include <cstdint>
namespace ink {
extern std::atomic<bool> cancelled;
extern std::atomic<int> phase;
extern std::atomic<int> generated;
extern std::atomic<int64_t> input_tokens, preprocessing_ms, evaluation_ms, generation_ms;
void load(const std::string & model, const std::string & projector, int threads, const std::string & backend_directory = "", int image_token_budget = 1600);
void unload();
struct Result { std::string text; int tokens; bool truncated; };
struct Example {
 std::vector<unsigned char> rgb;
 int width;
 int height;
 std::string transcription;
};
Result recognize(const unsigned char * rgb, int width, int height, const std::string & prompt,
                 int max_tokens, const std::vector<Example> & examples = {}, int head_mode = 0);
void load_head(const std::string & path);
void begin_training();
void train_sample(const unsigned char * rgb, int width, int height,
                  const std::string & prompt, const std::string & transcription);
int fit_training();
void save_candidate(const std::string & path);
}
