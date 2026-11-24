# Explore With Me (Spring Cloud)

Приложение разбито на модули и запускается как набор микросервисов под управлением Eureka,
с централизованной конфигурацией (Spring Cloud Config) и единой точкой входа (Spring Cloud Gateway).

## Структура проекта

```
infra/
  discovery-server   — Eureka Server (реестр сервисов), порт 8761
  config-server      — Spring Cloud Config Server (native-профиль), порт 8888
  gateway-server     — Spring Cloud Gateway, порт 8080 (единая точка входа)
core/
  main-service       — основной сервис (пользователи, события, категории, подборки, заявки, рейтинги)
stats/
  stats-dto          — DTO сервиса статистики
  stats-client       — HTTP-клиент и автоконфигурация для main-service
  stats-server       — сервис статистики, порт 9090
```

Сервисные имена (service-id) в Eureka: `discovery-server`, `config-server`, `gateway-server`,
`main-service`, `stats-server`.

## Как это работает

1. `discovery-server` поднимается первым — он не регистрируется сам в себе.
2. `config-server` регистрируется в Eureka и раздаёт конфигурацию из `infra/config-server/src/main/resources/config/<service-id>.properties`.
3. Остальные сервисы при старте указывают `spring.config.import=configserver:` и `spring.cloud.config.discovery.enabled=true`,
   поэтому адрес config-server они находят через Eureka (жёстко зашитый хост не нужен).
4. `gateway-server` маршрутизирует запросы по `lb://main-service` и `lb://stats-server`:
   `/admin/**`, `/users`, `/users/**`, `/events/**`, `/categories/**`, `/compilations/**`, `/user/**` → main-service;
   `/hit` и `/stats`, `/stats/**` → stats-server. Дополнительно включён `discovery.locator`,
   поэтому каждый сервис доступен по своему service-id (например, `/stats-server/...`).

## Порты

| Сервис           | Порт                     |
|------------------|--------------------------|
| gateway-server   | 8080                     |
| discovery-server | 8761                     |
| config-server    | 8888                     |
| stats-server     | 9090                     |
| main-service     | случайный (`server.port=0`) |

Все порты переопределяются переменной окружения `SERVER_PORT`.
Адрес Eureka — переменной `EUREKA_URL` (по умолчанию `http://localhost:8761/eureka/`).

## Запуск

Docker используется **только для баз данных**, сервисы запускаются локально из собранных jar-файлов.

`docker-compose.yml` поднимает два контейнера PostgreSQL (пользователь `postgres`, пароль `password`):

| Контейнер  | База данных    | Порт на хосте |
|------------|----------------|---------------|
| `ewm-db`   | `ewm_main_db`  | 5432          |
| `stats-db` | `ewm_stats_db` | 5433          |

```bash
# 1. Собрать jar-файлы
mvn clean package -DskipTests

# 2. Поднять базы данных и дождаться статуса healthy
docker compose up -d --wait

# 3. Запустить сервисы (каждый в своём терминале)
java -jar infra/discovery-server/target/discovery-server-0.0.1-SNAPSHOT.jar
java -jar infra/config-server/target/config-server-0.0.1-SNAPSHOT.jar
java -jar stats/stats-server/target/stats-server-0.0.1-SNAPSHOT.jar
java -jar core/main-service/target/main-service-0.0.1-SNAPSHOT.jar
java -jar infra/gateway-server/target/gateway-server-0.0.1-SNAPSHOT.jar
```

`main-service` подключается к `jdbc:postgresql://localhost:5432/ewm_main_db`,
`stats-server` — к `jdbc:postgresql://localhost:5433/ewm_stats_db`.

Проверка:

```bash
curl http://localhost:8761/eureka/apps        # список зарегистрированных сервисов
curl http://localhost:8080/categories         # запрос через gateway
curl -X POST http://localhost:8080/hit -H 'Content-Type: application/json' \
     -d '{"app":"ewm-main-service","uri":"/events","ip":"127.0.0.1","timestamp":"2024-06-15 12:00:00"}'
curl 'http://localhost:8080/stats?start=2000-01-01%2000:00:00&end=2100-01-01%2000:00:00'
```

Формат `timestamp` в теле запроса `/hit` — `yyyy-MM-dd HH:mm:ss`.

Остановка баз данных:

```bash
docker compose down
```

### Если порт 5432 уже занят

Если на машине работает локальный PostgreSQL, опубликуйте порт контейнера `ewm-db` на 5434 через
локальный overlay-файл `docker-compose.local.yml` (он не коммитится в git):

```yaml
services:
  ewm-db:
    ports: !override
      - "5434:5432"
```

```bash
docker compose -f docker-compose.yml -f docker-compose.local.yml up -d --wait
java -jar core/main-service/target/main-service-0.0.1-SNAPSHOT.jar \
     --spring.datasource.url=jdbc:postgresql://localhost:5434/ewm_main_db
```

Без этого флага `main-service` ищет базу на `localhost:5432`, то есть на вашем локальном PostgreSQL.
Тег `!override` требует Docker Compose 2.24+.

## Тесты

```bash
mvn test
```

Тестовые профили отключают config-server и Eureka (`spring.cloud.config.enabled=false`, `eureka.client.enabled=false`).

Демонстрационные данные (`core/main-service/src/main/resources/demo-data.sql`) не загружаются
автоматически — при необходимости файл можно выполнить вручную:

```bash
psql -h localhost -U postgres -d ewm_main_db -f core/main-service/src/main/resources/demo-data.sql
```

## Спецификации

- `ewm-main-service-spec.json` — API основного сервиса
- `ewm-stats-service-spec.json` — API сервиса статистики
- `postman/feature.json` — коллекция Postman (baseUrl `http://localhost:8080`)
