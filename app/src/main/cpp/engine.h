#pragma once
#include <string>
#include <atomic>
namespace ink {
extern std::atomic<bool> cancelled;
extern std::atomic<int> phase;
extern std::atomic<int> generated;
void load(const std::string & model, const std::string & projector, int threads, const std::string & backend_directory = "");
void unload();
struct Result { std::string text; int tokens; bool truncated; };
Result recognize(const unsigned char * rgb, int width, int height, const std::string & prompt, int max_tokens);
}
