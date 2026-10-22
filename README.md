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

На Linux VPS нужны Git, curl, `ss` (пакет iproute2), [Docker Engine](https://docs.docker.com/engine/install/) и [Compose](https://docs.docker.com/compose/install/linux/). Docker должен быть запущен и доступен пользователю, который запускает скрипт.

Для первой установки:

```sh
git clone https://github.com/Molzka/i2pd-multipath-outproxy.git
cd i2pd-multipath-outproxy
printf 'I2PD_PORT=17656\n' > server/i2pd/.env
sudo chown -R 1000:1000 server/i2pd/data
sudo chmod 700 server/i2pd/data
sh server/deploy.sh
```

`17656` — пример свободного порта для i2pd. Если на VPS или у хостера включён файрвол, разрешите этот порт по TCP и UDP вручную. UID 1000 нужен контейнеру для записи ключей и данных роутера.

Скрипт соберёт и запустит контейнеры, получит адрес из локальной консоли i2pd и сохранит его в `server/OUTPROXY_ADDRESS.txt`. Не публикуйте адрес: отдельного пароля нет.

Для обновления выполните `git pull --ff-only` и `sh server/deploy.sh`. Порт из `.env` сохранится. Сохраните `server/i2pd/data/outproxy.dat` — в нём ключи сервера. [Логи и переход со старого установщика](server/README.md).

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
