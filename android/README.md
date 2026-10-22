# Сборка Android

Нужны компьютер x86_64, Docker, JDK 17+ и Android SDK с платформой 36. Задайте `JAVA_HOME` и `ANDROID_HOME`.

Из корня репозитория:

```powershell
./android/build.ps1
```

Linux/macOS:

```sh
sh android/build.sh
```

Скрипт собирает i2pd 3.0.0, agg-tunnel и i2pmux для ARM64 и x86_64, затем APK. Результат: `dist/multipath-i2p.apk` и `dist/multipath-i2p-x86_64.apk`.

Для одной архитектуры: `./android/build.ps1 -Abis arm64-v8a` или `ABIS=arm64-v8a sh android/build.sh`.

Если нативные компоненты уже собраны и не менялись, можно добавить `-SkipNative` или `--skip-native`. Старый кэш после обновления i2pd не подойдёт.

Сохраните `android/release.jks` и `android/keystore.properties`. Без прежнего ключа обновление не установится поверх вашей сборки. Эти файлы в Git не добавляются.

[Готовые APK](https://github.com/Molzka/i2pd-multipath-outproxy/releases/tag/v1.2.0)
