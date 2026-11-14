# LiveLens : A advance text scanner

A modern Android application demonstrating real-time on-device text and code recognition (OCR) using **Google ML Kit**, **CameraX**, and **Jetpack Compose**. Featuring full-screen edge-to-edge camera preview, floating translucent controls, and an adjustable frosted-glass output container.


---

## 🛠️ Modifications & Modernization

This project was updated from a 3-year-old baseline to modern Android toolchains, dependencies, and Jetpack APIs:

### 1. Build System & Toolchain Updates
- **Gradle & Kotlin Compatibility**: Updated to Kotlin 2.2.x, Android Gradle Plugin (AGP) 9.1.x, and target SDK 36.
- **Gradle Version Catalog**: Replaced hardcoded dependencies with `gradle/libs.versions.toml` catalog references (`libs.androidx.camera.*`, `libs.mlkit.text.recognition`, etc.).
- **Dependency Optimization**: Commented out unused template dependencies (Room, Retrofit, Firebase AI/AppCheck) to reduce APK size to ~60MB and ensure fast compilation times.

### 2. CameraX & ML Kit Upgrade
- **CameraX 1.5.0**: Upgraded from legacy CameraX dependencies to CameraX 1.5.0 (`camera-camera2`, `camera-lifecycle`, `camera-view`, and `camera-core`).
- **Modern Lifecycle Binding**: Integrated `LifecycleCameraController` with `androidx.lifecycle.compose.LocalLifecycleOwner` to safely pause/resume camera processing with Compose and Activity lifecycles.
- **ML Kit Text Recognition 16.0.1**: Upgraded `com.google.mlkit:text-recognition` to 16.0.1.
- **ImageProxy & Memory Safety**: Ensured `TextRecognitionAnalyzer` releases camera buffers immediately on completion via `imageProxy.close()` inside coroutine scope lifecycle callbacks, preventing CameraX frame stalls.

### 3. Jetpack Compose & Material 3
- **Material 3 UI Migration**: Replaced legacy Material 2 (`androidx.compose.material.Scaffold`, `TopAppBar`) with modern Material 3 (`androidx.compose.material3.*`) components.
- **Accompanist Permissions 0.37.3**: Updated camera runtime permission handling (`rememberPermissionState` / `isGranted`) for Android 14+ / 15+ / 16+.
- **Edge-to-Edge Support**: Enabled `enableEdgeToEdge()` in `MainActivity` with proper system bar padding and readable text contrast.

### 4. Manifest & App Identity
- **Permissions**: Declared `<uses-permission android:name="android.permission.CAMERA" />` and non-mandatory camera feature `<uses-feature android:name="android.hardware.camera" android:required="false" />`.
- **Adaptive Launcher Icon**: Replaced default green placeholder icons with custom multi-density adaptive launcher icons (`ic_launcher.png`, `ic_launcher_round.png`, and XML adaptive vector layers).

### 5. Programming-Code Extraction Optimizations
- **CameraX 1080p Resolution & Autofocus**: Set `imageAnalysisTargetSize` to `1920x1080` (Full HD) for sharp character edges on punctuation, colons, and brackets. Enabled `isTapToFocusEnabled` and `isPinchToZoomEnabled` for fine-grained close-up focus on screen/paper code.
- **Backpressure & Concurrency Control**: Configured `STRATEGY_KEEP_ONLY_LATEST` with an atomic non-blocking lock (`AtomicBoolean isProcessing`) and 200ms frame throttle. Ensures zero frame queue lag, zero concurrent OCR runs, and immediate buffer returns.
- **Zero-Copy Pipeline**: Extracted `InputImage.fromMediaImage` directly with rotation degrees, avoiding all intermediate Bitmap allocations and heap churn.
- **Hierarchical Reading Order & Indentation**: Traverses `TextBlock -> Line -> Element` hierarchy to cluster visual lines top-to-bottom and calculate indentation offsets from leftmost code margins using average character width.
- **Programming Symbol Normalization**: Corrects common Latin OCR misreadings for code syntax (`->`, `=>`, `!=`, `==`, `===`, `<=`, `>=`, `&&`, `||`, `::`, `?.`, `?:`, `...`, quotes, and dashes).

### 6. Full-Screen Floating Glass UI Design
- **100% Full-Screen Camera Background**: Camera preview occupies 100% of the screen area without headers, toolbars, or separate navigation bars.
- **Floating Overlaid Controls**:
  - **Top-Left**: Compact translucent glass torch/flash button with instant on/off toggle.
  - **Top-Right**: Circular Start/Stop button (Green when actively scanning, Red when stopped).
- **Translucent Glass Output Panel**:
  - **Dynamic Height Resizing**: Features a drag handle at the top of the panel. Users can hold and swipe up to expand the panel (up to ~78% screen height for inspecting longer code blocks) or swipe down to minimize it (down to 120dp for an unobstructed camera view).
  - Transparent glass styling (`rgba(15,15,15,0.35)`) with subtle border, allowing camera feed to remain fully visible underneath.
  - Monospace font with internal vertical scrolling for extracted code formatting.
  - **Action Logos (Copy, Save, Share, List)**: Positioned cleanly at the top-right inside the glass panel without text labels (icon-only design):
    - **Copy Logo**: Copies current code to clipboard with instant checkmark feedback.
    - **Save Logo**: Saves the extracted code snippet directly into persistent local storage.
    - **Share Logo**: Triggers the Android system share sheet in real time (`Intent.ACTION_SEND`).
    - **List Logo**: Navigates to the dedicated Saved Snippets page.
- **Saved Snippets Page (`SavedListScreen`)**:
  - Displays all saved code snippets in reverse chronological order with line counts and timestamps.
  - Per-item actions: One-tap copy, share sheet trigger, and delete.
  - Features an empty state view, a "Clear All" action, and system `BackHandler` integration to return smoothly to the camera scanner.



