# 肌松大师 muscle-master

连锁门店推拿预约 / 前台 / 技师 / 管理后台。P0 模块化单体，见 [docs/p0-technical-design.md](docs/p0-technical-design.md)。

## 仓库布局

```
apps/mini-customer   C 端微信原生小程序
apps/mini-staff      员工端（技师 / 前台 / 店长）
apps/admin-web       Vue 3 + Vite + TS + Element Plus
server/              Spring Boot 3.3 / Java 21 单 JAR
deploy/              docker-compose（MySQL 8 + Redis 7 + server + admin）
docs/wechat-onboarding.md
```

## 本机无 Docker（推荐开发）

本机可能没有 Docker。`dev` profile 使用 **H2 内存库**，并关闭 Redis 自动配置，因此 **不需要 MySQL / Redis**。

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
export PATH="$JAVA_HOME/bin:$PATH"

mvn -f server/pom.xml test
mvn -f server/pom.xml spring-boot:run -Dspring-boot.run.profiles=dev
```

健康检查：

```bash
curl --noproxy '*' -s http://127.0.0.1:8080/actuator/health
```

若本机开了 Surge / 系统代理，访问 `127.0.0.1` 必须绕过代理（`--noproxy '*'` 或把 localhost 加入 bypass）。

期望：`{"status":"UP",...}`。Prometheus：`/actuator/prometheus`。

管理后台（代理 `/actuator` → 8080）：

```bash
cd apps/admin-web
npm install
npm run dev          # http://127.0.0.1:5173/health  ·  /catalog
```

`dev` 微信登录可 mock（无需真实 AppID）：`POST /api/v1/c/auth/wechat` `{"code":"dev"}`（C JWT 2h）；`POST /api/v1/staff/auth/wechat` `{"code":"dev-staff"}`（员工 JWT 8h，超管 ALL）。店长/前台/技师：`dev-staff-manager` / `dev-staff-front` / `dev-staff-t1`。目录 `GET /api/v1/c/stores|therapists|projects|symptoms` 无需登录。`/api/v1/f/**` `/a/**` 走 `@StoreScoped` 门店域；`POST /c/bookings` 挂 `CaptchaFilter`（默认关）。

默认 `app.jobs.enabled=false`。Compose 里唯一的 `server` 服务才设为 `true`。

## Schema（Flyway V1–V4）

P0 全量 DDL 在 `server/src/main/resources/db/migration/`：`V1__init.sql`（双资源 slot **不分区**）、`V2__rbac_seed.sql`、`V3__demo_store.sql`（1 店 / 3 技师 / 2 床 / 周模板）、`V4__locknew_free_indexes.sql`（FREE 行锁索引）。默认 profile 对 MySQL 开 Flyway。`dev` 的 H2 **关 Flyway**（DDL 含 VARBINARY / DATETIME(3) / JSON，不为 H2 削 schema）。无 Docker 时用 `SchemaContractTest` 断言 SQL 文本；有 compose MySQL 时跑 `scripts/verify-schema.sh`（**destructive**：DROP+CREATE 独立库 `muscle_master_verify`，不碰 `muscle_master` / Flyway 历史）。预览：`docs/test-cases/schema-preview.html`。

## Docker Compose（有 Docker 的机器）

```bash
docker compose -f deploy/docker-compose.yml up --build
```

- MySQL 8：`3306`，库 `muscle_master` / 用户 `muscle`
- Redis 7：`6379`
- Server：`8080`，`SNOWFLAKE_WORKER_ID=1`，`APP_JOBS_ENABLED=true`（P0 仅这一份 JobRunner）
- Admin：`8081` → nginx，反代 `/actuator`、`/api`

禁止在未改 worker id 的情况下再起第二个 server 实例。

## 微信开通

支付合入前按 [docs/wechat-onboarding.md](docs/wechat-onboarding.md) 勾选 AppID、类目、商户号、APIv3 证书、支付目录、JSAPI 目录。

## 技术栈

Java 21 · Spring Boot 3.3 · MyBatis-Plus 3.5 · Flyway · MapStruct · MySQL 8 · Redis 7 · Micrometer/Prometheus
