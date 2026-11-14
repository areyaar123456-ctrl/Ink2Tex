---
base_model: lightonai/LightOnOCR-3-0.8B
library_name: gguf
pipeline_tag: text-generation
tags:
  - gguf
  - llama.cpp
  - quantized
---

# LightOnOCR-3-0.8B GGUF

GGUF quantizations of [lightonai/LightOnOCR-3-0.8B](https://huggingface.co/lightonai/LightOnOCR-3-0.8B), covering 30 files (14.1 GB total).

## Files

| File | Quant | Size | Notes |
|---|---|---:|---|
| [LightOnOCR-3-0.8B-BF16.gguf](https://huggingface.co/NANI-Nithin/LightOnOCR-3-0.8B-GGUF/blob/main/LightOnOCR-3-0.8B-BF16.gguf) | `BF16` | 1.41 GB | Full precision source. Every quant below is cut from this file. |
| [LightOnOCR-3-0.8B-Q8_0.gguf](https://huggingface.co/NANI-Nithin/LightOnOCR-3-0.8B-GGUF/blob/main/LightOnOCR-3-0.8B-Q8_0.gguf) | `Q8_0` | 0.76 GB | Effectively lossless. Use when disk and RAM are not the constraint. |
| [LightOnOCR-3-0.8B-Q6_K.gguf](https://huggingface.co/NANI-Nithin/LightOnOCR-3-0.8B-GGUF/blob/main/LightOnOCR-3-0.8B-Q6_K.gguf) | `Q6_K` | 0.59 GB | Near-lossless; the last stop before quality becomes measurable. |
| [LightOnOCR-3-0.8B-Q5_K_M.gguf](https://huggingface.co/NANI-Nithin/LightOnOCR-3-0.8B-GGUF/blob/main/LightOnOCR-3-0.8B-Q5_K_M.gguf) | `Q5_K_M` | 0.54 GB | Very good quality, noticeably smaller than Q6_K. |
| [LightOnOCR-3-0.8B-Q5_K_S.gguf](https://huggingface.co/NANI-Nithin/LightOnOCR-3-0.8B-GGUF/blob/main/LightOnOCR-3-0.8B-Q5_K_S.gguf) | `Q5_K_S` | 0.52 GB | Slightly smaller than Q5_K_M for a slight quality cost. |
| [LightOnOCR-3-0.8B-Q5_1.gguf](https://huggingface.co/NANI-Nithin/LightOnOCR-3-0.8B-GGUF/blob/main/LightOnOCR-3-0.8B-Q5_1.gguf) | `Q5_1` | 0.55 GB | Legacy. Prefer Q5_K_M. |
| [LightOnOCR-3-0.8B-Q5_0.gguf](https://huggingface.co/NANI-Nithin/LightOnOCR-3-0.8B-GGUF/blob/main/LightOnOCR-3-0.8B-Q5_0.gguf) | `Q5_0` | 0.52 GB | Legacy. Prefer Q5_K_M. |
| [LightOnOCR-3-0.8B-Q4_K_M.gguf](https://huggingface.co/NANI-Nithin/LightOnOCR-3-0.8B-GGUF/blob/main/LightOnOCR-3-0.8B-Q4_K_M.gguf) | `Q4_K_M` | 0.49 GB | The usual default. Best quality-per-byte for most people. |
| [LightOnOCR-3-0.8B-Q4_K_S.gguf](https://huggingface.co/NANI-Nithin/LightOnOCR-3-0.8B-GGUF/blob/main/LightOnOCR-3-0.8B-Q4_K_S.gguf) | `Q4_K_S` | 0.47 GB | A little smaller than Q4_K_M, a little worse. |
| [LightOnOCR-3-0.8B-IQ4_NL.gguf](https://huggingface.co/NANI-Nithin/LightOnOCR-3-0.8B-GGUF/blob/main/LightOnOCR-3-0.8B-IQ4_NL.gguf) | `IQ4_NL` | 0.48 GB | Non-linear 4-bit; good on hardware without fast K-quant kernels. |
| [LightOnOCR-3-0.8B-IQ4_XS.gguf](https://huggingface.co/NANI-Nithin/LightOnOCR-3-0.8B-GGUF/blob/main/LightOnOCR-3-0.8B-IQ4_XS.gguf) | `IQ4_XS` | 0.47 GB | Best sub-4.5bpw option; usually beats Q4_K_S at a smaller size. |
| [LightOnOCR-3-0.8B-Q4_1.gguf](https://huggingface.co/NANI-Nithin/LightOnOCR-3-0.8B-GGUF/blob/main/LightOnOCR-3-0.8B-Q4_1.gguf) | `Q4_1` | 0.50 GB | Legacy. Prefer Q4_K_M. |
| [LightOnOCR-3-0.8B-Q4_0.gguf](https://huggingface.co/NANI-Nithin/LightOnOCR-3-0.8B-GGUF/blob/main/LightOnOCR-3-0.8B-Q4_0.gguf) | `Q4_0` | 0.47 GB | Legacy round-to-nearest. Prefer Q4_K_M unless a runtime needs this. |
| [LightOnOCR-3-0.8B-Q3_K_L.gguf](https://huggingface.co/NANI-Nithin/LightOnOCR-3-0.8B-GGUF/blob/main/LightOnOCR-3-0.8B-Q3_K_L.gguf) | `Q3_K_L` | 0.46 GB | Small, with real quality loss. Usable when RAM is tight. |
| [LightOnOCR-3-0.8B-Q3_K_M.gguf](https://huggingface.co/NANI-Nithin/LightOnOCR-3-0.8B-GGUF/blob/main/LightOnOCR-3-0.8B-Q3_K_M.gguf) | `Q3_K_M` | 0.43 GB | Smaller again; noticeable degradation. |
| [LightOnOCR-3-0.8B-IQ3_M.gguf](https://huggingface.co/NANI-Nithin/LightOnOCR-3-0.8B-GGUF/blob/main/LightOnOCR-3-0.8B-IQ3_M.gguf) | `IQ3_M` | 0.42 GB | Strong at ~3.7bpw, clearly better than Q3_K_M. |
| [LightOnOCR-3-0.8B-IQ3_S.gguf](https://huggingface.co/NANI-Nithin/LightOnOCR-3-0.8B-GGUF/blob/main/LightOnOCR-3-0.8B-IQ3_S.gguf) | `IQ3_S` | 0.42 GB | Slightly smaller than IQ3_M. |
| [LightOnOCR-3-0.8B-Q3_K_S.gguf](https://huggingface.co/NANI-Nithin/LightOnOCR-3-0.8B-GGUF/blob/main/LightOnOCR-3-0.8B-Q3_K_S.gguf) | `Q3_K_S` | 0.41 GB | Aggressive. Prefer IQ3_M at a similar size. |
| [LightOnOCR-3-0.8B-IQ3_XS.gguf](https://huggingface.co/NANI-Nithin/LightOnOCR-3-0.8B-GGUF/blob/main/LightOnOCR-3-0.8B-IQ3_XS.gguf) | `IQ3_XS` | 0.41 GB | Aggressive but coherent. |
| [LightOnOCR-3-0.8B-IQ3_XXS.gguf](https://huggingface.co/NANI-Nithin/LightOnOCR-3-0.8B-GGUF/blob/main/LightOnOCR-3-0.8B-IQ3_XXS.gguf) | `IQ3_XXS` | 0.37 GB | Very aggressive; the last coherent step down. |
| [LightOnOCR-3-0.8B-Q2_K.gguf](https://huggingface.co/NANI-Nithin/LightOnOCR-3-0.8B-GGUF/blob/main/LightOnOCR-3-0.8B-Q2_K.gguf) | `Q2_K` | 0.39 GB | Very small, heavily degraded. For experimentation. |
| [LightOnOCR-3-0.8B-IQ2_M.gguf](https://huggingface.co/NANI-Nithin/LightOnOCR-3-0.8B-GGUF/blob/main/LightOnOCR-3-0.8B-IQ2_M.gguf) | `IQ2_M` | 0.35 GB | The smallest size most people find usable. |
| [LightOnOCR-3-0.8B-Q2_K_S.gguf](https://huggingface.co/NANI-Nithin/LightOnOCR-3-0.8B-GGUF/blob/main/LightOnOCR-3-0.8B-Q2_K_S.gguf) | `Q2_K_S` | 0.39 GB | Smaller than Q2_K, at a further quality cost. |
| [LightOnOCR-3-0.8B-IQ2_S.gguf](https://huggingface.co/NANI-Nithin/LightOnOCR-3-0.8B-GGUF/blob/main/LightOnOCR-3-0.8B-IQ2_S.gguf) | `IQ2_S` | 0.34 GB | Below the usual usability line. |
| [LightOnOCR-3-0.8B-IQ2_XS.gguf](https://huggingface.co/NANI-Nithin/LightOnOCR-3-0.8B-GGUF/blob/main/LightOnOCR-3-0.8B-IQ2_XS.gguf) | `IQ2_XS` | 0.34 GB | Experimental. |
| [LightOnOCR-3-0.8B-IQ2_XXS.gguf](https://huggingface.co/NANI-Nithin/LightOnOCR-3-0.8B-GGUF/blob/main/LightOnOCR-3-0.8B-IQ2_XXS.gguf) | `IQ2_XXS` | 0.33 GB | Experimental. |
| [LightOnOCR-3-0.8B-Q2_0.gguf](https://huggingface.co/NANI-Nithin/LightOnOCR-3-0.8B-GGUF/blob/main/LightOnOCR-3-0.8B-Q2_0.gguf) | `Q2_0` | 0.34 GB | Extreme, group-64. Included for completeness. |
| [LightOnOCR-3-0.8B-IQ1_M.gguf](https://huggingface.co/NANI-Nithin/LightOnOCR-3-0.8B-GGUF/blob/main/LightOnOCR-3-0.8B-IQ1_M.gguf) | `IQ1_M` | 0.32 GB | Extreme. Expect substantial degradation. |
| [LightOnOCR-3-0.8B-IQ1_S.gguf](https://huggingface.co/NANI-Nithin/LightOnOCR-3-0.8B-GGUF/blob/main/LightOnOCR-3-0.8B-IQ1_S.gguf) | `IQ1_S` | 0.31 GB | Extreme. Expect substantial degradation. |
| [LightOnOCR-3-0.8B-Q1_0.gguf](https://huggingface.co/NANI-Nithin/LightOnOCR-3-0.8B-GGUF/blob/main/LightOnOCR-3-0.8B-Q1_0.gguf) | `Q1_0` | 0.27 GB | Extreme. Included for completeness. |

## Which one should I download?

Pick the largest file that leaves a couple of gigabytes of headroom on the device you will run it on — the model has to fit in RAM (or VRAM, if you are offloading) alongside the KV cache and the OS.

- Plenty of memory: **Q6_K** or **Q8_0**.
- The usual choice: **Q4_K_M**.
- Tight on memory: **IQ4_XS**, then **IQ3_M**, then **IQ2_M**.
- The `IQ*` files generally beat a `Q*` file of similar size, at the cost of slightly slower inference on some hardware.

## Usage

```bash
llama-cli -hf NANI-Nithin/LightOnOCR-3-0.8B-GGUF:Q4_K_M -p "Hello"
```

Or download one file and point at it directly:

```bash
huggingface-cli download NANI-Nithin/LightOnOCR-3-0.8B-GGUF LightOnOCR-3-0.8B-Q4_K_M.gguf --local-dir .
llama-cli -m LightOnOCR-3-0.8B-Q4_K_M.gguf -p "Hello"
```

---

Quantized with [llama.cpp](https://github.com/ggml-org/llama.cpp) by AgentQuantix on 2026-10-12. Licensing follows the base model.
