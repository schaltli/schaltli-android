# Screensmith Android

Consumes the "Android Phone" bundle exported from the
[Screensmith designer](https://github.com/) (`v0-screenman-editor-design`,
`lib/android-export.ts`) - a `project.json` + flattened background PNGs +
TTF fonts - and renders it live over MQTT, mirroring the
[MqttEPaperDisplay2](../MqttEPaperDisplay2) firmware's topic-subscription,
button-dispatch, and tab-control semantics so the same exported project
behaves the same way on both real render targets.

## First-time setup

This project was scaffolded without opening Android Studio, so a couple of
one-time steps are needed before it builds:

1. Open Android Studio (already installed). On first launch it runs a setup
   wizard that downloads the Android SDK, platform tools, and a system
   image for the emulator - let that finish.
2. **File > Open**, select this folder (`C:\GitHub\ScreensmithAndroid`).
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
