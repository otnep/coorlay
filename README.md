# coorlay

**Русский** · [English](README.en.md)

Экспериментальный CarPlay для штатного головного устройства **Geely Coolray**
(SX11, Neusoft / GKUI, Android 4.3 / API 18). Приложение ставится на магнитолу
и принимает от iPhone изображение, касания, звук и микрофон CarPlay — по USB
и без провода (Bluetooth + Wi-Fi).

coorlay — порт [DiPlay](https://github.com/shihabal3amri/DiPlay) (который, в свою
очередь, основан на [xcertplay](https://github.com/shilapi/xcertplay)) на старую
платформу Android 4.3. Проект не сертифицирован Apple и не связан с Apple, Geely
или Neusoft.

> **Статус:** версия `1.0.0`, экспериментальная. Работа на других магнитолах и
> других прошивках не проверялась и не поддерживается.

> [!WARNING]
> **Отказ от ответственности.** coorlay — любительский экспериментальный проект.
> Он распространяется «как есть», без каких-либо гарантий (GPL-3.0, разделы 15–16).
> Вы устанавливаете его на свой страх и риск: стороннее приложение и установка от root
> могут нарушить работу головного устройства и стать причиной отказа в гарантийном
> обслуживании. Авторы не отвечают за неисправности, потерю данных и иной ущерб.
> Не отвлекайтесь на экран во время движения и соблюдайте ПДД.

## Поддерживаемое устройство

| Параметр | Значение |
| --- | --- |
| Автомобиль | Geely Coolray / SX11 |
| ГУ | Neusoft, `ro.product.board=C3ALFUS` |
| Прошивки | `05.05.*` – `05.07.*` |
| Система | Android 4.3 / API 18, `armeabi-v7a` |
| Экран | 1920×640, 160 dpi |

## Возможности

- CarPlay по USB (USBMUX, Lockdown, iAP2, NCM) и беспроводной CarPlay
  (сопряжение через штатный Bluetooth ANW, Wi-Fi — точка доступа ГУ или Wi-Fi Direct).
- Видео H.264, касания, звук (в т. ч. AAC-LC по Wi-Fi), Siri и микрофон.
- Кнопки руля, трек и стрелки навигации на приборной панели.
- Автозапуск вместе с ГУ и автоподключение.
- Сохранение диагностического журнала; интерфейс на русском и английском.
- Установка/обновление с флешки от root и исправление Wi-Fi Direct —
  см. [RootInstall/ROOT-INSTALL.md](RootInstall/ROOT-INSTALL.md).

## Ключи

**В репозитории нет ни одного ключа.** Для сборки нужны два набора, их кладут
в `build-inputs/` (папки в `.gitignore`), подробности — в
[build-inputs/README.md](build-inputs/README.md).

1. **Ключи аксессуара MFi** (`identity.pk8`, `certificate.p7b`).
   Экспериментальные ключи взяты из оригинального репозитория
   [DiPlay](https://github.com/shihabal3amri/DiPlay) — они входят в его публичный
   preview APK. По данным авторов DiPlay, эта пара извлечена из публичной прошивки
   Carlinkit C2Air; это не ключи, выданные Apple для coorlay или DiPlay.
   Будет ли iPhone принимать их после будущих обновлений iOS — неизвестно.
2. **Ключ подписи APK** (`coorlay.keystore` + `signing.properties`). Для своей
   сборки подойдёт debug keystore. APK с другой подписью не обновит установленный
   coorlay: старую версию нужно сначала удалить.

## Сборка

Нужны:

- JDK 25 (подходит JBR из Android Studio);
- Android SDK Platform 37, Build Tools 37.0.0, NDK 23.2.8568313;
- Gradle 9.5.0 скачивается wrapper'ом, Android Gradle Plugin 9.3.0.

```bash
./gradlew :coolray43:assembleRelease
```

Готовый APK: `coolray43/build/outputs/apk/release/coolray43-release.apk`.
В Android Studio откройте корень репозитория и запустите конфигурацию **Build APK**.

- Версия задаётся в `coolray43/build.gradle.kts` (`versionCode`, `versionName`).
  Если `versionName` оканчивается на `-debug` (например, `1.0.1-debug`), подробный
  журнал и logcat включены; в выпуске вызовы `android.util.Log` вырезаются R8.
- Юнит-тесты: `./gradlew :coolray43:testDebugUnitTest :legacy-core:testDebugUnitTest`.
- Пути к ключам можно переопределить переменными `COORLAY_AUTH_ASSETS_DIR`,
  `COORLAY_SIGNING_STORE_FILE` и `COORLAY_SIGNING_PROPERTIES`.

## Структура

```text
├── coolray43/      # приложение coorlay для Android 4.3 (интерфейс, ANW Bluetooth, ГУ)
├── legacy-core/    # USB, сеть, медиа и CarPlay-оркестрация с поддержкой API 18
├── shared/         # протоколы iAP2 / AirPlay / Lockdown из DiPlay (подключаются в legacy-core)
├── RootInstall/    # установщик с флешки от root и исправление Wi-Fi Direct
├── build-inputs/   # сюда кладутся ключи (не в git)
├── docs/           # лицензии зависимостей и исходные документы DiPlay/xcertplay
└── gradle/         # Gradle Wrapper и версии зависимостей
```

Идентификатор приложения — `com.coorlay`, Kotlin-пакеты — `com.coorlay.*`
(в DiPlay/xcertplay — `com.shihab.diplay.*` и `com.shilapi.xcertplay.*`).
Пакет приложения новый, поэтому прежние сборки coorlay (`com.shihab.diplay.coolray43`)
он не обновляет: их нужно удалить вручную.

## Ветки

- `main` — coorlay. История начинается заново с 1.0.0: архитектура сильно
  отличается от DiPlay, поэтому код не сливается с ним напрямую.
- `upstream` — копия `main` исходного [DiPlay](https://github.com/shihabal3amri/DiPlay),
  чтобы смотреть и переносить его исправления. Кнопку GitHub «Sync fork» на `main`
  не нажимайте: она предлагает заменить coorlay кодом DiPlay. Обновить `upstream`:

  ```bash
  git fetch https://github.com/shihabal3amri/DiPlay.git main
  git push origin FETCH_HEAD:refs/heads/upstream
  ```

## Авторы и лицензии

- Основа: [xcertplay](https://github.com/shilapi/xcertplay) (shilapi) и
  [DiPlay](https://github.com/shihabal3amri/DiPlay) (shihabal3amri); исходные
  README и уведомления — в [docs/upstream](docs/upstream).
- Лицензия проекта — GPL-3.0, см. [LICENSE](LICENSE).
- Concentus (Opus на Java) — своя лицензия в `legacy-core/src/main/java/org/concentus/LICENSE`.
- Bouncy Castle, SLF4J, Conscrypt — тексты лицензий в [docs/licenses/dependencies](docs/licenses/dependencies).
- CarPlay и iPhone — товарные знаки Apple Inc.; Geely и Coolray — товарные знаки
  Geely. Названия используются только для указания совместимости; проект не связан
  с Apple, Geely и Neusoft и не одобрен ими.
