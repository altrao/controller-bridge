# Controller Bridge

Turns an Android phone into a WiFi adapter for a physical game controller. Plug in (USB/OTG) or pair (Bluetooth) a controller with the phone, and its input is sent over the LAN to [Phone2Pad Desktop](https://github.com/altrao/Phone2Pad-Desktop), which drives a virtual Xbox 360 gamepad on Windows.

Only a physical controller is supported; there is no on-screen gamepad. The only setting is the PC's IP address.

## Install

1. Open the latest successful **Build Android APK** run under this repo's **Actions** tab.
2. Download the `ControllerBridge-debug` artifact and unzip it.
3. Sideload the `.apk` on the phone (allow installs from unknown sources).

The APK is signed with the fixed debug key in `app/debug.keystore`, so new builds install as updates and keep the saved server IP.

## Use

1. Start Phone2Pad Desktop on the PC (it listens on port `60001` by default).
2. Connect the controller to the phone.
3. Enter the PC's IP address, e.g. `192.168.1.50`. Use `ip:port` if the server runs on another port.
4. Tap **Connect**. The controller shows up in Phone2Pad Desktop.

Phone and PC must be on the same network.

- **Analog triggers** are sent as 0.0–1.0. If they only read 0 or 1, the controller reports its triggers as digital buttons.
- **Xbox / guide button** is forwarded as the guide button. On some phones Android handles it as the Home key before any app sees it; this can't be prevented.
- **Debug** (left of Connect) shows a live log of connection events, controller detection and errors under the button readout. Everything is also written to logcat with the tag `ControllerBridge`.
- **Screen** stays on but dims to minimum after 30 s without a touch. Touch to restore. The UI is pitch-black to save power on OLED screens.

## Build

Every push runs `.github/workflows/build-android.yml` (JDK 17, Gradle 8.7, `gradle assembleDebug`), which uploads the APK as the `ControllerBridge-debug` artifact. There is no Gradle wrapper in the repo; to build locally, use Gradle 8.7 with JDK 17 and the Android SDK (platform 34):

```
gradle assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`.

Stack: Kotlin, minSdk 24, targetSdk 34, OkHttp (WebSocket), msgpack-core.

## Protocol

MessagePack over WebSocket, compatible with the Phone2Pad Desktop server: `handshake` → `register` (as `GAMEPAD_XBOX360`) → a stream of `input` frames → `disconnect`. Each `gamepadData` map uses the same keys as the original Unity client's `GamepadData`, plus `ps` for the guide button.
