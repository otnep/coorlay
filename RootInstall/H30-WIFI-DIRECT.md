# Wi-Fi Direct для H30 / H30 Wi-Fi Direct

[Русский](#русский) · [English](#english)

## Русский

Исправление нужно, если на штатной H30 установлено coorlay 1.0.4, но Wi-Fi Direct не включается. Если вам подходит подключение по USB или через точку доступа, можно продолжать им пользоваться.

1. **Проверьте прошивку.** Эти шаги для штатной H30 с Android 4.3, в прошивке которой уже есть Wi-Fi Direct. На знакомых прошивках H30 могут быть видны только номера `05.05.10500` и `05.05.10600`. Если версия или наличие других модификаций неизвестны, уточните их перед запуском. [Выбор по версии](ROOT-INSTALL.md)
2. **Подготовьте откат заранее.** Возьмите две отдельные пустые флешки FAT32 без CommunityFull и других установщиков. Распакуйте фикс из выпуска 1.0.4 на первую, а архив отката из того же выпуска — на вторую. При открытии каждой флешки должны быть видны папки `coorlay` и `b832bc61…`. Подпишите флешки «ФИКС» и «ОТКАТ». [Скачать фикс H30](https://github.com/otnep/coorlay/releases/download/v1.0.4/coorlay-usb-wifi-direct-fix.zip) · [Скачать откат H30](https://github.com/otnep/coorlay/releases/download/v1.0.4/coorlay-usb-wifi-direct-undo.zip)
3. **Запустите исправление на стоянке.** При включённой магнитоле вставьте только флешку «ФИКС» в USB-разъём для музыки. Пока открыт экран «Об устройстве», не выключайте питание и оставьте флешку на месте. Когда coorlay откроется, выньте флешку, включите **Wi-Fi Direct** в **coorlay → Настройки → Подключение** и попробуйте подключить iPhone.
4. **При сбое выполните откат.** Отсоедините iPhone и выключите Wi-Fi Direct в coorlay. Если магнитола загружается штатно, вставьте только флешку «ОТКАТ» из этого же комплекта. Дождитесь завершения, затем проверьте штатный Wi-Fi. Если coorlay не открылся или Wi-Fi не восстановился, сохраните последний `install-*.log` из `coorlay/logs` после завершения установщика и обратитесь за помощью. [Сообщить о проблеме](https://github.com/otnep/coorlay/issues)

Этот откат возвращает стандартный интервал сканирования Wi-Fi. Если вы вручную меняли этот интервал или на магнитоле уже установлен другой фикс Wi-Fi Direct, сначала уточните порядок восстановления. Используйте архивы фикса и отката из одного выпуска.

## English

Use this fix if you have coorlay 1.0.4 on a factory H30 and Wi-Fi Direct fails to start. You can keep using USB or the head unit's hotspot if either works for you.

1. **Check the software version.** Use these steps on a factory H30 running Android 4.3 with Wi-Fi Direct already declared by the firmware. Known H30 versions may show only `05.05.10500` or `05.05.10600`. If you are unsure of the version or of any modifications already installed, check before proceeding. [Choose your firmware](ROOT-INSTALL.en.md)
2. **Prepare the rollback drive first.** Use two separate empty FAT32 USB drives free of CommunityFull and other installers. Extract the fix from release 1.0.4 onto the first drive and the rollback archive from that same release onto the second. Both drives should show the `coorlay` and `b832bc61…` folders when you open them. Label the drives “FIX” and “UNDO.” [Download H30 fix](https://github.com/otnep/coorlay/releases/download/v1.0.4/coorlay-usb-wifi-direct-fix.zip) · [Download H30 rollback](https://github.com/otnep/coorlay/releases/download/v1.0.4/coorlay-usb-wifi-direct-undo.zip)
3. **Run the fix while parked.** With the head unit powered on, insert only the FIX drive into the port you use for music. Keep the head unit powered on and leave the drive in place while the “About device” screen is open. When coorlay opens, remove the drive, turn on **Wi-Fi Direct** in **coorlay → Settings → Connection**, and try connecting your iPhone.
4. **Roll back if the test fails.** Disconnect the iPhone and turn off Wi-Fi Direct in coorlay. If the head unit starts normally, insert only the UNDO drive from the same kit. Wait for it to finish, then check the head unit's regular Wi-Fi. If coorlay does not open or Wi-Fi does not recover, save the latest `install-*.log` from `coorlay/logs` once the installer finishes and ask for help. [Report an issue](https://github.com/otnep/coorlay/issues)

The rollback restores the default Wi-Fi scan interval. If you changed that interval manually or installed another Wi-Fi Direct fix, check how to restore your setup before proceeding. Use the fix and rollback from the same release.
