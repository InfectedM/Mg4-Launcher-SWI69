# MG4 Launcher (SWI69) — InfectedM fork

Independent fork of Ernesto Micco's MG4 Launcher SWI69. Nothing is pulled from the original repository any more;
credits stay in the README ("Sobre este fork").

## How to reply
- Reply in Portuguese (pt-PT), plainly. If something is missing (a file, an APK to analyse, a tool), stop and ask.
- Code, comments, identifiers and diagnostic log keys are in **English**.
- User-visible strings go through `tools/i18n.py` (+ `tools/i18n_pl.py` for Polish), then `python3 tools/i18n.py`.
  Never hand-edit `app/res/values*/strings.xml`.

## Git and GitHub
- Ask before commit, push or release unless explicitly told to go ahead.
- Commit author: `InfectedM <6432694+InfectedM@users.noreply.github.com>`. Descriptive commit messages.
- Never delete old APKs in `releases/` or existing GitHub Releases.

## Secret / excluded files
- `tools/android.jar`, `tools/d8.jar`, `tools/apksig.jar`, `tools/*.apk` and any token are excluded from git
  (see `.gitignore`): never commit them, print them or write them into repository files.
- The fork never uploads logs anywhere: "Save log" only writes to the car (USB stick or `Download/MG4Launcher`).

## Every new version
- Bump `VERSION_CODE` and `VERSION_NAME` in `version.properties`.
- Add `news_vXYZ_*` strings and a `case "X.Y.Z"` in `MainActivity.newsFor`, otherwise the update popup shows a stale list.
- Update the README changelog (Portuguese, version numbers only, no dates), the download badge and install examples.
- Copy `build/MG4Launcher-<ver>.apk` to `releases/MG4Launcher-SWI69-<ver>.apk`, update `releases/latest.json`
  (`version`, `apk`, `size`, `notes`) and publish with `gh release create v<ver> <apk> --latest`.
  The in-car updater reads `releases/latest.json` from this fork.

## Compatibility
- SWI69 (Color Radio MY23). Must stay compatible with the original HVAC app (`ICarHvacService`) and MG4 Clima.
- Preference keys stored in SharedPreferences are persistent data: renaming one needs a one-time migration.

## Build
```bash
./build.sh
```
- Needs Java (JDK 17+), `python3`, `zip`, `unzip`, `curl`. Version comes from `version.properties`.
- Missing `tools/android.jar` / `tools/d8.jar` / `tools/apksig.jar` are downloaded from Google and SHA-256 checked.
- `tools/MG4Store.apk` is optional (bundled into the launcher only if present).
- Steps: aapt2 (resources) → javac (bytecode 8) → D8 → v1+v2 signing with `keys/platform.p12`.

## Testing
- Emulator AVD `MG4` (1920x720, API 28) with a stub `com.saicmotor.launcher` package so `Firmware.wrong()` passes.
- The emulator platform key differs from the car's: grant signature permissions with `adb shell pm grant`.
