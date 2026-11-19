# Changes

## 0.1.6 — Guided, repeatable handwriting training

- Check images directly in Training. New conversions retain a separate draft for each original. The screen explains how to unblock a batch and opens the next review.
- Choose a separate evaluation set automatically and retain it across batches. Known crops, shared originals and exact copies are grouped to reduce leakage. Manual evaluation flags are still supported.
- Prioritize new and revised labels with limited replay. Continue from saved output weights. A batch records the reviewed data revision, progress, outcome, and per-image text edits, math edits and scan latency.
- Require improvement with no text or math regression on any evaluation image. Keep the previous checkpoint available for restore. Room upgrades preserve existing reviewed images and notes.

## 0.1.5 — On-device output weight training

- Mark a scan correct or save corrected source for a particular image. Reviewed pairs stay private and can be exported explicitly.
- Run supervised training batches on the tablet using OvisOCR2's final decoder activations. Small sparse output weights are updated with gradient steps, then tested with actual OCR on reserved images from separate notes. A candidate that fails the comparison leaves the active checkpoint intact.
- Loaded weights run in the normal one-image path. **Compare stock model** bypasses trained weights and scan-time examples. The older scan-time examples can be enabled separately and are off by default.
- Preserve prior history with a Room migration. Labels are removed when their teaching note or its original is deleted.

## 0.1.4 — Handwriting examples

- Teach from a short, checked crop using the editor menu. The two latest confirmed examples are used locally as image-and-text demonstrations on later scans.
- Keep a separate comparison with the same model and no examples. Settings provides a switch and removal for individual examples.
- Keep samples private. A note cannot serve as its own example. Removing the teaching note or its original also removes its saved example. Validation and speed benchmarks omit examples.

## 0.1.3 — Tall PDF imports

- Render tall PDF pages in full-width sections directly from the PDF. The importer keeps more handwriting detail and looks for blank horizontal gaps around each cut. If it finds no clear gap, neighboring sections share 128 pixels and the app flags the overlap for review.
- Keep all pages and sections together in one editable result. PDF recognition uses Full detail and warns about sections with empty or truncated output.
- Reimport previously scanned PDFs from their source files to use the new rasterization. Earlier imports saved small images with the detail already lost.

## 0.1.2 — equation layout and explicit speed option

- Promote an inline-delimited formula occupying its own source line to display math, preserving the formula body exactly. Inline prose formulas, existing display math, fenced and indented code are retained. This applies to new OCR output and existing rendered previews. Operators now use display sizing and summation limits above/below, with equations aligned to the text margin. Existing saved source is not silently edited when opening a preview.
- Add a persisted Faster scan option in Scanner settings and an editor chip. Full detail remains the default (196–1600 image tokens); Faster scan uses 64–256 image tokens. This reduces image work when the budget applies, but can lose small handwriting detail. It does not cap the generated transcription or change model weights. Validation always uses full detail. Runtime improvements on the tablet are unmeasured.
- Expose native preprocessing, image/prompt evaluation and generation timings plus actual input-token count in Diagnostics. Timings use a monotonic clock. These are last-successful-inference values, not invented progress percentages or historical per-document profiles.

The user reported a 0.1.1 transcription taking 10,667 ms for 33 output tokens. This is user-supplied evidence, not a controlled benchmark against 0.1.0 or 0.1.2. The displayed duration excludes model initialization. Reducing initialization alone would not remove that reported inference time.

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
