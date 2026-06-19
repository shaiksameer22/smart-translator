# Smart Translator

Fully offline Android translator. Image / PDF / text / speech → translation, all on-device via Google ML Kit — no network needed after the language pack is downloaded.

## Features

- **On-device OCR** — scan images or photos (Latin, Hindi/Devanagari, Chinese, Japanese, Korean scripts)
- **PDF translation** — translate every page of multi-hundred-page PDFs; output is a **layout-preserving** PDF (headings, columns, tables and reading order kept, Google-Translate style) saved to Downloads
- **Long-text translation** — paste or type any length; chunked + translated reliably
- **Speech** — speak to translate (speech-to-text) and hear results (text-to-speech)
- **~59 languages** via ML Kit, with offline language-pack management in Settings
- **History** of past translations
- **Light / dark / system theme**

## Requirements

- Android 12 (API 32) or newer
- ~30 MB free per downloaded language pack
- Wi-Fi recommended for the first download of each language pack (downloads stall on some mobile networks)

## Installation

### Option A — Install the APK

1. Download `app-debug.apk` from the latest build (or [Releases](../../releases)).
2. On the device, enable **Settings → Apps → Special access → Install unknown apps** for your file manager/browser.
3. Open the APK and tap **Install**.

### Option B — Build from source

```bash
# 1. Clone
git clone https://github.com/shaiksameer22/smart-translator.git
cd smart-translator

# 2. Build the debug APK
./gradlew assembleDebug
# output: app/build/outputs/apk/debug/app-debug.apk

# 3. Install on a connected device (USB debugging on)
./gradlew installDebug
```

Or open the project in **Android Studio** and click **Run**.

## Build / test

```bash
./gradlew testDebugUnitTest        # unit tests
./gradlew connectedDebugAndroidTest # instrumented tests (device required)
```

## Tech stack

Kotlin · Jetpack Compose (Material 3) · Google ML Kit (OCR, Translation, Language ID) · Android `PdfRenderer` / `PdfDocument` · DataStore
