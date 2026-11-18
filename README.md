# coorlay

**Русский** · [English](https://github.com/otnep/coorlay/blob/main/README.en.md)

coorlay добавляет CarPlay по USB и Wi-Fi на штатную магнитолу Geely Coolray SX11 с Android 4.3. Это экспериментальный проект.

[Скачать coorlay 1.0.4](https://github.com/otnep/coorlay/releases/tag/v1.0.4) · [Установка с флешки](https://github.com/otnep/coorlay/blob/main/RootInstall/ROOT-INSTALL.md)

## Совместимость

| Версия | Что проверено |
| --- | --- |
| **H30** | CarPlay по USB и Wi-Fi работает на проверенных магнитолах. |
| **H40** | Пока не тестировали. |
| **H50** | Есть проблемы с USB и беспроводным подключением. Исправление Wi-Fi Direct v8 для `05.07.06000.H50.00032` ещё предстоит проверить на магнитоле с iPhone. [Отчёт и обсуждение](https://github.com/otnep/coorlay/issues/2) |

Приложение рассчитано на штатную магнитолу Neusoft / GKUI с платой `C3ALFUS`, Android 4.3 (API 18, `armeabi-v7a`) и экраном 1920×640 при 160 dpi.

## Установка и подключение

**При переходе на 1.0.4** удалите предыдущую официальную версию coorlay перед установкой.

1. Скачайте APK из выпуска и установите его через файловый менеджер магнитолы. Для установки с флешки в выпуске есть отдельный архив. [Файлы выпуска](https://github.com/otnep/coorlay/releases/tag/v1.0.4) · [Инструкция по установке с флешки](https://github.com/otnep/coorlay/blob/main/RootInstall/ROOT-INSTALL.md)
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
