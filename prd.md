# Product Requirement Document (PRD): Dynamic Wallpaper Manager

## 1. Project Overview & Core Intent
The goal is to build an Android wallpaper automation application that bypasses standard Android Media Store limitations to index hidden folders (folders starting with a dot or containing `.nomedia` files). The app will feature advanced, conditional playlist (album) switching based on time, calendar dates, geofencing, and user-defined logic rules.

## 2. Technical Architecture & Storage
* **Storage Strategy (Local):** Save file paths only. Do not duplicate or cache local media files.
* **Storage Strategy (Cloud):** Include a global toggle for "Cache Online Sources." When enabled, the app can pull and cache images from web URLs or Google Photos.
* **File Access Mechanism:** Use Kotlin file API queries with `MANAGE_EXTERNAL_STORAGE` permissions to read directly from `.hidden` and `.nomedia` directories in real-time.
* **Error Handling (Missing Files):** * If a file path is dead/deleted, the wallpaper rotation engine must **silently skip** it to prevent app crashes.
    * The UI must display an error icon next to the album showing the count of missing photos.
    * Include an album-level toggle: *"Show placeholders for missing photos."* When active, a placeholder replaces the missing image inside the album view to help users identify and clean up dead paths.

## 3. Wallpaper Rendering Engine
* **Targets:** Supported configurations include **Home Screen Only**, **Lock Screen Only**, or **Both Simultaneously** (User-selectable).
* **Scaling & Aspect Ratios:** * Supported Modes: `Fill`, `Stretch`, or `Fit (with black borders)`.
    * Granularity: Configuration can be set globally, per-album, or overridden per-photo.

## 4. Automation & Rule Engine
* **Conflict Resolution:** Rules operate on a **strict priority hierarchy**. The user can drag-and-drop to reorder the list; the rule at the top of the list has the highest priority and wins in a conflict.
* **Location Tracking:** Support native Android location accuracy tiers. The user can toggle between *Loose* (Cell-tower/Wi-Fi based for battery saving) and *Precise* (Pinpoint GPS).
* **Override Behavior:**
    * If a user manually selects an album, it overrides all automation until the *next* scheduled rule trigger occurs.
    * Include a *"Continue Override"* toggle. When checked, the manual selection locks in permanently until explicitly unchecked by the user.

## 5. User Interface (UI) Profiles
The application must support three distinct rule-building interfaces depending on user preference:
1. **Easy Mode:** A traditional UI (similar to screenshot.png) featuring two configuration panels, supplemented by a 3rd logic-statement selection box underneath.
2. **Hobbyist Mode:** A visual, node-graph flowchart interface for connecting triggers, logic gates (`AND`, `OR`, `NOT`), and actions.
3. **Expert Mode:** A raw text-editor interface that parses a custom `YAML` payload defining all rules, priorities, and conditions.

## 6. Gesture & Sensor Interaction
The app must register background listeners for the following interactions to trigger quick-actions (e.g., *Next Wallpaper*, *Change Album*, *Freeze Current Photo*):
* **On-Screen Gestures (if supported by launcher/accessibility service):**
    * Double Tap
    * Triple Tap
    * Two-Finger Double Tap
    * Three-Finger Triple Tap
* **Hardware Sensors:** * Integrate with Android's sensor framework to detect a **Double-Tap on the back of the device** (similar to Pixel's Quick Tap feature).