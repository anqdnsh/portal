# PORTAL — Product specification
Publisher: ATELIER TECH
Application ID: tech.atelier.portal
Version: 0.1.0

## Purpose
A temporary file shelf for Android productivity workflows, inspired by Dropover.

## Platforms
Android phones/tablets, Samsung One UI and DeX.

## Visual direction
Minimal premium interface: charcoal and black surfaces, blue accent, muted grey metadata, off-white text.

## Prototype scope
- Kotlin + Jetpack Compose
- Multi-file import using Android Storage Access Framework
- Persist read permission where supported
- Display filenames and MIME types
- Remove individual files or clear shelf
- Adaptive tablet layout

## Roadmap
1. Drag-and-drop using Android APIs
2. Receive files from Android share sheet
3. Floating shelf overlay with explicit permission and foreground-service compliance
4. DeX-specific sizing, keyboard shortcuts and pointer polish
5. Cursor-shake summon experiment; Android does not provide a universal global mouse-motion hook to ordinary apps
6. Persistence, accessibility, testing and release hardening

## Privacy
No account, analytics, ads or backend in this prototype. Files are selected explicitly by the user; no broad storage permission is requested.
