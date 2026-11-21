# Ink2TeX

A real Android/Kotlin application for a floating, user-authorized handwriting scanner with local mathematical-document OCR. **Engineering build 0.1.8.**

## Import handwritten strokes

Import a compatible MyScript Notes (.nebo) export, then choose a recognition engine. **Model Store → Set up stroke models** installs the ML Kit English and Abel Notes assets. The optional licensed build adds MyScript iink. Both routes are experiments for mixed prose and equations. SVG import uses image OCR. [Setup, supported files, and acceptance tests](docs/STROKE_PILOT.md)

## Train handwriting weights on Android

1. Open **Training → Review next image**, or use **Mark correct** and **Submit corrected source** from a scan. For fresh PDF and multi-image conversions, each original has its own draft.
2. Check images from at least two separate notes. Ink2TeX picks and locks a separate evaluation set when you start training. You can still reserve a checked note explicitly by selecting **Reserve this note for evaluation**.
3. Tap **Train on tablet**. Each batch prioritizes new or revised labels, uses up to 12 training images with limited replay, and checks up to three evaluation images before applying new weights.
4. Review the saved scorecard. Ink2TeX installs a candidate only if at least one text or math score improves and neither score worsens on any evaluation image. **Restore previous weights** returns to the last checkpoint. **Compare stock model** runs the same note with stock weights.

Long evaluation images are split automatically at clear gaps between lines. Both sets of weights read the same ordered snips, and the joined text is scored against your existing checked source. Batches with an incomplete evaluation from 0.1.6 can be retried. For long training images, select **Review → Split into snips** to create a new note of ordered crops. Convert it, then check each crop's source.

Training uses supervised updates to a compact set of output weights. Later batches start from the installed checkpoint. The encoder and decoder stay frozen. Everything runs on the tablet. Training and scan times on the Moto Pad still need device measurements. Whole-model retraining remains unavailable in this Android runtime.

## Optional scan-time examples

1. Snip one or two representative lines. Check the result against the original and correct **Source**.
2. Open the editor menu and choose **Save scan-time example → Save example**. Enable examples in Settings.
3. Scan a different note. Ink2TeX includes up to two saved image-and-text pairs in local inference. The result says when examples were used.
4. Use **Compare without examples** in that result’s menu to run the same model on a separate copy. In **Settings**, turn examples off or remove them individually.

The examples give the installed model context during recognition. This approach does not train model weights or learn from unreviewed scans. Added images can slow inference, and an example can still make recognition worse. Only explicitly saved examples are used. Saving a third replaces the oldest. Deleting a teaching note or its original removes the related example.

## 0.1.3 PDF imports

Reimport your original PDF to use the new reader. Tall pages are rendered as full-width sections at a readable scale. Tap **Original** to review each section in order. PDF conversion uses Full detail, and any empty, truncated or overlapping sections are flagged for review. Verify exponents and divisibility symbols before using the transcription.

## 0.1.2 equation layout and scan speed

Standalone equations now render as display mathematics with full-size operators and above/below summation limits; the expression itself is unchanged. Existing saved results receive this preview formatting, and new OCR output uses display delimiters for standalone math lines. Inline formulas embedded in prose remain inline.

Tap the **Full detail** chip in the editor to select **Faster scan**, or use Scanner settings. Faster scan lowers the vision budget for clear, larger writing and may miss small symbols; rerun Full detail for dense proofs. Full detail remains the default. Diagnostics now separate image preparation, image/prompt evaluation and text generation and report actual input tokens. No particular new timing is claimed; the user reported 10,667 ms for 33 output tokens on 0.1.1, and this build has not been timed on the tablet.

## 0.1.1 runtime repair

Install the new APK over 0.1.0; the signing certificate is unchanged. Keep app data to retain downloaded models and notes. Force-stop the old process before updating if a conversion is stuck. The first model load prepares an app-private float32 vision cache from the existing BF16 projector (about twice the projector download size in extra storage); it does not redownload weights. CPU backends now select supported ARM features at runtime and enable the optimized matrix multiplication path. Native startup failures resolve as errors, queued cancellation no longer cancels another engine owner, and progress reports actual native stages, elapsed time and generated token counts. See [change details](CHANGELOG.md). Target-tablet speed and stability still require verification.

## Open and build

1. Open this directory in Android Studio with JDK 17.
2. Install Android SDK Platform 36, Build Tools 36.0.0, NDK 28.2.13676358, and CMake 3.22.1 through SDK Manager. Android Studio can install the declared native toolchains. Set your SDK path in `local.properties` if Studio does not create it.
3. Run:

   ```sh
   ./gradlew :app:assembleDebug :app:testDebugUnitTest
   ```

4. APK: `app/build/outputs/apk/debug/app-debug.apk`. This build packages **arm64-v8a**, targets Android 16 / API 36, and requires Android 12 / API 31 or later. Install the actual built debug APK on the Moto Pad, allowing installation from your file manager when Android asks.

Gradle wrapper, Kotlin source, resources, Room schema, native JNI code, and pinned llama.cpp source are included. Internet is needed for Gradle dependencies and initial model installation. There is no Python/Termux/server setup for the tablet user. App model payloads are downloaded by the app; they are not embedded in the APK.

## First use

1. Open **Model Store**, inspect the model and license, and download **OvisOCR2 Quality** or **Compact**. The app checks capacity, downloads both required files, checks SHA-256 and runs local image inference. The model becomes Ready only after validation passes.
2. Start **Conversion Mode**. Grant overlay access and choose a display/app through Android's capture consent dialog. The ongoing notification and system capture indicator remain visible.
3. Return to Nebo. Tap the floating bubble, choose **Rectangular Snip Scan**, draw a region on the frozen screenshot, and tap **Convert selection**.
4. Review the original, editable Markdown + LaTeX and rendered result. Copy or export after checking uncertain symbols. On a wide tablet screen the panels appear together.
5. Tap **Stop Conversion Mode** in the panel or notification to end capture authorization.

The panel also offers screenshot, freeform, multi-snip and manually guided scrolling scans. Imports use the Android file picker. Tall PDF pages are rendered directly in bounded sections. Long image exports use tiles; check the boundaries. No Accessibility Service is requested.

The benchmark action uses a clearly labelled printed runtime smoke image and records real time/tokens. For handwriting acceptance use the supplied scanned evaluation image or your own Nebo export; compare actual source fidelity. Do not interpret the smoke benchmark as handwriting accuracy.

## What is and is not supported

OvisOCR2 has a packaged CPU Qwen3.5/mtmd adapter and two downloadable quantization variants. A shared C++ host test exercises real vision inference. Android install-time validation still runs on the user's device. Additional requested families are researched and honestly marked **Not yet compatible** in this build, with no installation button. See [model compatibility](docs/MODEL_COMPATIBILITY.md).

The supplied handwritten evaluation has visible transcription errors and **does not pass strict fidelity acceptance**; see [the raw review](docs/testing/handwriting-review.md). This delivery does **not** establish Moto Pad speed, thermal stability, peak RAM, Nebo overlay reliability or handwritten-math accuracy. No Android target was connected in the build environment. See [executed and pending tests](docs/TESTING.md), [feature status](docs/FEATURE_STATUS.md), [Android 16 audit](docs/ANDROID_16_AUDIT.md) and [architecture](docs/ARCHITECTURE.md).

## Developer checks

```sh
python3 scripts/verify_registry.py
./gradlew :app:lintDebug :app:assembleDebugAndroidTest
# With an authorized Android device attached:
./gradlew :app:connectedDebugAndroidTest
```

The registry verifier can check downloaded host artifacts with `--files /path/to/model-files`. To build the shared host-native evaluation tool (developer-only, never needed on the tablet):

```sh
cmake -S app/src/main/cpp -B host-build -DCMAKE_BUILD_TYPE=Release
cmake --build host-build --parallel 2
host-build/ink2tex-host /path/to/OvisOCR2-Q8_0.gguf /path/to/mmproj-OvisOCR2-BF16.gguf app/src/main/assets/validation.png docs/ovis-prompt.txt
```

Host evaluation outputs and provenance are in `docs/testing`. No cloud inference backend, note upload or analytics code is present. Models, screenshots and history stay in private app storage. License notices are in [THIRD_PARTY.md](THIRD_PARTY.md).

## Downloadable updates

After each project update, rebuild and check any changed Android code, then run:

```sh
python3 scripts/package_release.py
```

This creates `../Ink2TeX-deliverables/Ink2TeX-All-Files.zip` containing the full project, the latest built application and device-test APKs, documentation, test evidence and checksums. A separate source-only ZIP is also refreshed. Machine-specific SDKs, build caches and local settings are excluded; the app downloads model weights itself. Packaging verifies archive integrity but does not rebuild the APKs or rerun tests.

## GitHub repository and APKs

Target repository: [areyaar123456-ctrl/Ink2Tex](https://github.com/areyaar123456-ctrl/Ink2Tex).

**Publication status:** the project and APKs are published on `main`. [Download the entire repository as a ZIP](https://github.com/areyaar123456-ctrl/Ink2Tex/archive/refs/heads/main.zip), or use the individual APK links below. See [publishing notes](docs/GITHUB_PUBLICATION.md).

- [Download the complete project and APK ZIP](https://raw.githubusercontent.com/areyaar123456-ctrl/Ink2Tex/main/downloads/Ink2TeX-All-Files.zip).
- [Download the committed ARM64 debug APK](https://github.com/areyaar123456-ctrl/Ink2Tex/raw/refs/heads/main/downloads/Ink2TeX-debug-arm64.apk).
- [Download the compiled device-test APK](https://github.com/areyaar123456-ctrl/Ink2Tex/raw/refs/heads/main/downloads/Ink2TeX-device-tests.apk).
- [APK checksums](https://github.com/areyaar123456-ctrl/Ink2Tex/blob/main/downloads/SHA256SUMS.txt).

These APKs are engineering builds. Device tests were compiled, not executed; handwriting fidelity and Moto Pad/Nebo acceptance remain open as described above. The `downloads/` directory mirrors built APKs for GitHub readers. The downloadable complete bundle includes those APKs once under `apks/`, while its source-only ZIP excludes the mirrored download directory.
