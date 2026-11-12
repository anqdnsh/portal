# PORTAL — Product specification
Publisher: ATELIER TECH
Application ID: tech.atelier.portal
Version: 0.3.0

## Product goal
A compact, persistent file drop shelf for Samsung One UI and DeX, inspired by the quick-collect workflow of Dropover.

## Visual direction
Premium minimal interface: black and charcoal surfaces, white text, muted grey metadata and a clear blue accent. The floating shelf remains compact while collapsed and expands only when requested.

## Implemented in the current 0.3 build
- Import multiple files through Android's document picker.
- Receive single and multiple files through Android's share sheet.
- Copy selected files into app-private storage so the shelf does not depend on temporary provider URI access.
- Persistent shelf across app restarts.
- Open, share, remove and clear collected files.
- Drag a collected file out of the app using Android global drag-and-drop.
- Optional floating overlay with a compact draggable tab, expandable mini shelf, file drop target and quick open action.
- Foreground notification and explicit overlay permission flow.

## Platform constraints
- Android global drag-and-drop works only when the source app exposes a compatible drag operation and the target accepts it. Some Samsung/third-party apps may not expose draggable file URIs.
- A normal Android app cannot continuously observe global mouse movement or intercept arbitrary Shift+mouse gestures while another app is active. Cursor-shake activation is therefore not promised. The floating overlay and Android share sheet are the supported collection entry points.
- Samsung DeX and One UI versions differ. A successful CI build does not prove every OEM drag-and-drop path works on every device; device testing is required.

## Privacy
No account, analytics, ads or backend. Files are copied only after the user selects or drops them and remain in app-private storage until removed or the app is uninstalled.
