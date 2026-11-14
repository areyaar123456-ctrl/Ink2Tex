---
license: apache-2.0
library_name: llama.cpp
pipeline_tag: image-text-to-text
base_model: ATH-MaaS/OvisOCR2
tags:
  - gguf
  - llama.cpp
  - multimodal
  - vision
  - ocr
  - document-parsing
  - omnidocbench
---

English | [简体中文](./README_zh.md)

# OvisOCR2 GGUF

GGUF builds of [ATH-MaaS/OvisOCR2](https://huggingface.co/ATH-MaaS/OvisOCR2) for llama.cpp. OvisOCR2 is a small (Qwen3.5-0.8B based) document parser that turns a page image into Markdown with LaTeX formulas and HTML tables.

Converted and quantized with **llama.cpp [v0.5.0](https://github.com/ggml-org/llama.cpp/releases/tag/v0.5.0)** (`7fe450e`). Eight language model / projector pairs were evaluated end to end on all 1,651 OmniDocBench pages. This is an unofficial conversion.

## Which files to download

You need **one language model** and **one `mmproj`** (vision projector). Use `mmproj-OvisOCR2-Q8_0.gguf` unless you have a reason not to.

| Goal | Language model | Size with Q8_0 mmproj | OmniDocBench overall |
| --- | --- | ---: | ---: |
| Best quality | `OvisOCR2-Q8_0.gguf` | 885 MiB | **96.41** |
| Same quality, smaller | `OvisOCR2-Q6_K.gguf` | 711 MiB | 96.39 |
| Balanced | `OvisOCR2-Q4_K_M.gguf` | 615 MiB | 95.89 |
| Small | `OvisOCR2-Q3_K_S.gguf` | 526 MiB | 95.65 |
| Smallest that holds up | `OvisOCR2-IQ3_XXS-imatrix.gguf` | 490 MiB | 95.20 † |

Avoid **Q2_K**: it loses about 9 points (87.30) and runs into the output-token limit on 12% of pages.

† IQ models use an importance matrix calibrated on OmniDocBench outputs. Their scores overlap with the calibration data and are not clean held-out results.

## Quick start

Download a pair and the recommended prompt:

```bash
hf download MDfox/OvisOCR2-GGUF-SOTA-20260823 \
  OvisOCR2-Q6_K.gguf mmproj-OvisOCR2-Q8_0.gguf prompt.txt --local-dir .
```

Single image:

```bash
llama-mtmd-cli -m OvisOCR2-Q6_K.gguf --mmproj mmproj-OvisOCR2-Q8_0.gguf \
  --image page.png -f prompt.txt \
  -ngl all -c 32768 -n 16384 --temp 0 --flash-attn on \
  --image-min-tokens 196 --image-max-tokens 8100
```

OpenAI-compatible server (the setup used for the benchmark):

```bash
llama-server -m OvisOCR2-Q6_K.gguf --mmproj mmproj-OvisOCR2-Q8_0.gguf \
  --jinja -ngl all -c 98304 --parallel 3 --kv-unified --flash-attn on \
  --cache-type-k bf16 --cache-type-v bf16 \
  --image-min-tokens 196 --image-max-tokens 8100
```

Send the page image followed by the text of `prompt.txt` as one user message, with `temperature: 0`, `max_tokens: 16384` and `"chat_template_kwargs": {"enable_thinking": false}`.

The files were produced with llama.cpp v0.5.0. Earlier tests also loaded the non-IQ files on v0.3.0 and v0.4.0 servers; newer releases are expected to work but are not covered by these benchmarks.

## Files

| File | MiB | Benchmarked |
| --- | ---: | --- |
| `OvisOCR2-BF16.gguf` | 1446.5 | yes (with BF16 mmproj) |
| `OvisOCR2-Q8_0.gguf` | 774.2 | yes |
| `OvisOCR2-Q6_K.gguf` | 600.6 | yes |
| `OvisOCR2-Q5_K_M.gguf` | 551.2 | — |
| `OvisOCR2-Q4_K_M.gguf` | 504.8 | yes |
| `OvisOCR2-Q3_K_M.gguf` | 444.6 | — |
| `OvisOCR2-Q3_K_S.gguf` | 415.2 | yes |
| `OvisOCR2-Q2_K.gguf` | 402.8 | yes |
| `OvisOCR2-IQ3_M-imatrix.gguf` | 432.9 | — |
| `OvisOCR2-IQ3_S-imatrix.gguf` | 429.9 | — |
| `OvisOCR2-IQ3_XS-imatrix.gguf` | 422.8 | — |
| `OvisOCR2-IQ3_XXS-imatrix.gguf` | 379.6 | yes † |
| `OvisOCR2-IQ2_M-imatrix.gguf` | 360.4 | yes † |
| `OvisOCR2-IQ2_S-imatrix.gguf` | 349.8 | — |
| `OvisOCR2-IQ2_XS-imatrix.gguf` | 347.0 | — |
| `OvisOCR2-IQ2_XXS-imatrix.gguf` | 336.0 | — |
| `mmproj-OvisOCR2-BF16.gguf` | 197.7 | yes |
| `mmproj-OvisOCR2-Q8_0.gguf` | 110.6 | yes |
| `OvisOCR2-OmniDocBench-BF16BF16Predictions-imatrix.gguf` | 1.1 | calibration data, not a model |

Every quantized language model comes straight from the BF16 conversion, and the Q8_0 projector straight from the BF16 projector; nothing was re-quantized twice. Hashes are in [`checksums.sha256`](./checksums.sha256).

## OmniDocBench results

All 1,651 pages, official end-to-end quick-match evaluator. No page failed in any run.

| Language model | mmproj | MiB | Overall ↑ | Text edit ↓ | Formula CDM ↑ | Table TEDS ↑ | Pages/min | Hit token limit |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Q8_0 | Q8_0 | 884.8 | **96.41** | 0.02453 | **97.40** | 94.27 | 12.0 | 10 |
| Q6_K | Q8_0 | 711.1 | 96.39 | 0.02451 | 97.18 | **94.46** | 13.1 | 10 |
| BF16 | BF16 | 1644.2 | 96.35 | **0.02446** | 97.09 | 94.41 | 10.3 | 9 |
| Q4_K_M | Q8_0 | 615.3 | 95.89 | 0.02555 | 96.81 | 93.41 | 13.4 | 8 |
| Q3_K_S | Q8_0 | 525.7 | 95.65 | 0.02755 | 96.30 | 93.40 | 12.0 | 21 |
| IQ3_XXS † | Q8_0 | 490.1 | 95.20 | 0.02896 | 95.97 | 92.51 | 12.1 | 16 |
| IQ2_M † | Q8_0 | 471.0 | 93.11 | 0.03375 | 91.88 | 90.82 | 10.9 | 36 |
| Q2_K | Q8_0 | 513.3 | 87.30 | 0.07550 | 83.09 | 86.38 | 5.2 | 198 |

Overall = ((1 − text edit) × 100 + formula CDM + table TEDS) / 3. Sizes are the two files combined, not VRAM. "Hit token limit" counts pages that reached 16,384 output tokens, usually because of repetition.

What the numbers say:

- Q8_0, Q6_K and BF16 are within 0.06 points of each other, so the 8-bit and 6-bit files are effectively lossless.
- Going down to Q4_K_M or Q3_K_S costs 0.5–0.8 points, mostly on tables and formulas.
- Below 3 bits quality drops fast. Q2_K repeats itself far more often, which also makes it the slowest file.

## Real-world capture (Real5-OmniDocBench)

[Real5-OmniDocBench](https://huggingface.co/datasets/PaddlePaddle/Real5-OmniDocBench) re-captures OmniDocBench pages under five real-world conditions (1,355 pages each). Only IQ3_XXS + Q8_0 mmproj was run here.

| Condition | Overall ↑ | Text edit ↓ | Formula CDM ↑ | Table TEDS ↑ |
| --- | ---: | ---: | ---: | ---: |
| Scanning | 95.04 | 0.03958 | 97.48 | 91.60 |
| Illumination | 94.40 | 0.04192 | 96.43 | 90.97 |
| Screen photography | 93.15 | 0.05800 | 94.57 | 90.69 |
| Warping | 91.76 | 0.06892 | 94.83 | 87.34 |
| Skew | 89.67 | 0.06510 | 93.62 | 81.92 |

Scanned and unevenly lit pages are close to the clean-page score; skewed pages hurt tables the most. The † calibration caveat applies here too.

## Benchmark setup

- **Data:** [OmniDocBench](https://github.com/opendatalab/OmniDocBench) at revision `aa1ee96`, 1,651 pages; Real5-OmniDocBench at revision `b3ca1d3`.
- **Scoring:** official end-to-end quick-match evaluator, run in the fixed Docker image `ghcr.io/zeng-weijun/omnidocbench-eval:repro-ubuntu2204`.
- **Inference:** llama.cpp v0.5.0 `llama-server`, CUDA, one RTX 5060 Ti 16 GB. 3 parallel slots, 32K context per slot, BF16 KV cache, flash attention, temperature 0, up to 16,384 output tokens, 196–8,100 image tokens, prompt from [`prompt.txt`](./prompt.txt).
- **Evidence:** [`benchmark-results/`](./benchmark-results) has per-run summaries, evaluator outputs, settings, GPU traces and the scripts used ([`reproduction/`](./benchmark-results/reproduction)). [`summary.csv`](./benchmark-results/summary.csv) lists every metric of every run. Raw files keep the local paths recorded at run time.

## About the IQ files

The IQ2/IQ3 files share one importance matrix computed from cleaned OmniDocBench predictions of the BF16 model. That makes their OmniDocBench and Real5 scores optimistic. If you need a fair number for these files, evaluate them on your own documents. Details: [CONVERSION_NOTES.md](./CONVERSION_NOTES.md) and [`calibration/`](./calibration).

## Changes from the previous release

The previous release (2026-09-12) was built with llama.cpp v0.4.0. This update:

- rebuilds every file with v0.5.0, which adds one metadata key; tensor data of the BF16 model is unchanged;
- adds IQ3_S, IQ3_XS and four IQ2 variants, and recalibrates the IQ files from BF16 instead of Q8_0 predictions;
- re-runs the OmniDocBench evaluation on v0.5.0 and adds Q6_K, BF16, IQ2_M and Real5 results. Q3_K_M, IQ3_M and Q8_0 + BF16 mmproj were not re-run; on v0.4.0 they scored 96.20, 95.95 and 96.45.

The v0.4.0 files and results remain available in the repository's commit history.

## License

Apache-2.0, following the [original model](https://huggingface.co/ATH-MaaS/OvisOCR2). Please cite the OvisOCR2 authors when you use this model.
