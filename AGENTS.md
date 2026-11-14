# Ink2TeX project workflow

The user's delivery preference is a downloadable ZIP after every project update. Keep the existing engineering-build and testing limitations accurate.

- After updating the app, run the appropriate build/checks, then run `python3 scripts/package_release.py`.
- The packager produces a source archive and a complete bundle containing source, built APKs, documentation, test evidence and checksums in the sibling `Ink2TeX-deliverables` directory.
- Include a clickable link to the refreshed complete bundle in the final response after each update.
- If Android application sources changed, rebuild the APK before packaging. Packaging by itself does not build or test the application. Do not describe an older APK or test report as newly built or verified.
- The user also accepts commits through the GitHub plugin as an alternative when a target repository has been identified. Do not claim a commit or push occurred unless it actually succeeded.

## Confirmed GitHub destination

The user identified `https://github.com/areyaar123456-ctrl/Ink2Tex.git` as this project's repository and authorized project commits through the GitHub plugin. Publish updates to this repository, preserve unrelated changes, and never force-push. Maintain the downloadable ZIP as well. Keep the APKs in `downloads/` synchronized with the current successful Android build and update their checksums.
