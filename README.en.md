# coorlay

[Русский](README.md) · **English**

Experimental CarPlay for the factory head unit of the **Geely Coolray**
(SX11, Neusoft / GKUI, Android 4.3 / API 18). The app is installed on the head unit
and receives CarPlay video, touch, audio and microphone from an iPhone, over USB
and wirelessly (Bluetooth + Wi-Fi).

coorlay is a port of [DiPlay](https://github.com/shihabal3amri/DiPlay) (itself based
on [xcertplay](https://github.com/shilapi/xcertplay)) to the old Android 4.3 platform.
The project is not certified by Apple and is not affiliated with Apple, Geely or Neusoft.

> **Status:** version `1.0.0`, experimental. Other head units and other firmware
> versions have not been tested and are not supported.

> [!WARNING]
> **Disclaimer.** coorlay is an experimental hobby project. It is provided "as is",
> without any warranty (GPL-3.0, sections 15–16). You install it at your own risk:
> third-party apps and the root installer may break the head unit and may void its
> warranty. The authors are not liable for malfunctions, data loss or any other damage.
> Do not get distracted by the screen while driving and obey traffic rules.

## Supported device

| Parameter | Value |
| --- | --- |
| Car | Geely Coolray / SX11 |
| Head unit | Neusoft, `ro.product.board=C3ALFUS` |
| Firmware | `05.05.*` – `05.07.*` |
| System | Android 4.3 / API 18, `armeabi-v7a` |
| Screen | 1920×640, 160 dpi |

## Features

- CarPlay over USB (USBMUX, Lockdown, iAP2, NCM) and wireless CarPlay
  (pairing through the factory ANW Bluetooth; Wi-Fi via the head unit's hotspot or Wi-Fi Direct).
- H.264 video, touch, audio (including AAC-LC over Wi-Fi), Siri and microphone.
- Steering wheel buttons; track info and navigation arrows on the instrument cluster.
- Starts with the head unit and connects automatically.
- Diagnostic log export; Russian and English interface.
- Install/update from a USB stick as root and a Wi-Fi Direct fix —
  see [RootInstall/ROOT-INSTALL.md](RootInstall/ROOT-INSTALL.md) (in Russian).

## Keys

**The repository contains no keys.** A build needs two sets of them in `build-inputs/`
(both folders are in `.gitignore`):

```text
build-inputs/
├── runtime-assets/
│   └── offline-mfi/
│       ├── identity.pk8      # MFi accessory private key (PKCS#8)
│       └── certificate.p7b   # accessory certificate (PKCS#7)
└── signing/
    ├── coorlay.keystore      # APK signing key
    └── signing.properties    # storePassword, keyAlias, keyPassword
```

1. **MFi accessory keys.** The experimental keys were taken from the original
   [DiPlay](https://github.com/shihabal3amri/DiPlay) repository — they ship in its public
   preview APK. According to the DiPlay authors, this pair was extracted from public
   Carlinkit C2Air firmware; these are not keys issued by Apple for coorlay or DiPlay.
   Whether iPhones will keep accepting them after future iOS updates is unknown.
2. **APK signing key.** Without `signing.properties` the debug keystore defaults apply
   (`android` / `androiddebugkey`), so for your own build you can copy
   `~/.android/debug.keystore` to `coorlay.keystore`. Official coorlay APKs are signed with
   the author's key; an APK with a different signature will not install over them —
   uninstall the installed version first.

The build stops with an error if any of these files is missing. The paths can be
overridden with `COORLAY_AUTH_ASSETS_DIR` (folder containing `offline-mfi/`),
`COORLAY_SIGNING_STORE_FILE` and `COORLAY_SIGNING_PROPERTIES`.

## Building

Requirements:

- JDK 25 (the JBR bundled with Android Studio works);
- Android SDK Platform 37, Build Tools 37.0.0, NDK 23.2.8568313;
- Gradle 9.5.0 is downloaded by the wrapper; Android Gradle Plugin 9.3.0.

```bash
./gradlew :coolray43:assembleRelease
```

The APK is written to `coolray43/build/outputs/apk/release/coolray43-release.apk`.
In Android Studio, open the repository root and run the **Build APK** configuration.

- The version is set in `coolray43/build.gradle.kts` (`versionCode`, `versionName`).
  If `versionName` ends with `-debug` (for example `1.0.1-debug`), the detailed journal
  and logcat output are on; in a release R8 strips all `android.util.Log` calls.
- Unit tests: `./gradlew :coolray43:testDebugUnitTest :legacy-core:testDebugUnitTest`.

## Layout

```text
├── coolray43/      # the coorlay app for Android 4.3 (UI, ANW Bluetooth, head unit)
├── legacy-core/    # USB, networking, media and CarPlay orchestration for API 18
├── shared/         # iAP2 / AirPlay / Lockdown protocols from DiPlay (compiled into legacy-core)
├── RootInstall/    # root installer from a USB stick and the Wi-Fi Direct fix
├── build-inputs/   # keys go here (not in git)
├── docs/           # dependency licenses and the original DiPlay/xcertplay documents
└── gradle/         # Gradle Wrapper and dependency versions
```

The application id is `com.coorlay` and the Kotlin packages are `com.coorlay.*`
(`com.shihab.diplay.*` and `com.shilapi.xcertplay.*` in DiPlay/xcertplay).
Because the package is new, it does not upgrade earlier coorlay builds
(`com.shihab.diplay.coolray43`): uninstall them manually.

## Branches

- `main` — coorlay. Its history starts over at 1.0.0: the architecture differs a lot
  from DiPlay, so the code is not merged with it directly.
- `upstream` — a copy of `main` of the original [DiPlay](https://github.com/shihabal3amri/DiPlay),
  to review and port its fixes. Do not press GitHub's "Sync fork" on `main`: it offers
  to replace coorlay with DiPlay's code. To update `upstream`:

  ```bash
  git fetch https://github.com/shihabal3amri/DiPlay.git main
  git push origin FETCH_HEAD:refs/heads/upstream
  ```

## Credits and licenses

- Based on [xcertplay](https://github.com/shilapi/xcertplay) (shilapi) and
  [DiPlay](https://github.com/shihabal3amri/DiPlay) (shihabal3amri); their original
  READMEs and notices are in [docs/upstream](docs/upstream).
- The project is licensed under GPL-3.0, see [LICENSE](LICENSE).
- Concentus (Opus in Java) has its own license in `legacy-core/src/main/java/org/concentus/LICENSE`.
- Bouncy Castle, SLF4J, Conscrypt — license texts in [docs/licenses/dependencies](docs/licenses/dependencies).
- CarPlay and iPhone are trademarks of Apple Inc.; Geely and Coolray are trademarks of
  Geely. The names are used only to state compatibility; the project is not affiliated
  with or endorsed by Apple, Geely or Neusoft.
