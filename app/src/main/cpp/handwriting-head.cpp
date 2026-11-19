#include "handwriting-head.h"
#include <algorithm>
#include <cmath>
#include <cstring>
#include <fstream>
#include <numeric>
#include <random>
#include <stdexcept>

namespace ink {
namespace {
constexpr char MAGIC[] = "INKHEAD1";
constexpr float MAX_LOGIT_CHANGE = 3.0f;

template<class T> void write(std::ostream & out, const T & value) {
    out.write(reinterpret_cast<const char *>(&value), sizeof(value));
}

template<class T> T read(std::istream & input) {
    T value{};
    if (!input.read(reinterpret_cast<char *>(&value), sizeof(value))) {
        throw std::runtime_error("Handwriting weights are incomplete.");
    }
    return value;
}

float dot(const float * hidden, const std::vector<float> & row) {
    return std::inner_product(row.begin(), row.end(), hidden, 0.0f);
}
}

HandwritingHead::HandwritingHead(int dimension, int vocabulary)
    : dimension(dimension), vocabulary(vocabulary), scale(1.f / std::sqrt(float(dimension))) {
    if (dimension < 1 || dimension > 4096 || vocabulary < 2 || vocabulary > 400000) {
        throw std::runtime_error("This model is incompatible with handwriting weights.");
    }
}

void HandwritingHead::apply(const float * hidden, float * logits) const {
    for (const auto & [token, row] : rows) {
        logits[token] += std::clamp(dot(hidden, row) * scale, -MAX_LOGIT_CHANGE, MAX_LOGIT_CHANGE);
    }
}

void HandwritingHead::add(const float * hidden, const float * logits, int32_t expected) {
    if (!hidden || !logits || expected < 0 || expected >= vocabulary || !std::isfinite(logits[expected])) {
        throw std::runtime_error("The model did not return usable training values.");
    }
    if (samples.size() >= MAX_SAMPLES) throw std::runtime_error("Training batch exceeds the token budget.");

    // Sampling the strongest alternatives keeps training bounded while including real base logits.
    std::vector<Choice> choices;
    choices.reserve(CANDIDATES + 1);
    for (int32_t token = 0; token < vocabulary; ++token) {
        if (token == expected || !std::isfinite(logits[token])) continue;
        const float value = logits[token];
        if (choices.size() == CANDIDATES && value <= choices.back().logit) continue;
        auto position = std::lower_bound(choices.begin(), choices.end(), value,
            [](const Choice & choice, float score) { return choice.logit > score; });
        choices.insert(position, {token, value});
        if (choices.size() > CANDIDATES) choices.pop_back();
    }
    for (int i = 0; i < dimension; ++i) {
        if (!std::isfinite(hidden[i])) throw std::runtime_error("Invalid model activation.");
    }
    choices.push_back({expected, logits[expected]});
    samples.push_back({std::vector<float>(hidden, hidden + dimension), std::move(choices), expected});
}

void HandwritingHead::fit(const std::atomic<bool> & cancelled) {
    if (samples.empty()) throw std::runtime_error("No training tokens available.");

    // Concentrate capacity on tokens involved in mistakes. Correct labels also regularize these rows.
    std::map<int32_t, int> mistakes;
    for (const auto & sample : samples) {
        if (sample.choices.size() > 1 && sample.choices.front().logit >= sample.choices.back().logit) {
            ++mistakes[sample.expected];
            ++mistakes[sample.choices.front().token];
        }
    }
    std::vector<std::pair<int32_t, int>> ranked(mistakes.begin(), mistakes.end());
    std::stable_sort(ranked.begin(), ranked.end(), [](auto a, auto b) { return a.second > b.second; });
    for (size_t i = 0; i < std::min<size_t>(ranked.size(), MAX_ROWS); ++i) {
        rows.emplace(ranked[i].first, std::vector<float>(dimension, 0.f));
    }

    std::vector<size_t> order(samples.size());
    std::iota(order.begin(), order.end(), 0);
    std::mt19937 rng(314159);
    for (int epoch = 0; epoch < 6; ++epoch) {
        std::shuffle(order.begin(), order.end(), rng);
        for (size_t i : order) {
            if (cancelled) throw std::runtime_error("Training cancelled.");
            const auto & sample = samples[i];
            float scores[CANDIDATES + 1];
            float maximum = sample.choices.back().logit - MAX_LOGIT_CHANGE;
            for (size_t j = 0; j < sample.choices.size(); ++j) {
                const auto & choice = sample.choices[j];
                const auto weight = rows.find(choice.token);
                const float residual = weight == rows.end() ? 0.f : dot(sample.hidden.data(), weight->second) * scale;
                scores[j] = choice.logit + std::clamp(residual, -MAX_LOGIT_CHANGE, MAX_LOGIT_CHANGE);
                maximum = std::max(maximum, scores[j]);
            }
            float denominator = 0.f;
            for (size_t j = 0; j < sample.choices.size(); ++j) {
                scores[j] = std::exp(scores[j] - maximum);
                denominator += scores[j];
            }
            for (size_t j = 0; j < sample.choices.size(); ++j) {
                const auto & choice = sample.choices[j];
                auto weight = rows.find(choice.token);
                if (weight == rows.end()) continue;
                const float step = 0.22f * scale * ((choice.token == sample.expected ? 1.f : 0.f) - scores[j] / denominator);
                auto & row = weight->second;
                for (int k = 0; k < dimension; ++k) {
                    row[k] = std::clamp(row[k] * 0.9998f + step * sample.hidden[k], -2.f, 2.f);
                }
            }
        }
    }
    samples.clear();
    samples.shrink_to_fit();
}

void HandwritingHead::save(const std::string & path) const {
    std::ofstream out(path, std::ios::binary | std::ios::trunc);
    out.write(MAGIC, sizeof(MAGIC));
    write<int32_t>(out, dimension);
    write<int32_t>(out, vocabulary);
    write<uint32_t>(out, rows.size());
    for (const auto & [token, row] : rows) {
        write(out, token);
        out.write(reinterpret_cast<const char *>(row.data()), row.size() * sizeof(float));
    }
    out.close();
    if (!out) throw std::runtime_error("Unable to save handwriting weights.");
}

void HandwritingHead::load(const std::string & path) {
    std::ifstream input(path, std::ios::binary);
    char magic[sizeof(MAGIC)]{};
    if (!input.read(magic, sizeof(magic)) || std::memcmp(magic, MAGIC, sizeof(magic)) ||
        read<int32_t>(input) != dimension || read<int32_t>(input) != vocabulary) {
        throw std::runtime_error("Handwriting weights do not match this model.");
    }
    const auto count = read<uint32_t>(input);
    if (count > MAX_ROWS) throw std::runtime_error("Handwriting weights exceed the row limit.");
    std::map<int32_t, std::vector<float>> loaded;
    for (uint32_t i = 0; i < count; ++i) {
        const auto token = read<int32_t>(input);
        if (token < 0 || token >= vocabulary || loaded.count(token)) {
            throw std::runtime_error("Invalid token in handwriting weights.");
        }
        auto & row = loaded[token];
        row.resize(dimension);
        for (float & value : row) {
            value = read<float>(input);
            if (!std::isfinite(value) || std::abs(value) > 2.f) {
                throw std::runtime_error("Invalid value in handwriting weights.");
            }
        }
    }
    if (input.peek() != EOF) throw std::runtime_error("Unexpected data in handwriting weights.");
    rows.swap(loaded);
}
}
