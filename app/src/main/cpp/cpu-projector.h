#pragma once
#include <string>
#include <atomic>
std::string cpu_projector(const std::string & source, const std::atomic<bool> & cancelled);
