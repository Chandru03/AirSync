# AirSync

**An open-source AirPods 4 (ANC) companion for Android** — battery, listening modes, automatic ear detection, Conversation Awareness, head gestures and more, built with Jetpack Compose and Material 3.

AirPods work on Android as plain Bluetooth headphones: no battery levels, no noise-control switching, no ear detection. AirSync talks to the AirPods over the same private control channel an iPhone uses, so those features work on Android too.

> **Tested with:** Samsung Galaxy Z Fold 8 + AirPods 4 (ANC).
> Other Android phones and AirPods models may work; see [Compatibility](#compatibility).

---

## Features

| | Feature | Notes |
|---|---|---|
| 🔋 | **Battery** — left, right and case, 1 % precision | The case reports while a bud is docked with the lid open; the last reading is shown with its age |
| 🎧 | **Listening modes** — Noise Cancellation, Transparency, Adaptive, Off | Switches the AirPods themselves and follows stem presses live |
| 👂 | **Automatic ear detection** | Pauses when you take an AirPod out, resumes when you put it back (only if AirSync paused it) |
| 🗣️ | **Conversation Awareness** | Uses the **AirPods' own microphones**, like on iPhone; media volume fades down while you speak and back up afterwards |
| 🙂 | **Head gestures** | Nod to answer a call, shake your head to decline — SIM calls and app calls (WhatsApp etc.) |
| 🔗 | **One-tap connect** | Quick Settings tile and in-app button connect already-paired AirPods in ~2 s |
| ⚡ | **Quick Settings tile** | Cycle ANC → Transparency → Adaptive from the notification shade |
| 🏠 | **Home-screen widget** | Battery and one-tap mode switching |
| 🎚️ | **Equalizer** — Balanced, Bass Boost, Vocal Clarity, plus per-app presets | For apps that expose an audio-effect session (Samsung Music, Spotify, …) |
| 🌐 | **Spatial audio status** | Shows whether Android's spatializer is active for your AirPods |
| 🧭 | **Smart modes** | Rule-based switching: walking → Transparency, noisy → ANC, still → Adaptive |
| 📍 | **Find my AirPods** | Last-seen location on a map and a loud locator tone through the buds |
| 📱 | **Built for foldables** | Two-pane layout on the inner display (hinge-aware), single column on the cover screen |

The UI follows Material 3 with dynamic colour, and every animation respects Android's *Remove animations* accessibility setting.

---

## How it works

### The AAP control channel
iPhones control AirPods through Apple's private **Apple Accessory Protocol (AAP)**, carried over a classic Bluetooth L2CAP channel (PSM `0x1001`). Battery reports, ear detection, listening-mode changes, Conversation Awareness and head-tracking data all travel over it.

Android has no public API for classic L2CAP sockets — its public L2CAP API only creates LE sockets. The hidden `BluetoothDevice.createInsecureL2capSocket(psm)` does exist but is on the hidden-API blocklist. AirSync uses [LSPosed's HiddenApiBypass](https://github.com/LSPosed/AndroidHiddenApiBypass) to lift that filter **inside its own process only** — no root, nothing on the device is modified.

### Why not BLE advertisements?
Most AirPods apps for Android read the "proximity pairing" Bluetooth LE advertisement. **AirPods 4 on current firmware don't broadcast it** (zero such frames across thousands of captured Apple adverts), so AirSync uses AAP as its primary source and keeps BLE parsing only as a fallback for older models.

### Head gestures
Gesture detection is a small, deterministic algorithm (`HeadGestureDetector`), not ML:
1. Gravity (a slow low-pass of the AirPods' accelerometer) defines a head-aligned frame — an AirPod sits tilted in the ear, so its raw axes can't be trusted.
2. Rotation around gravity is a **shake**; rotation perpendicular to it is a **nod**.
3. A gesture fires only when one component clearly dominates and swings back and forth several times, so a single glance doesn't count.

It is unit-tested against a real recorded nod/shake sequence (`app/src/test/resources/airpods4_nod_then_shake.csv`). The motion stream runs only while a call is ringing.

### Conversation Awareness
The AirPods detect your voice with their own microphones and stream a "duck level". AirSync ramps media volume toward that level one step at a time and always restores the exact volume you had before.

---

## Limitations

Some things are not possible from an Android app, and AirSync says so in the UI rather than faking them:

- **Case speaker / chime** — triggered through Apple's Find My network with a key tied to the owner's Apple ID. Not reachable from Android. (*Play sound* plays a loud tone through the buds instead.)
- **Case battery with no bud docked** — AirPods 4 don't advertise it; the last docked reading is shown with its age.
- **Head-tracked spatial audio** — Android's spatializer only accepts head tracking from a system head-tracker sensor or a privileged system API. AirSync shows spatial audio status, but can't feed AirPods head motion into it.
- **Equalizer** — only applies to apps that publish an audio-effect session.

---

## Compatibility

| | Status |
|---|---|
| Samsung Galaxy Z Fold 8 + AirPods 4 (ANC) | ✅ Tested |
| Other Android 12+ phones | Likely to work; the AAP channel depends on the phone's Bluetooth stack allowing classic L2CAP |
| AirPods Pro 2 / AirPods 4 (non-ANC) | Expected to work for shared features; untested |
| Older AirPods | Battery/ear detection via BLE fallback; AAP features vary |

Reports from other devices are very welcome — please open an issue with your phone, Android version and AirPods model.

---

## Building

Requirements: **Android Studio** (recent), **JDK 17+**, Android SDK with compile SDK 37.

```bash
git clone https://github.com/Chandru03/AirSync.git
cd AirSync
./gradlew :app:assembleDebug        # build
./gradlew :app:testDebugUnitTest    # unit tests
./gradlew :app:installDebug         # install on a connected phone
```

On first launch, grant **Nearby devices** (required). Optional features ask for their permissions when you turn them on.

### Permissions

| Permission | Used for |
|---|---|
| Nearby devices (Bluetooth connect/scan) | Talking to the AirPods — required |
| Notifications | Background status notification |
| Location | Find my AirPods last-seen position (optional) |
| Microphone | Ambient-noise rule in Smart modes (optional; Conversation Awareness does **not** use the phone mic) |
| Physical activity | Walking/still detection for Smart modes (optional) |
| Phone + notification access | Head gestures for SIM and app calls (optional) |

---

## Project structure

```
app/src/main/java/com/example/airsync/
├── data/
│   ├── bluetooth/          BluetoothService, AirPodsConnector, BLE parser
│   │   └── aap/            AAP protocol codec + L2CAP client
│   ├── audio/              Media control, volume ducking, EQ, locator tone, spatializer
│   ├── repository/         AirPodsRepositoryImpl — merges AAP / BLE / headset-profile data
│   ├── settings/           DataStore preferences
│   ├── location/           Last-seen location
│   └── context/            Activity recognition + Smart-modes controller
├── domain/                 Models, repository contracts, AutomationEngine, HeadGestureDetector
├── service/                Foreground service, Quick Settings tiles, call gestures
├── ui/                     Compose UI (adaptive dashboard, components, motion, theme)
└── widget/                 Home-screen widget
tools/aap_from_pklg.py      Extracts AAP/GATT traffic from Apple PacketLogger captures
```

Architecture: MVVM + clean architecture, Kotlin coroutines/Flow, Hilt, DataStore, WorkManager. Protocol parsing, automation rules, gesture detection and ear-detection logic are pure Kotlin and covered by unit tests.

---

## Contributing

Issues and pull requests are welcome — especially:
- compatibility reports from other phones and AirPods models,
- new AAP messages (please document the bytes and how you captured them),
- UI and accessibility improvements.

Please **never attach raw Bluetooth captures** (`.pklg`, `btsnoop`) to issues: they contain device addresses and pairing key material. Share only the relevant decoded bytes.

---

## Credits

AirSync builds on public research by the AirPods-on-other-platforms community:
- [LibrePods](https://github.com/librepods-org/librepods) — AAP protocol documentation
- [CAPod](https://github.com/d4rken-org/capod) and [OpenPods](https://github.com/adolfintel/OpenPods) — BLE advertisement research
- [furiousMAC/continuity](https://github.com/furiousMAC/continuity) — Apple Continuity message formats
- [LSPosed/AndroidHiddenApiBypass](https://github.com/LSPosed/AndroidHiddenApiBypass) — hidden-API access

AirSync's own code is an independent implementation; no code from GPL-licensed projects is included.

## Disclaimer

AirSync is an independent, unofficial project. It is not affiliated with, endorsed by, or sponsored by Apple, Google or Samsung. AirPods is a trademark of Apple Inc.; Android is a trademark of Google LLC; Galaxy is a trademark of Samsung Electronics. AirSync uses undocumented protocols that may change with any firmware update — use at your own risk.

## License

[Apache License 2.0](LICENSE)
