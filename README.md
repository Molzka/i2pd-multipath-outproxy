# Multipath I2P

Android VPN и Linux SOCKS5 через несколько параллельных каналов I2P. Нужен свой выходной сервер.

## Android

Версия 1.2.0, встроенный i2pd 3.0.0. Android 8 и новее.

- [Скачать APK для ARM64](https://github.com/Molzka/i2pd-multipath-outproxy/releases/download/v1.2.0/multipath-i2p-1.2.0-arm64-v8a.apk)
- [Скачать APK для x86_64](https://github.com/Molzka/i2pd-multipath-outproxy/releases/download/v1.2.0/multipath-i2p-1.2.0-x86_64.apk)

Вставьте адрес сервера и нажмите «Подключить». Первый запуск i2pd требует времени на вход в сеть.

В настройках: DNS, MTU, исключения приложений, подключение при открытии и восстановление после сбоя. Каналы и прыжки выбираются на главном экране.

Можно использовать установленный I2P-роутер через SAM. Адрес по умолчанию — `127.0.0.1:7656`; SAM должен быть включён в роутере.

Сам клиент и приложения-роутеры обходят VPN. Если включить в Android блокировку соединений без VPN, внешний роутер потеряет доступ к сети — используйте встроенный.

[Релиз 1.2.0](https://github.com/Molzka/i2pd-multipath-outproxy/releases/tag/v1.2.0) · [Сборка APK](android/README.md)

## Сервер

На VPS с Debian или Ubuntu:

```sh
git clone https://github.com/Molzka/i2pd-multipath-outproxy.git
cd i2pd-multipath-outproxy
sudo sh server/deploy.sh
```

Скрипт установит Docker, соберёт и запустит контейнеры. Адрес сервера появится в терминале и в `server/OUTPROXY_ADDRESS.txt`. Не публикуйте его: отдельного пароля нет.

Для обновления выполните `git pull --ff-only` и снова запустите `server/deploy.sh`. Сохраните `server/i2pd/data/outproxy.dat` — в нём ключи сервера.

По умолчанию выход работает через IPv4. В Android оставьте DNS `1.1.1.1` или укажите другой публичный IPv4-DNS.

## Linux-клиент

Нужны Docker Compose и Python 3. На другой машине, из корня репозитория:

```sh
python3 client/i2pd/gen.py "адрес.b32.i2p" > client/i2pd/data/tunnels.conf
sudo chown -R 1000:1000 client/i2pd/data
sudo sh client/i2pd/scripts/build.sh
sudo sh client/i2pd/scripts/run.sh
sudo sh client/multipath/scripts/build.sh
sudo sh client/multipath/scripts/run.sh
```

SOCKS5: `127.0.0.1:1081`, TCP и UDP. По умолчанию используются 32 канала; при изменении `--links` в `gen.py` поменяйте и список `--tcp` в `client/multipath/docker-compose.yml`.

Больше каналов расходует больше памяти и не всегда даёт больше скорости. Параметры агрегации находятся в `client/multipath/multipath.cfg` и `server/multipath/multipath.cfg`.

Сторонние компоненты: [i2pd](https://github.com/PurpleI2P/i2pd), [aggligator](https://github.com/remoc-rs/aggligator), [tun2socks](https://github.com/xjasonlyu/tun2socks).
