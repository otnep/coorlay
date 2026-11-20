# coorlay

**Русский** · [English](https://github.com/otnep/coorlay/blob/main/README.en.md)

coorlay добавляет CarPlay по USB и Wi-Fi на штатную магнитолу Geely Coolray SX11 с Android 4.3. Это экспериментальный проект.

[Скачать coorlay 1.0.4](https://github.com/otnep/coorlay/releases/tag/v1.0.4) · [Пошаговая установка для H30, H40 и H50](RootInstall/ROOT-INSTALL.md)

## Совместимость

| Версия | Что проверено |
| --- | --- |
| **H30** | CarPlay по USB и Wi-Fi работает на проверенных магнитолах. [Исправление Wi-Fi Direct](RootInstall/H30-WIFI-DIRECT.md) |
| **H40** | Пока не тестировали. |
| **H50** | Есть проблемы с USB и беспроводным подключением. Экспериментальное исправление Wi-Fi Direct v9 для `05.07.06000.H50.00032` ещё предстоит проверить на магнитоле с iPhone. [Отдельный выпуск для H50](https://github.com/otnep/coorlay/releases/tag/v1.0.4-h50-wifi-direct-v9) |

Приложение рассчитано на штатную магнитолу Neusoft / GKUI с платой `C3ALFUS`, Android 4.3 (API 18, `armeabi-v7a`) и экраном 1920×640 при 160 dpi.

## Установка и подключение

Сначала найдите H30, H40 или H50 в полной строке версии ПО магнитолы. На H30 начните с USB. На H40 можно попробовать APK, совместимость ещё предстоит проверить. На H50 начните с точки доступа магнитолы и выключите Wi-Fi Direct в coorlay.

**При переходе на 1.0.4** сохраните нужные настройки перед удалением предыдущей официальной версии coorlay. Из-за смены подписи приложения настройки и сопряжения потребуется создать заново.

1. Скачайте APK из выпуска и установите его через штатный файловый менеджер магнитолы. [Файлы выпуска](https://github.com/otnep/coorlay/releases/tag/v1.0.4) · [Пошаговая инструкция](RootInstall/ROOT-INSTALL.md)
2. **По USB.** Подключите iPhone кабелем и выберите «Подключение по USB» в coorlay.
3. **По Wi-Fi.** Сопрягите iPhone с магнитолой в штатном меню Bluetooth и выберите «Беспроводное подключение» в coorlay. Чтобы использовать точку доступа магнитолы, выключите Wi-Fi Direct в разделе «Настройки → Подключение».

> [!WARNING]
> Установщик с флешки работает с правами root, а исправление Wi-Fi Direct меняет системные файлы. Ошибка при установке может нарушить работу магнитолы. Выполняйте установку и настройку на свой страх и риск.

## Возможности

- Интерфейс CarPlay на экране магнитолы
- Кнопки руля, информация о текущем треке и стрелки навигации на приборной панели.
- Автозапуск вместе с магнитолой и автоматическое подключение к iPhone.
- Интерфейс на русском и английском и сохранение журнала диагностики.

## Сборка

Для сборки нужны JDK 25 (подходит JBR из Android Studio), Android SDK Platform 37, Build Tools 37.0.0 и NDK 23.2.8568313. Gradle Wrapper загружает Gradle 9.5.0. Версия Android Gradle Plugin — 9.3.0.

Подготовьте локальные входные файлы для сборки. Они хранятся в `build-inputs/` вне Git. [Настройка файлов для сборки](https://github.com/otnep/coorlay/blob/main/build-inputs/README.md)

- **Данные аксессуара MFi.** Для сборки нужны `identity.pk8` и `certificate.p7b`. coorlay использует экспериментальные данные из DiPlay. Совместимость с будущими версиями iOS неизвестна. [DiPlay](https://github.com/shihabal3amri/DiPlay)

## Устройство проекта

| Каталог | Содержимое |
| --- | --- |
| `coolray43/` | Интерфейс coorlay и интеграция со штатным Bluetooth и магнитолой. |
| `legacy-core/` | Транспорт USB и Wi-Fi, видео и звук, управление сеансом CarPlay на Android 4.3. |
| `shared/` | Реализации iAP2, AirPlay и Lockdown из DiPlay. |
| `RootInstall/` | Установка с флешки, исправление Wi-Fi Direct и откат изменений. |
| `build-inputs/` | Инструкция по подготовке локальных файлов для сборки. |
| `docs/` | Лицензии зависимостей и документы исходных проектов. |
| `gradle/` | Gradle Wrapper и версии зависимостей. |

Основная разработка ведётся в `main`. В `upstream` хранится копия основной ветки DiPlay для выборочного переноса изменений.

```bash
git fetch https://github.com/shihabal3amri/DiPlay.git main
git push origin FETCH_HEAD:refs/heads/upstream
```

## Авторы и лицензия

coorlay — порт DiPlay на Android 4.3. В основе DiPlay лежит xcertplay. [DiPlay](https://github.com/shihabal3amri/DiPlay) · [xcertplay](https://github.com/shilapi/xcertplay) · [Документы исходных проектов](https://github.com/otnep/coorlay/tree/main/docs/upstream)

Проект распространяется по GPL-3.0 «как есть», без гарантий. CarPlay и iPhone — товарные знаки Apple Inc. Geely и Coolray — товарные знаки Geely. Проект не связан с Apple, Geely или Neusoft и не сертифицирован Apple. [LICENSE](https://github.com/otnep/coorlay/blob/main/LICENSE)

- libopus 1.5.2 (Xiph.Org). [Лицензия](https://github.com/otnep/coorlay/blob/main/legacy-core/src/main/jni/opus/COPYING)
- Concentus (Opus на Java). [Лицензия](https://github.com/otnep/coorlay/blob/main/legacy-core/src/main/java/org/concentus/LICENSE)
- Bouncy Castle, SLF4J и Conscrypt. [Лицензии зависимостей](https://github.com/otnep/coorlay/tree/main/docs/licenses/dependencies)
