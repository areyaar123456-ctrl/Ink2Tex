# Ink2TeX stroke recognition pilot

## Run the independent route

1. In MyScript Notes, export a handwriting-only notebook or board as **MyScript Notes (.nebo)**. Keep the PDF for comparison when the note contains typed text, diagrams, pasted images, or decorations.
2. In Ink2TeX, open **Model Store → Set up stroke models** with a connection. Ink2TeX verifies the pinned 15,549,904-byte Abel weights and runs a small inference through both local runtimes. Google manages the English ink model download.
3. Import the .nebo file or share it to Ink2TeX. Tap **Convert → ML Kit + Abel Notes**. Review against the original preview, particularly at inline formulas, line boundaries and exponents.
4. Install an OvisOCR2 image model for automatic fallback on ambiguous line groups. Open the editor's **Rerun / compare models** menu and use **Compare** to make a separate transcription. If recognition leaves ink incomplete, check the review details before copying or labeling the document.

The regular APK includes the independent route. All recognition runs locally. Model downloads require a connection. The .nebo importer supports the observed BINK 5 container with payload version 0 or 5 and layout version 1. It consults the document index for a multi-page order and reports unsupported archives so users can import PDF. The reconstruction draws pen paths in black on white. It preserves stroke points, stroke order, pressure and stroke start times in local ink files. Styles, typed text, pictures and layout objects can differ from the source note. The SVG route renders local shapes and paths as an image. SVGs with embedded images, text, CSS style sheets, external resources or symbol references must be exported as PDF.

The Abel model favors isolated formulas. Ink2TeX applies it to a whole formula or a span that lines up with the full-line text output. Crowded lines, suspected divisibility or congruence notation, unbalanced output and mismatched digits go to image OCR when available. For image-only conversion of an imported ink note, long pages are redrawn from strokes in full-detail sections. A warning records the affected page and line group. These checks are heuristics. A recognized formula still needs review.

## Licensed MyScript build

Install the Android build toolchain from the README. In the MyScript Developer dashboard, obtain an Android certificate for `dev.ink2tex.pilot` and download the matching English, Math 2 and Raw Content 2 recognition packs. Use an assets directory **outside the Git checkout**. Extract the vendor packs there so `conf/` and the relative resource paths referenced by those configuration files are intact. Store the certificate byte array as raw bytes in `myscript/license.bin` inside that directory.

```bash
./gradlew :app:assembleDebug -Pink2texMyScriptPilot=true -Pink2texMyScriptAssets=/absolute/path/to/private-assets
```

This debug variant installs alongside the regular app and offers **MyScript iink pilot** for imported Nebo notes. The first inference may require a connection to activate the certificate. Test it in airplane mode after activation. The integration uses the SDK's Raw Content recognizer and JIIX text and math blocks. It does not call the solver. A result with unmapped strokes is flagged. The certificate, vendor recognition assets and licensed APK are private inputs and excluded from the public distribution. [SDK guide](https://developer.myscript.com/docs/sdk/4.5/recognizer/recognizers/) · [On-device license management](https://developer.myscript.com/support/on-device/on-device-license-management/)

## Reproduce the formula runtime check

The Apache-licensed ONNX Runtime loads two independently generated graphs with external initializers from the original Abel Notes ABLM file. The file is pinned to revision `41e0eb791b0dcf88deb8c5461bc91bf0315dc90d` and checked by length and SHA-256 before runtime setup. The graphs are small and included with the app. The weights are distributed by Abel Notes under CC BY-SA 4.0. The attribution and upstream links follow below.

```bash
python3 scripts/export_abel_onnx.py /path/to/math_ink.ablm /path/to/check-directory
cp /path/to/math_ink.ablm /path/to/check-directory/
OPENBLAS_NUM_THREADS=2 python3 scripts/check_abel_onnx.py /path/to/check-directory
```

The export tool requires NumPy and ONNX. The check also requires ONNX Runtime. It compares encoder cross-attention tensors and cached decoder logits against a direct NumPy implementation with the same weights. That check measures runtime agreement. [Abel Notes weights and model card](https://github.com/abelnotes/abelnotes/blob/41e0eb791b0dcf88deb8c5461bc91bf0315dc90d/ml/MODEL_CARD.md) · [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/)

## Tablet acceptance

- Compare both routes with OvisOCR2 Quality using the same page bounds and reviewed sources for G4 and at least five other notes. Record missing/reordered spans, literal mistakes, and text and math edit counts, including divisibility and exponents.
- Record time from pressing Convert through the saved result, first after process restart, then for three repeat scans. Check long notes, cancellation, an interrupted model setup, deletion of shared originals, and airplane mode after setup.
- Keep each stroke route opt-in until it improves either median elapsed time or math edit counts by at least 20%, with no increase in prose or critical symbol errors and no silently missing or reordered steps. No Moto Pad timings or accuracy measurements are available in this release.
