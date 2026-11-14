---
license: mit
base_model: zai-org/GLM-OCR
tags:
- gguf
- llama.cpp
- ocr
- latex
- lm-studio
- ollama
---

# GLM-OCR GGUF Models (for LM Studio / Ollama / llama.cpp)

GGUF conversions of [ctogaurav/GLM_OCR](https://huggingface.co/ctogaurav/GLM_OCR) — fine-tuned LoRA
adapters merged into [zai-org/GLM-OCR](https://huggingface.co/zai-org/GLM-OCR) (0.9B) and quantized
for fast, local, private CPU/GPU inference.

## 📦 Available Versions & Files

Each model requires **two files** — the quantized language model and its vision projector (`mmproj`). Download both files into the same directory:

| Version | Language Model GGUF | Multimodal Projector (`mmproj`) | Ollama Setup | Status |
|---|---|---|---|---|
| **v5.0 (Latest SOTA)** | [`v5.0/GLM-OCR-v5.0-Q8_0.gguf`](./v5.0/GLM-OCR-v5.0-Q8_0.gguf) (~682 MB) | [`v5.0/mmproj-GLM-OCR-v5.0-Q8_0.gguf`](./v5.0/mmproj-GLM-OCR-v5.0-Q8_0.gguf) (~484 MB) | [`v5.0/Modelfile`](./v5.0/Modelfile) | **Lowest CER (0.3377), 82.0% compile** |
| **v4.1** | [`v4.1/GLM-OCR-v4.1-Q8_0.gguf`](./v4.1/GLM-OCR-v4.1-Q8_0.gguf) (~683 MB) | [`v4.1/mmproj-GLM-OCR-v4.1-Q8_0.gguf`](./v4.1/mmproj-GLM-OCR-v4.1-Q8_0.gguf) (~485 MB) | [`v4.1/Modelfile`](./v4.1/Modelfile) | CER: 0.3816, 82.4% compile |
| **v3.1** | [`v3.1/GLM-OCR-v3.1-Q8_0.gguf`](./v3.1/GLM-OCR-v3.1-Q8_0.gguf) (~683 MB) | [`v3.1/mmproj-GLM-OCR-v3.1-Q8_0.gguf`](./v3.1/mmproj-GLM-OCR-v3.1-Q8_0.gguf) (~485 MB) | [`v3.1/Modelfile`](./v3.1/Modelfile) | Highest compile rate (88.9%) |

---

## ⚡ Quickstart: Running in LM Studio

1. Download `GLM-OCR-v5.0-Q8_0.gguf` and `mmproj-GLM-OCR-v5.0-Q8_0.gguf` into your LM Studio models directory:
   ```text
   ~/.lmstudio/models/ctogaurav/GLM_OCR-GGUF/
   ```
2. LM Studio will automatically recognize the multimodal projector (`mmproj`) alongside the language model.
3. In the Chat tab, load **`GLM-OCR-v5.0-Q8_0`** and drag-and-drop your math scan.

---

## 🦙 Quickstart: Running in Ollama

1. Download the files into a folder:
   ```bash
   huggingface-cli download ctogaurav/GLM_OCR-GGUF --include "v5.0/*" --local-dir ./glm_v5
   cd glm_v5/v5.0
   ```
2. Create and run the model in Ollama:
   ```bash
   ollama create glm-ocr-v5.0 -f Modelfile
   ollama run glm-ocr-v5.0 "OCR this handwritten math page. Convert ONLY the handwritten mathematical content into a complete, compilable LaTeX document. Output only LaTeX. path/to/page.png"
   ```

---

## ⚠️ Recommended Inference Settings (Crucial)

| Setting | Value | Rationale |
|---|---|---|
| **Context Length (`num_ctx`)** | **≥ 8192** | The vision encoder consumes ~1,536 tokens. Contexts below 4,096 will fail to decode. |
| **Temperature** | `0` (Greedy) | Fine-tuned and benchmarked with deterministic greedy decoding. |
| **Repeat Penalty** | `1.0` (Disabled) | Matches benchmark conditions. |
| **Max Tokens (`num_predict`)** | `1024` - `2048` | Covers full-page dense mathematical derivations. |

---

## 📊 Benchmark Scorecard (250 Held-Out Pages)

| System | Mean CER ↓ | Norm CER ↓ | Math-F1 ↑ | Compile Rate ↑ | Training Hardware |
|---|:---:|:---:|:---:|:---:|:---:|
| Base GLM-OCR | 0.5151 | 0.4910 | 0.7031 | 0.0% | — |
| GLM-OCR v3.1 | 0.3971 | 0.3753 | 0.8171 | **88.9%** | RTX 3060 |
| GLM-OCR v4.1 | 0.3816 | 0.4106 | 0.8272 | 82.4% | RTX 3060 |
| **GLM-OCR v5.0 (Ours, SOTA)** | **0.3377** 🏆 | **0.3683** 🏆 | **0.8358** 🏆 | **82.0%** | **Hybrid: Local RTX 3060 (step 0–250) ➔ Cloud A100 (step 250–3945)** |

Full training methodology, benchmark logs, and dataset curation pipeline are available at:  
👉 **[github.com/realgauravvyas/ocr2tex](https://github.com/realgauravvyas/ocr2tex)**
\n### 🖥️ Training Journey: Local RTX 3060 ➔ Step 250 Warm-Start Cloud Handoff
- **Phase 1 (Local RTX 3060 12GB):** Steps 0–250 trained locally (batch size 1, grad accum 8, FP16, max_length=3584, max_image_tokens=1536). Under continuous 100% compute, the GPU reached 88°C thermal throttling at 57.05s/step (~62.5 hour ETA).
- **Phase 2 (Cloud A100 SXM4 80GB):** Handed off from the step-250 checkpoint to Lightning AI Studio, accelerating the remaining training to 3.80s/step (15× speedup) and reaching convergence loss 0.0008 in 281 minutes.\n