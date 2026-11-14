# Handwritten proof evaluation — fidelity review

One real scanned topology solution was run through the shared native C++ core with OvisOCR2 Q8_0 and the BF16 vision projector. See `sample-origin.txt`, `handwritten-evaluation.png`, `initial-evaluation-prompt.txt`, `host-handwriting-output.md` and `host-handwriting.log`.

The runtime returned **363 generated tokens**, reached end-of-generation and did **not** hit the 2048-token limit. The full page was one image input. Printed hypotheses, handwritten reasoning, mathematical expressions and footer text appeared in their broad original sequence. No separate text/equation selection or separate OCR mode was used.

This sample **does not pass strict transcription acceptance**. Visible errors include:

- The handwritten phrase “all possible open sets” became “all horrible open sets”.
- Several occurrences of “open sets” became “then sets”.
- The small interline addition “some or all of” in part (i) was omitted.
- Printed bracket notation around membership was interpreted as vertical bars.
- The struck or ambiguous mark before “empty set” was transcribed as `=g`, and the footer was misread.

Powers such as `2^K-1`, `2^K` and `2^(2^n)` were emitted as LaTeX among the prose, but these successes do not compensate for missing or changed source content. There is no calibrated confidence estimate. The UI marks every OCR result for comparison with the original; it must not present this result as verified accurate.

The initial test used the upstream prompt. An experiment adding a long fidelity instruction caused the printed smoke image to lose inline LaTeX delimiters; its raw output and log are retained as `host-smoke-fidelity-experiment-*`. The packaged model therefore uses the exact upstream transcription prompt. The application never applies algebraic or reasoning corrections, but model-generated content still needs careful review.

The elapsed time in the handwriting log includes a deliberate process suspension and heavy build contention. It is invalid as a latency benchmark, and the host CPU is not the target Dimensity 8300. Neither Android accuracy nor acceptable interactive speed has been established. A production release requires a representative Nebo handwriting dataset, measured character/symbol/ordering accuracy, and actual target-device profiling.
