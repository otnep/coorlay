# build-inputs — ключи для сборки (в репозиторий не входят)

Сборка `coolray43` проверяет наличие этих файлов (задача `verifyAuthentication`)
и без них останавливается:

```text
build-inputs/
├── runtime-assets/
│   └── offline-mfi/
│       ├── identity.pk8      # закрытый ключ аксессуара MFi (PKCS#8)
│       └── certificate.p7b   # сертификат аксессуара (PKCS#7)
└── signing/
    ├── coorlay.keystore      # ключ подписи APK
    └── signing.properties    # пароли и alias ключа подписи
```

Обе папки внесены в `.gitignore`. Не добавляйте ключи в коммиты, issues и pull requests.

**MFi.** Экспериментальные ключи аксессуара взяты из оригинального репозитория
[DiPlay](https://github.com/shihabal3amri/DiPlay) (они входят в его публичный preview APK).
Это не ключи, выданные Apple для этого проекта; см. раздел «Ключи» в [README](../README.md).

**Подпись APK.** `signing.properties` — обычный файл свойств:

```properties
storePassword=...
keyAlias=...
keyPassword=...
```

Без `signing.properties` используются пароли debug keystore (`android` / `androiddebugkey`),
так что для своей сборки можно просто положить `~/.android/debug.keystore` под именем
`coorlay.keystore`. Официальные APK coorlay подписаны ключом автора; APK с другой подписью
не установится поверх них — сначала удалите установленную версию.

Вместо этих путей можно задать переменные окружения:

- `COORLAY_AUTH_ASSETS_DIR` — папка, внутри которой лежит `offline-mfi/`;
- `COORLAY_SIGNING_STORE_FILE` — путь к keystore;
- `COORLAY_SIGNING_PROPERTIES` — путь к файлу с паролями.
