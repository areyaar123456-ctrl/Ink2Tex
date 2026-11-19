#pragma once

#include <atomic>
#include <cstdint>
#include <map>
#include <string>
#include <vector>

namespace ink {

// A learned sparse residual on the frozen output matrix. Rows are keyed by base token ID.
class HandwritingHead {
public:
    HandwritingHead(int dimension, int vocabulary);

    bool empty() const { return rows.empty(); }
    void apply(const float * hidden, float * logits) const;
    void add(const float * hidden, const float * logits, int32_t expected);
    void fit(const std::atomic<bool> & cancelled);
    void save(const std::string & path) const;
    void load(const std::string & path);
    size_t training_tokens() const { return samples.size(); }

private:
    static constexpr int CANDIDATES = 8;
    static constexpr int MAX_ROWS = 192;
    static constexpr size_t MAX_SAMPLES = 14'000;
    struct Choice { int32_t token; float logit; };
    struct Sample {
        std::vector<float> hidden;
        std::vector<Choice> choices;
        int32_t expected;
    };
    int dimension;
    int vocabulary;
    float scale;
    std::map<int32_t, std::vector<float>> rows;
    std::vector<Sample> samples;
};
}
