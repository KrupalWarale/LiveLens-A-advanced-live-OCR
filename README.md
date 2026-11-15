# LiveLens — Advanced Text & Code Scanner

<p align="center">
  <img src="assets/livelens-banner.png" width="100%" alt="LiveLens" />
</p>

<p align="center">
  **Scan. Extract. Capture.**
  <br />
  Real-time text and code recognition with bulk image OCR.
</p>

<p align="center">
  ![Android](https://img.shields.io/badge/Platform-Android-3DDC84?logo=android&logoColor=white)
  ![Kotlin](https://img.shields.io/badge/Kotlin-2.2.x-7F52FF?logo=kotlin&logoColor=white)
  ![CameraX](https://img.shields.io/badge/CameraX-1.5.0-blue)
  ![ML Kit](https://img.shields.io/badge/Google-ML%20Kit-orange)
  ![Compose](https://img.shields.io/badge/UI-Jetpack%20Compose-4285F4)
</p>

---

## Overview

**LiveLens** is an Android OCR application that extracts text and programming code directly from camera frames or multiple selected images.

Built with Google ML Kit, CameraX and Jetpack Compose, it provides continuous text recognition, scrolling-based text accumulation, bulk image extraction and a customizable OCR experience.

## Features

| Feature | Description |
|---|---|
| **Live OCR** | Continuously recognize text through the camera |
| **Code Extraction** | Extract programming code and common symbols |
| **Smart Text Stitching** | Detect overlapping content and prevent duplication while scrolling |
| **Bulk Image OCR** | Select multiple images and combine their extracted text |
| **Export Formatting** | Choose separators between extracted images |
| **OCR Settings** | Configure batch size, processing speed and text cleanup |
| **Glass UI** | Full-screen camera with floating translucent controls |
| **Saved Snippets** | Save, browse, copy and share extracted content |
| **Focus Controls** | Tap-to-focus, zoom and flash controls |

## Screenshots

### Live Scanner & Extracted Text

<p align="center">
  <img src="assets/screenshots/scanner.png" width="42%" alt="Live scanner" />
  <img src="assets/screenshots/extracted-text.png" width="42%" alt="Extracted text" />
</p>

### OCR Settings

<p align="center">
  <img src="assets/screenshots/ocr-settings.png" width="42%" alt="OCR settings" />
</p>

Configure OCR processing:

- Maximum Vote Cleanup
- Batch Size
- Fast Mode FPS
- Text processing and matching options

### Bulk Image OCR

<p align="center">
  <img src="assets/screenshots/bulk-image-ocr.png" width="42%" alt="Bulk image OCR" />
  <img src="assets/screenshots/bulk-export-settings.png" width="42%" alt="Bulk export settings" />
</p>

Extract text from multiple images and combine the results into one output.

Available separators:

- New line
- Empty line
- Three underscores (`___`)
- Four dashes (`----`)
- Image numbering

## How It Works

```mermaid
flowchart TD
    A[Camera / Multiple Images] --> B[Image Processing]
    B --> C[Google ML Kit OCR]
    C --> D[Text and Line Extraction]
    D --> E[Normalization and Matching]
    E --> F[Text Accumulation]
    F --> G[LiveLens Output]
    G --> H[Copy / Save / Share]
```

## Tech Stack

- **Language:** Kotlin
- **UI:** Jetpack Compose, Material 3
- **Camera:** CameraX 1.5.0
- **OCR:** Google ML Kit Text Recognition
- **Concurrency:** Kotlin Coroutines
- **Architecture:** ViewModel and Repository pattern

## Getting Started

**Requirements:** Android Studio, compatible JDK, Android SDK 36 and an Android device.

```bash
git clone https://github.com/KrupalWarale/LiveLens.git
```

Open the project in Android Studio, sync Gradle and run the application.

Build using:

```bash
./gradlew assembleDebug
```

Windows:

```powershell
.\gradlew.bat assembleDebug
```

Camera permission is required for live scanning.

## Author

**Krupal Warale**

[GitHub](https://github.com/KrupalWarale) · [LinkedIn](https://www.linkedin.com/in/krupal-warale/)

---

<p align="center">
  **LiveLens — See it. Scan it. Capture it.**
</p>
