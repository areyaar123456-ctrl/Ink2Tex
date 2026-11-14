# Changes

## 0.1.1 — runtime and queue repair

The user reported conversions stuck at “Reading handwriting”; diagnostics showed a previous 21-token inference took 531,515 ms. That is evidence of unacceptable latency, not evidence that the current process has crashed. The exact current stall cannot be diagnosed without target-device traces.

- Replace the forced baseline ARM build with packaged CPU variants selected by upstream hardware probes; enable optimized llamafile matrix multiplication. Keep a baseline fallback.
- Avoid the pinned runtime’s scalar ARM BF16 dot-product path by streaming an exact float32 copy of BF16 projector tensors into a private cache. Other weights and processor settings are unchanged. Expect approximately twice the projector file size in extra cache storage and higher vision-weight RAM. Downloads remain intact; existing models need no redownload.
- Catch native library initialization failures inside the executor so pending callers receive an error instead of waiting indefinitely.
- Stop cancelling the shared engine when a queued worker is cancelled before acquiring it. Active JNI cancellation remains scoped to its operation.
- Report native loading, CPU-cache preparation, preprocessing, image/prompt evaluation, generated token count and monotonic elapsed time. Installation validation uses an indeterminate progress indicator rather than a full download bar.
- Trim only an exactly uniform border from the inference copy, retaining all non-background pixels and padding. Original captures remain unchanged.

Validation: debug and device-test APKs compiled; 24 JVM/Robolectric tests passed; native cache fixture passed; lint 0 errors/39 warnings; APK signature and native 16 KB alignment verified. No attached Android device, target-device speedup, thermal profile or new OCR accuracy result is claimed. Existing handwritten fidelity limitations remain.

Install over the previous engineering APK to preserve app-private models and history. Stop/force-stop the old conversion first, then retry one small snip. The first load may show CPU-cache preparation.
