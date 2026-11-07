# android-esc-pos

🇬🇧 [English version](README.md)

Dve minimálne Android aplikácie (Kotlin a Flutter), ktoré vytlačia text na ESC/POS
pokladničnej tlačiarni pripojenej cez **USB-serial prevodník PL2303** (USB OTG) a odrežú papier.

| Priečinok | Technológia |
|---|---|
| [`esc-kotlin/`](esc-kotlin) | Natívna Android aplikácia v Kotline (jeden súbor `MainActivity.kt`) |
| [`esc-flutter/`](esc-flutter) | Flutter aplikácia (`lib/main.dart`) + natívny USB mostík v Kotline |

Obe používajú knižnicu [usb-serial-for-android](https://github.com/mik3y/usb-serial-for-android) –
netreba root, podporuje PL2303 vrátane novších čipov **PL2303GC/GS (PID 0x23A3)**, ďalej CH340, FTDI, CP210x.

## Otestovaný hardvér

- Tlačiareň: mechanika RMF8300 / RMP8300 s presenterom RPR302/350, `115200 8N1 H/W`
- Prevodník: Prolific PL2303GC (VID `067B`, PID `23A3`)
- Telefón: Samsung SM-S948B (Android 16)

## Zostavenie a spustenie

```bash
# Kotlin
cd esc-kotlin && ./gradlew installDebug

# Flutter
cd esc-flutter && flutter run
```

Potrebné: Android SDK, JDK 17+, Flutter 3.41+. `esc-kotlin` potrebuje súbor
`local.properties` s `sdk.dir=...` (Android Studio ho vytvorí automaticky).

## Použitie

1. Zapoj prevodník PL2303 do telefónu (OTG). Aplikácia sa sama otvorí a dostane povolenie na USB.
2. Nastav baud rate (predvolene **115200**) a text.
3. Stlač **Tlačiť a odrezať**.

## Čo sa posiela (ESC/POS)

| Bajty | Príkaz |
|---|---|
| `1B 40` | `ESC @` – inicializácia tlačiarne |
| `1B 61 01` | `ESC a 1` – zarovnanie na stred |
| `1D 21 11` | `GS ! 17` – dvojitá šírka + výška |
| `ESC/POS TEST\n` | nadpis |
| `1D 21 00`, `1B 61 00` | normálna veľkosť, zarovnanie doľava |
| text + `\n` | text (bez diakritiky – tlačiareň nepoužíva UTF-8) |
| `1B 64 08` | `ESC d 8` – posun o 8 riadkov (pri menej papier uviazne v presenteri) |
| `1D 56 00` | `GS V 0` – úplné odrezanie (`01` = čiastočné) |
