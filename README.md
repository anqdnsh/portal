# PORTAL

A drag-and-drop shelf for Android, made for Samsung DeX and good on any tablet. Like Shelf on the Mac.

Drag anything toward the **bottom-left corner** (or hold **Shift** while dragging) and a small frosted
shelf springs out of the corner. Drop to keep it. Drag it out to any app later.

## Using it

- **Open:** drag into the bottom-left corner · hold Shift while dragging · Ctrl + Alt + Space · tap the corner
- **Close:** click anywhere else · Esc. The pin keeps it open.
- **Keep:** drop anywhere on the shelf. Items survive restarts.
- **Drag out:** press and pull an item (mouse), or long-press then drag (finger). Select several first to drag them together.
- **Quick actions** (drop onto one, or click it to run on the selected items):
  1. Quick Share
  2. Compress to ZIP (the zip lands back on the shelf)
  3. Your choice in settings: Share to other apps (default), Convert images to JPG, Merge PDFs, or Copy text and links
- **Remove / pin:** right-click an item, or tap the bin, then use the × or the pin on each item. Tap the bin again to clear everything that isn't pinned.
- **Send to Portal:** from any app's share menu ("Keep in Portal").

## Setup

Open PORTAL and tap **Turn on Portal (accessibility)**. Android only lets an accessibility service see
keys pressed in other apps, so this is what makes Shift and Ctrl + Alt + Space work. Portal asks only
for key events; it can't read your screen.

Prefer not to? **Use without shortcuts** runs the same shelf as a normal overlay, minus the keys.

## How it's built

- Plain Android framework + Kotlin, no libraries (same approach as OrbitSearch). minSdk 33.
- One small window in the corner changes size: an invisible strip → a glowing corner while any drag
  is happening → the open shelf. Keeping it one window means it can always catch a drag that's
  already in progress.
- Every animation is a real spring stepped each display frame (120 Hz where the screen supports it),
  so motion can reverse mid-way without jumping. The shelf grows out of the corner and goes back into it.
- Drops use Android's own drop-permission handling (`OnReceiveContentListener`), and files go back out
  through Portal's private, read-only content provider.

## Known limits

- **Blur:** Android only offers background blur to full app windows, not small floating ones like
  this, so the shelf is a frosted charcoal/white tint with fine grain instead of a live blur.
- **120 Hz:** Portal asks for the fastest mode; on an external monitor it can only be as smooth as the
  monitor, and power saving can cap it.
- **Merge PDFs** re-draws pages as images (Android has no built-in PDF editor), so text in the merged
  file isn't selectable.

## Build

GitHub Actions builds the APK on every push (Actions → latest run → `PORTAL-apk`).
To make updates install over the previous version, add repo secrets `PORTAL_KEYSTORE_B64`,
`PORTAL_KEYSTORE_PASSWORD`, `PORTAL_KEY_ALIAS`, `PORTAL_KEY_PASSWORD`.
