# server

Установка и зависимости — в [основном README](../README.md#сервер). Из корня проекта: `sh server/deploy.sh`.

- `i2pd/` - роутер, серверный туннель `multipath` -> 127.0.0.1:1337. Ключи в
  `data/outproxy.dat`.
- `multipath/` - agg-tunnel на 127.0.0.1:1337 и i2pmux (1081 mux, 1080 socks).

В `*/scripts/` есть build, run, stop, logs, export/import.

Логи: `sh server/i2pd/scripts/logs.sh` и `sh server/multipath/scripts/logs.sh`. Остановка: соответствующий `scripts/stop.sh`.

Если старый установщик запускался через sudo, перед запуском от обычного пользователя передайте ему созданные файлы (если они существуют):

```sh
sudo chown "$(id -u):$(id -g)" server/i2pd/.env server/OUTPROXY_ADDRESS.txt
```

Каталог проекта тоже должен быть доступен этому пользователю. Данные роутера остаются у UID 1000; если ваш UID другой, для редактирования этих конфигов и их обновления через Git потребуются повышенные права.

Созданные старым установщиком swap, настройки sysctl и правила файрвола останутся в системе. Новый скрипт их не меняет.

## Выход через Xray

В `inbounds` существующей конфигурации Xray добавьте SOCKS5 на отдельном порту:

```json
{
  "tag": "i2p-outproxy",
  "listen": "127.0.0.1",
  "port": 10808,
  "protocol": "socks",
  "settings": { "auth": "noauth", "udp": true }
}
```

В `server/multipath/.env` добавьте:

```ini
I2PMUX_OUTBOUND_SOCKS=127.0.0.1:10808
```

После запуска Xray пересоберите и перезапустите сервер из корня проекта:

```sh
git pull --ff-only
sh server/multipath/scripts/build.sh
sh server/multipath/scripts/run.sh
```

TCP и UDP от Android, Linux-клиента и входящего SOCKS пойдут через Xray. При его остановке или отказе UDP прямого обхода нет. Обновлять APK для этого не нужно.

При запуске без Compose: `i2pmux server -outbound-socks 127.0.0.1:10808`. Поддерживается SOCKS5 без авторизации. Флаг `-socks` задаёт отдельный входящий порт; в штатном Compose это `1080`, а Xray использует `10808`.

Доменные имена передаются в Xray без разрешения на стороне i2pmux. За запрет доменов, ведущих в локальные сети, отвечают правила Xray: настройте блокировку таких адресов перед разрешающими правилами, например через `geoip:private` с разрешением доменов при проверке IP. Явно заданные локальные IP по-прежнему запрещены без `-allow-private`. Флаги `-ipv4-only` и `-prefer-ipv6` не управляют разрешением имён внутри Xray.

Android передаёт адреса из IP-пакетов. Для маршрутизации по доменам настройте в Xray sniffing (`http`, `tls`, `quic`, `routeOnly: true`); зашифрованные имена он может не распознать. Размер UDP-пакетов также зависит от выбранного SOCKS-сервера.

Справка: `docker exec myi2pmux i2pmux server -h`. При проблемах проверьте порт Xray, `udp: true` и логи обоих сервисов. Ошибка `flag provided but not defined: -outbound-socks` означает, что образ ещё не пересобран. Если удалить `I2PMUX_OUTBOUND_SOCKS` из `.env` и повторить `run.sh`, вернётся прямой выход.

[SOCKS в Xray](https://xtls.github.io/en/config/inbounds/socks.html) · [Маршрутизация](https://xtls.github.io/en/config/routing.html) · [Sniffing](https://xtls.github.io/en/config/inbound.html#sniffingobject)
