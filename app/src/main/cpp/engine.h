#pragma once
#include <string>
#include <atomic>
namespace ink {
extern std::atomic<bool> cancelled;
void load(const std::string & model, const std::string & projector, int threads);
void unload();
struct Result { std::string text; int tokens; bool truncated; };
Result recognize(const unsigned char * rgb, int width, int height, const std::string & prompt, int max_tokens);
}
