# PORTAL
**By ATELIER TECH**

PORTAL is a compact, persistent file drop shelf for Android, designed for Samsung One UI and DeX.

## Current 0.3 functionality
- Import multiple files through Android's document picker.
- Receive files through Android's share sheet.
- Copy collected files into private app storage for persistence.
- Open, share, remove and clear collected files.
- Drag files out of the shelf using Android global drag-and-drop.
- Enable a small floating PORTAL tab that expands into a mini shelf and accepts compatible file drops.
- Dark black/grey interface with blue accent.

## Platform notes
Android does not expose unrestricted global mouse-motion monitoring to ordinary apps. Cursor-shake and universal Shift+drag interception cannot be guaranteed. Drag-and-drop depends on the source app exposing file URIs and on Android/DeX accepting the global drag operation.

## Build
Open in Android Studio with JDK 17 and Android SDK 35, sync Gradle, then run the app. Build with `gradle testDebugUnitTest assembleDebug`.

Publisher: ATELIER TECH
