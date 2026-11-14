# Third-party components

- llama.cpp, MIT, exact source commit `32bdc3a89e6528d3e419024c833c5dabf32c7541`: full source and license are under `vendor/llama.cpp`. Its `vendor` directories contain additional component licenses (including stb and hashing code). The native source is linked into the APK; it is never downloaded by the app.
- KaTeX 0.16.22, MIT: bundled distribution, fonts, and license in `app/src/main/assets/katex`. Source: https://github.com/KaTeX/KaTeX/releases/tag/v0.16.22
- Material Design outlined vectors from @material-design-icons/svg 0.14.15, Apache-2.0. Only the used paths are packaged in `InkIcons.kt`; license is in `docs/MATERIAL_ICONS_LICENSE.txt`.
- AndroidX / Jetpack Compose / Room / DataStore / WorkManager / WebKit, Apache-2.0. Resolved build versions are in `app/build.gradle.kts`.
- CommonMark Java 0.24.0, BSD 2-Clause: https://github.com/commonmark/commonmark-java
- OvisOCR2 and the selected community GGUF conversion, Apache-2.0. Model weights are not redistributed in the APK/source archive. Official source: https://huggingface.co/ATH-MaaS/OvisOCR2 . Community conversion: https://huggingface.co/MDfox/OvisOCR2-GGUF-SOTA-20260823 . Immutable revisions and hashes are in the catalogue. Users download through Model Store with license links visible before installation.
- Printed runtime smoke image is generated for this project. It is not handwritten acceptance evidence.
- Public scanned handwritten evaluation sample: https://github.com/realgauravvyas/ocr2tex , exact origin in `docs/testing/sample-origin.txt`. Included as an engineering evaluation fixture with source attribution; upstream repository is MIT. It is not a standardized accuracy benchmark or a Moto Pad benchmark.

Unavailable model entries do not distribute those weights. Their license labels summarize upstream metadata and are not substitutes for the linked full terms.
