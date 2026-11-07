# android-esc-pos

🇸🇰 [Slovenská verzia](README.sk.md)

Two minimal Android apps (Kotlin and Flutter) that print text on an ESC/POS
receipt printer connected via a **USB-serial PL2303** adapter (USB OTG) and cut the paper.

| Folder | Technology |
|---|---|
| [`esc-kotlin/`](esc-kotlin) | Native Android app in Kotlin (single `MainActivity.kt`) |
| [`esc-flutter/`](esc-flutter) | Flutter app (`lib/main.dart`) + native USB bridge in Kotlin |

Both use [usb-serial-for-android](https://github.com/mik3y/usb-serial-for-android) –
no root, supports PL2303 including the newer **PL2303GC/GS (PID 0x23A3)**, plus CH340, FTDI, CP210x.

## Tested hardware

- Printer: RMF8300 / RMP8300 mechanism with RPR302/350 presenter, `115200 8N1 H/W`
- Adapter: Prolific PL2303GC (VID `067B`, PID `23A3`)
- Phone: Samsung SM-S948B (Android 16)

## Build and run

```bash
# Kotlin
cd esc-kotlin && ./gradlew installDebug

# Flutter
cd esc-flutter && flutter run
```

Requirements: Android SDK, JDK 17+, Flutter 3.41+. `esc-kotlin` needs a
`local.properties` with `sdk.dir=...` (Android Studio creates it automatically).

## Usage

1. Plug the PL2303 adapter into the phone (OTG). The app opens automatically and gets USB permission.
2. Set the baud rate (default **115200**) and the text.
3. Tap **Tlačiť a odrezať** (Print and cut).

## What gets sent (ESC/POS)

| Bytes | Command |
|---|---|
| `1B 40` | `ESC @` – initialize printer |
| `1B 61 01` | `ESC a 1` – center |
| `1D 21 11` | `GS ! 17` – double width + height |
| `ESC/POS TEST\n` | title |
| `1D 21 00`, `1B 61 00` | normal size, left align |
| text + `\n` | body (diacritics stripped – the printer does not use UTF-8) |
| `1B 64 08` | `ESC d 8` – feed 8 lines (fewer lines jam the paper in the presenter) |
| `1D 56 00` | `GS V 0` – full cut (`01` = partial) |
