# coorlay

[Русский](https://github.com/otnep/coorlay/blob/main/README.md) · **English**

coorlay adds wired and wireless CarPlay to the factory head unit of the Geely Coolray SX11 running Android 4.3. It is an experimental project.

[Download coorlay 1.0.4](https://github.com/otnep/coorlay/releases/tag/v1.0.4) · [Install from a USB drive (Russian)](https://github.com/otnep/coorlay/blob/main/RootInstall/ROOT-INSTALL.md)

## Compatibility

| Version | Test status |
| --- | --- |
| **H30** | CarPlay works over USB and Wi-Fi on the units tested. |
| **H40** | Untested. |
| **H50** | There are known issues with USB and wireless connections. The v8 Wi-Fi Direct fix for `05.07.06000.H50.00032` still needs to be tested with an iPhone on the head unit. [Issue report and discussion](https://github.com/otnep/coorlay/issues/2) |

The app is designed for the factory Neusoft / GKUI head unit with a `C3ALFUS` board, Android 4.3 (API 18, `armeabi-v7a`), and a 1920×640 screen at 160 dpi.

## Installation and connection

**If you are upgrading to 1.0.4**, uninstall the previous official version of coorlay before installing the update.

1. Download the APK from the release and install it using the head unit's file manager. A separate archive is available for installation from a USB drive. [Release files](https://github.com/otnep/coorlay/releases/tag/v1.0.4) · [USB installation guide (Russian)](https://github.com/otnep/coorlay/blob/main/RootInstall/ROOT-INSTALL.md)
2. **Over USB.** Connect your iPhone with a cable and select “USB connection” in coorlay.
3. **Over Wi-Fi.** Pair your iPhone with the head unit in its built-in Bluetooth menu, then select “Wireless connection” in coorlay. To use the head unit's Wi-Fi hotspot, turn off Wi-Fi Direct under Settings → Connection.

> [!WARNING]
> The USB installer runs as root, and the Wi-Fi Direct fix changes system files. An installation error may prevent the head unit from working properly. Proceed with installation and setup at your own risk.

## Features

- CarPlay on the head unit's screen.
- Steering wheel controls, track information, and navigation arrows on the instrument cluster.
- Automatic startup with the head unit and automatic connection to your iPhone.
- Russian and English interface and diagnostic log export.

## Building

You need JDK 25 (the JBR bundled with Android Studio works), Android SDK Platform 37, Build Tools 37.0.0, and NDK 23.2.8568313. The Gradle Wrapper downloads Gradle 9.5.0. The project uses Android Gradle Plugin 9.3.0.

Prepare the local build inputs. Store them in `build-inputs/` outside version control. [Build input setup (Russian)](https://github.com/otnep/coorlay/blob/main/build-inputs/README.md)

- **MFi accessory data.** The build requires `identity.pk8` and `certificate.p7b`. coorlay uses experimental data from DiPlay. Compatibility with future versions of iOS is unknown. [DiPlay](https://github.com/shihabal3amri/DiPlay)

## Repository layout

| Directory | Contents |
| --- | --- |
| `coolray43/` | The coorlay interface and integration with the head unit and its built-in Bluetooth. |
| `legacy-core/` | USB and Wi-Fi transport, video and audio, and CarPlay session management on Android 4.3. |
| `shared/` | DiPlay's iAP2, AirPlay, and Lockdown implementations. |
| `RootInstall/` | USB drive installation, the Wi-Fi Direct fix, and rollback. |
| `build-inputs/` | Instructions for preparing local build inputs. |
| `docs/` | Dependency licenses and documentation from the original projects. |
| `gradle/` | The Gradle Wrapper and dependency versions. |

Development takes place on `main`. The `upstream` branch mirrors DiPlay's main branch for selectively porting changes.

```bash
git fetch https://github.com/shihabal3amri/DiPlay.git main
git push origin FETCH_HEAD:refs/heads/upstream
```

## Credits and license

coorlay is a port of DiPlay to Android 4.3. DiPlay is based on xcertplay. [DiPlay](https://github.com/shihabal3amri/DiPlay) · [xcertplay](https://github.com/shilapi/xcertplay) · [Documentation from the original projects](https://github.com/otnep/coorlay/tree/main/docs/upstream)

The project is distributed under the GPL-3.0 license as is, without warranty. CarPlay and iPhone are trademarks of Apple Inc. Geely and Coolray are trademarks of Geely. The project has no affiliation with Apple, Geely, or Neusoft and has no Apple certification. [LICENSE](https://github.com/otnep/coorlay/blob/main/LICENSE)

- libopus 1.5.2 (Xiph.Org). [License](https://github.com/otnep/coorlay/blob/main/legacy-core/src/main/jni/opus/COPYING)
- Concentus (Opus in Java). [License](https://github.com/otnep/coorlay/blob/main/legacy-core/src/main/java/org/concentus/LICENSE)
- Bouncy Castle, SLF4J, and Conscrypt. [Dependency licenses](https://github.com/otnep/coorlay/tree/main/docs/licenses/dependencies)
