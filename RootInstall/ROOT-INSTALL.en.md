# coorlay 1.0.4 setup and first connection

[Русский](ROOT-INSTALL.md) · **English**

## 1. Check your head unit software version

1. Park the car and set the parking brake. Stay parked while setting up and testing CarPlay.
2. Open the head unit's built-in settings and look for “About system” or “About device.” The menu name may vary. Find the **head unit software version** and take a photo of the full line.
3. Look for **H30, H40, or H50** in that line. Some H30 units show only `05.05.10500` or `05.05.10600`. If you cannot identify the version, ask for help and share the photo before proceeding. `1.0.4` is the app version, and `v9` is the experimental H50 installer version. Use the head unit software version to choose your steps.

## 2. Before you install

- This kit is for the factory Geely Coolray SX11 head unit running Android 4.3. Install **`coorlay-1.0.4.apk`** first using the built-in file manager. The APK is the only file you need on the USB drive for this step.
- If you are upgrading from an earlier official version to 1.0.4, take photos of any settings you want to keep. Then uninstall **only coorlay** from the head unit's app settings. The APK signing key changed, so you will need to set up the app and pair your iPhone again. If 1.0.4 is already installed and opens, skip the installation. [coorlay 1.0.4 release notes](https://github.com/otnep/coorlay/releases/tag/v1.0.4)
- Keep the head unit powered on until installation finishes. If the built-in file manager cannot open the APK or installation is blocked, save the error message and ask for help.

## 3. Install the app

1. Download `coorlay-1.0.4.apk` to your computer and copy only that file to a regular USB drive. If the head unit cannot see the drive, use a FAT32 drive. Formatting erases the drive's contents. [Release 1.0.4](https://github.com/otnep/coorlay/releases/tag/v1.0.4)
2. Plug the drive into the head unit and open the APK in the built-in file manager. Select “Install.” If Android asks you to allow installation from unknown sources, enable that setting for this APK and turn it off again once installation finishes.
3. Open coorlay. Check that **1.0.4** appears in the corner of the home screen.

## 4. Connect your iPhone

### H30 — start with USB

1. Connect your unlocked iPhone with a USB data cable. Select **USB connection** in coorlay. If your iPhone asks you to trust the device or allow CarPlay, confirm the prompt.
2. For a wireless connection, unplug the cable, pair the iPhone in the head unit's built-in Bluetooth menu, then select **Wireless connection** in coorlay.
3. If Wi-Fi Direct fails, open **coorlay → Settings → Connection**, turn off **Wi-Fi Direct**, and try wireless connection again. The app will use the head unit's hotspot.
4. If you want to use **Wi-Fi Direct**, download `coorlay-usb-wifi-direct-fix.zip` from the regular **1.0.4 release**. Download and prepare the matching rollback archive from the same release before testing. [H30 fix and rollback instructions](H30-WIFI-DIRECT.md#english)

### H40 — try the APK

1. H40 compatibility is unconfirmed. If you want to try it, install the same APK and start with USB using the first H30 step.
2. To try wireless CarPlay, first turn off **Wi-Fi Direct** in **coorlay → Settings → Connection**. Then pair the iPhone in the head unit's built-in Bluetooth menu and select **Wireless connection** in coorlay.
3. If the connection fails, record the full software version and the on-screen message. Avoid using USB system installers from other kits on H40.

### H50 — start with the head unit's hotspot

1. Open **coorlay → Settings → Connection** and turn off **Wi-Fi Direct**.
2. Pair your iPhone in the head unit's built-in Bluetooth menu and select **Wireless connection** in coorlay. The app will try using the head unit's hotspot.
3. If you want to try USB, check that **iPhone charging at 1 A** is enabled under **Settings → Connection** before plugging in the cable. Select **USB connection** in coorlay. If the head unit reports a USB error, unplug the cable and return to wireless connection. [Known H50 issues](https://github.com/otnep/coorlay/issues/2)
4. The APK is enough for the steps above. A separate experimental kit is available for **`05.07.06000.H50.00032`**. It modifies system files and still needs testing with an iPhone on the head unit.

## If the connection fails

Record the full head unit software version, iPhone model, iOS version, and connection method. Take a photo of the error message. To export a log, open **coorlay → Settings → Save log**. Remove phone numbers, passwords, names, and other personal information from attachments before posting. An error description and photo are enough to open an issue. [Report an issue](https://github.com/otnep/coorlay/issues)

## Experimental Wi-Fi Direct for H50.00032 (installer v9)

### Before you start

1. Check the full software version **`05.07.06000.H50.00032`**. The kit is for the factory **SX11QJ** head unit. If the software version differs or you are unsure of the model, use the regular APK with Wi-Fi Direct turned off. Both v9 installers check the model and software version before making changes.
2. Check that **coorlay 1.0.4 is already installed and opens**. Test the head unit's regular Wi-Fi first so you can compare the result after rollback.
3. **Test only while parked and with a stable power supply.** The installer modifies system files. This kit still needs testing on an H50 head unit with an iPhone. If the hotspot works well enough for you, keep using it.
4. Prepare **two separate empty FAT32 USB drives** and label them “ENABLE” and “UNDO.” Extract the outer kit on your computer. Before testing, extract `01-H50.00032-v9-ENABLE.zip` onto the first drive and `02-H50.00032-v9-UNDO.zip` onto the second. When you open either drive, its top level should contain two folders, `coorlay` and `b832bc61…`. Use drives dedicated to these files. Keep CommunityFull and other automatic installers on separate drives.
5. If you previously enabled Wi-Fi Direct with v7 or v8, consult that kit's instructions for rolling back the earlier installation first. Those installers did not record the original scan interval or whether they created the socket link. v9 cannot reliably restore those earlier values.

### Enable Wi-Fi Direct

1. Turn on the head unit and wait for the home screen. Plug in **only the ENABLE drive**, using the USB port that normally reads your music drive.
2. The installer may be running while the “About device” screen is open. Keep the head unit powered on and leave the drive plugged in. Remove the drive when coorlay opens again. If coorlay does not open or you see an error, wait for the installer to finish and check the latest `install-*.log` in `coorlay/logs`. If the log says `DONE WITH ERRORS`, stop before testing the connection.
3. Once the installer finishes successfully, turn off the ignition, wait for the head unit to shut down, and turn the ignition back on. While parked, turn on **Wi-Fi Direct** in coorlay and try connecting your iPhone once.

### Roll back

1. If the head unit restarts, regular Wi-Fi stops working, or CarPlay fails to connect, stop testing and disconnect the iPhone. If the head unit starts normally, turn off **Wi-Fi Direct** in coorlay and plug in **only the UNDO drive** from this same v9 kit.
2. Wait for the installer to finish. Remove the drive, turn off the ignition, wait for the head unit to shut down, then turn it back on. Check regular Wi-Fi. After a successful rollback, you can try CarPlay over the hotspot with Wi-Fi Direct turned off.
3. If the head unit cannot reach the home screen, rollback reports an error, or regular Wi-Fi does not recover, stop and ask for help. Keep the logs from both USB drives. [H50 discussion](https://github.com/otnep/coorlay/issues/2)

---

[Download coorlay 1.0.4](https://github.com/otnep/coorlay/releases/tag/v1.0.4) · [Download the experimental H50.00032 kit](https://github.com/otnep/coorlay/releases/tag/v1.0.4-h50-wifi-direct-v9)

[Technical guide in Russian](TECHNICAL.md)
