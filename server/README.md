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
