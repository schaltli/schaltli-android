# Schaltli Android

Consumes the "Android Phone" bundle exported from the
[Schaltli designer](https://github.com/) (`schaltli-designer`,
`lib/android-export.ts`) - a `project.json` + flattened background PNGs +
TTF fonts - and renders it live over MQTT, mirroring the
[schaltli-eink](../schaltli-eink) firmware's topic-subscription,
button-dispatch, and tab-control semantics so the same exported project
behaves the same way on both real render targets.

## First-time setup

This project was scaffolded without opening Android Studio, so a couple of
one-time steps are needed before it builds:

1. Open Android Studio (already installed). On first launch it runs a setup
   wizard that downloads the Android SDK, platform tools, and a system
   image for the emulator - let that finish.
2. **File > Open**, select this folder (`C:\GitHub\schaltli-android`).
   Android Studio will notice the Gradle wrapper's `gradle-wrapper.jar` is
   missing (only the wrapper's small `.properties` file was hand-written)
   and offer to regenerate it automatically during Gradle Sync - accept
   that prompt. If it doesn't offer, run `gradle wrapper` once from a
   terminal with a system Gradle install to generate it.
3. Let Gradle Sync finish, then Run (the green ▶ button) on an emulator or
   a physical device with USB debugging enabled.

## Project layout

- `data/` - `project.json` models, bundle import/parsing, the `"#jsonpath"`
  composite-topic-reference resolver (ported from the designer's
  `lib/json-path.ts`).
- `mqtt/` - broker connection, topic subscription, and button-action
  dispatch (mirrors `Application::dispatchButtonAction` in the firmware).
- `ui/` - Compose screens: import, settings, and the screen renderer
  (background PNG + live dynamic-object overlays).
- `render/` - the shared arc-level rasterizer, ported from the designer's
  `lib/arc-raster.ts`, plus the Adafruit_GFX rounded-rect primitive a
  Switch's marker bar is drawn with. Both are integer arithmetic on
  purpose: they exist in the designer and in each firmware too, and the
  copies have to produce the same pixels. `ArcSinTable.kt` is generated -
  do not edit it, regenerate with the designer's
  `scripts/gen-arc-sin-table.js`.
- `ddf/` - this device's Device Description File, built at runtime from the
  screen this phone actually has (`DdfBuilder`), served at
  `http://<phone>:8080/ddf.zip` (`DdfServer`) and pointed at from the
  retained MQTT `hello` (`MqttRepository.setAnnouncement`). It used to be a
  file checked into the designer declaring a fixed 360x800, which is one
  size for a class of devices whose sizes all differ - see the designer's
  `docs/2026-09-21-android-self-announce.md`.

## What it renders

Every object type `DdfBuilder`'s own list declares, which as of
2026-08-29 is the same set the Waveshare Knob-1.8 firmware renders:
`label`, `MqttDataField`, `MQTTIconField`, `level-indicator`,
`MqttDataLine`, `arc-level`, `SoftwareButton`, `Switch`, `tab-control` /
`panel`, and `box` / `line` / `icon` (those three arrive already baked into
each screen's flattened background PNG).

Navigation is the project's decision, not the app's: `swipe-left`,
`swipe-right`, `swipe-up` and `swipe-down` are bound per screen in the
designer's Swipe Navigation panel and arrive as ordinary button actions.
One of them can be bound to the `showScreenMenu` device action, which opens
the list of screens (`ui/ScreenMenuOverlay.kt`).

## Tests

```
gradle testDebugUnitTest
```

Holds `render/ArcRaster.kt` to the exact numbers the designer's own copy
produces, from a golden file the designer records
(`hil/android/fixtures/build-arc-golden.js` there). No device, no emulator,
no broker. The designer's `npm run test:all` runs it too, because the golden
is generated from that repo and a change there is what invalidates it.

Everything else is verified against a real phone by the designer's
`hil/android/` suite - see `hil/README.md` there.
