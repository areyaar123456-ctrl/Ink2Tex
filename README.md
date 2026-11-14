# Ink2TeX

A real Android/Kotlin application for a floating, user-authorized handwriting scanner with local mathematical-document OCR. **Engineering build 0.1.0; not a device-certified production release.** It contains no mock OCR or hardcoded conversion output.

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

The panel also offers screenshot, freeform, multi-snip and manually guided scrolling scans. Imports use the Android file picker. PDF pages are processed at a bounded raster resolution. Long image exports use tiles; check the boundaries. No Accessibility Service is requested.

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

- [Download the committed ARM64 debug APK](https://github.com/areyaar123456-ctrl/Ink2Tex/raw/refs/heads/main/downloads/Ink2TeX-debug-arm64.apk).
- [Download the compiled device-test APK](https://github.com/areyaar123456-ctrl/Ink2Tex/raw/refs/heads/main/downloads/Ink2TeX-device-tests.apk).
- [APK checksums](https://github.com/areyaar123456-ctrl/Ink2Tex/blob/main/downloads/SHA256SUMS.txt).

These APKs are engineering builds. Device tests were compiled, not executed; handwriting fidelity and Moto Pad/Nebo acceptance remain open as described above. The `downloads/` directory mirrors built APKs for GitHub readers. The downloadable complete bundle includes those APKs once under `apks/`, while its source-only ZIP excludes the mirrored download directory.
