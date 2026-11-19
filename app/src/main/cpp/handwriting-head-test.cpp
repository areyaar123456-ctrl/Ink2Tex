#include "handwriting-head.h"
#include <filesystem>
#include <iostream>
#include <stdexcept>

static void check(bool passed, const char * message) {
    if (!passed) throw std::runtime_error(message);
}

int main(int argc, char ** argv) {
    if (argc != 2) return 2;
    try {
        constexpr float a[] = {1.5f, 0.f, 0.2f, 0.f};
        constexpr float b[] = {-1.5f, 0.f, 0.2f, 0.f};
        constexpr float base[] = {0.5f, 0.f, -1.f};
        ink::HandwritingHead candidate(4, 3);
        for (int i = 0; i < 16; ++i) {
            candidate.add(a, base, 1);
            candidate.add(b, base, 0);
        }
        candidate.fit(std::atomic<bool>(false));
        check(!candidate.empty(), "Expected changed weights");
        const float unseen_a[] = {1.4f, 0.1f, 0.2f, 0.f};
        const float unseen_b[] = {-1.4f, 0.1f, 0.2f, 0.f};
        float logits_a[] = {0.5f, 0.f, -1.f};
        float logits_b[] = {0.5f, 0.f, -1.f};
        candidate.apply(unseen_a, logits_a);
        candidate.apply(unseen_b, logits_b);
        check(logits_a[1] > logits_a[0], "Training did not fix the wrong output");
        check(logits_b[0] > logits_b[1], "Training harmed the checked output");
        candidate.save(argv[1]);
        ink::HandwritingHead restored(4, 3);
        restored.load(argv[1]);
        float restored_logits[] = {0.5f, 0.f, -1.f};
        restored.apply(unseen_a, restored_logits);
        check(restored_logits[1] == logits_a[1], "Restored weights changed predictions");
        ink::HandwritingHead mismatch(3, 3);
        bool rejected = false;
        try { mismatch.load(argv[1]); } catch (const std::runtime_error &) { rejected = true; }
        check(rejected, "Incompatible head was accepted");
        std::filesystem::remove(argv[1]);
        std::cout << "PASS: supervised updates, checked control retention, persisted weights and shape validation\n";
        return 0;
    } catch (const std::exception & failure) {
        std::cerr << failure.what() << '\n';
        return 1;
    }
}
