# APK downloads

Download `Ink2TeX-debug-arm64.apk` to install the engineering build on an ARM64 Android 12+ device. `Ink2TeX-device-tests.apk` contains instrumentation tests and is intended for development.

The app targets Android 16. APK signature and 16 KB alignment were verified; 24 JVM/Robolectric tests passed. No Android device tests were run. The real handwritten evaluation contains transcription errors. Read the project README and `docs/TESTING.md` before interpreting this as production acceptance.

`SHA256SUMS.txt` verifies the exact APKs here. Model weights are downloaded inside the app and are not stored in this repository.

Current application: **0.1.1 (versionCode 2)**. Install over the previous build to preserve models/history. First use creates a private CPU vision cache from the existing projector; no model redownload is needed. See `CHANGELOG.md` for fixes and limits.

`Ink2TeX-All-Files.zip` is the refreshed complete bundle with project source, built APKs, reports and checksums. It excludes this mirrored downloads directory to avoid recursive archives. Download directly from GitHub when Cloud workspace downloads are unavailable.
