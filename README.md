# 进京了么（jinjing2）

Java 17、Spring Boot、PostgreSQL 项目。`backend` 负责地图、探头管理、定时任务和统一数据表；`duomaomao-spring` 作为 Maven 依赖提供躲猫猫采集能力。运行时只启动 `backend`，无需另起采集服务。

## 本机 IDEA 启动

1. 在 IDEA 中打开本目录的 `pom.xml`，选择 Java 17 SDK，等待两个 Maven 模块导入完成。
2. 在 `deploy/.env` 配置数据库地址、密码和本机管理开关；若尚未配置，参考 `deploy/.env.example`。本机启动数据库：

   ```bash
   docker compose --env-file deploy/.env -f deploy/compose.yaml -f deploy/compose.dev.yaml up -d db
   ```

3. 在 IDEA 运行 `backend` 模块的 `JinjingApplication`。建议工作目录设为 `backend`；程序也能从项目根目录运行。默认读取 `deploy/.env`，打开 [地图首页](http://127.0.0.1:8080/)、[探头管理](http://127.0.0.1:8080/admin.html)、[定时抓取](http://127.0.0.1:8080/sync.html)。

本机 `APP_AUTH_ENABLED=false` 时管理操作无需账号密码。生产环境应使用 `APP_AUTH_ENABLED=true` 和不少于 12 位的 `APP_ADMIN_PASSWORD`；定时抓取页和 `/api/v1/admin/**` 使用 HTTP Basic，探头管理页的写操作也通过管理接口鉴权。通过 HTTPS 反向代理访问。未配置躲猫猫凭据时 backend 仍能启动，定时抓取页会显示“未配置”，手动抓取按钮不可用。

## 抓取凭据与调度

躲猫猫云端令牌通过 `DUOMAOMAO_BEARER_TOKEN` 或 `DUOMAOMAO_BEARER_TOKEN_FILE` 提供，优先使用前者。IDEA 可把这两个配置之一写在被 Git 忽略的 `deploy/.env`，文件形式可填绝对路径；Docker Compose 从 `deploy/.env` 读取 `DUOMAOMAO_BEARER_TOKEN` 注入容器。令牌不要写入代码、README、提交记录或镜像。采集服务使用的云端地址和加密公钥来自配置，默认值见 `backend/src/main/resources/application.yml`；需要调整时用 `DUOMAOMAO_BASE_URL`、`DUOMAOMAO_PUBLIC_KEY` 覆盖。

`APP_SYNC_ENABLED=false` 是开发默认值；管理员仍可在网页点击“立即抓取”。VPS 上把它设为 `true`，按北京时间每日 03:00 执行；可用 `APP_SYNC_CRON` 修改 Spring cron 表达式。不要让本机和 VPS 同时向同一个数据库运行自动调度。一个来源同时只允许一条活动任务；应用重启后，未结束任务标为“已中断”，可重新触发。每次全量抓取会请求约 7,300 条探头详情，耗时取决于上游服务；可用 `DUOMAOMAO_EXPORT_CONCURRENCY` 调整详情并发数，默认 6。

任务状态为“等待执行、正在抓取、正在归档、正在入库、已完成、部分完成、失败、已中断”。少量评论详情失败时继续同步，并保留失败探头的旧评论；抓取失败或得到空列表时不导入。任务页显示采集和入库进度、失败与待核对记录、逐条操作留痕、原始 JSON 下载，并支持手动重跑。

## 数据归档与清洗

成功抓取后，JSON 原子写入根目录 `定时数据抓取清单/duomaomao_yyyyMMdd.json`。同日再次成功抓取会替换当天文件；每次导入还会按文件 SHA-256 保存不可变副本到 `APP_IMPORT_DIRECTORY`，因此历史任务仍能下载当时的数据。两个目录应纳入 VPS 备份。Docker Compose 将项目根目录的归档目录挂载到容器 `/app/data/snapshots`，并用命名卷保存导入副本；使用 `APP_SNAPSHOT_DIRECTORY` 可指定其他位置。归档文件、导入副本和凭据均不提交到 Git。

来源 ID 只在各自数据源内唯一，统一探头使用数据库主键。同一名称经空白、方向括号和“六环外”等标记规范化后，若方向明确且 GCJ-02 坐标相距不超过 100 米，则关联同一个统一探头；远距同名、无明确方向或两条人工保护记录会进入待核对列表。合并时保留来源关联、评论和图片及审计记录。人工删除不会被同步恢复；来源本次未出现的记录保持原状。人工修正的名称、坐标受到保护，类型、图片和评论仍可同步。评论按昵称、时间、内容去重；图片按 URL 去重。`aa` 原值保存在 `type_code_raw`，类型显示为中文。

数据库的 `sync_run`、`sync_event` 保存任务与阶段；`import_batch`、`import_record` 保存每批和每条结果；`camera_audit_log` 保存人工操作及同步造成的探头、评论、图片变更前后内容。所有数据更新由 Flyway 迁移管理。

## 管理接口

| 地址 | 用途 |
| --- | --- |
| `GET /api/v1/admin/sync/config` | 凭据状态、调度开关、下次执行时间和归档目录 |
| `POST /api/v1/admin/sync/runs` | 手动发起全量抓取，返回任务 ID；已有活动任务返回 409 |
| `GET /api/v1/admin/sync/runs` | 分页查询历史任务 |
| `GET /api/v1/admin/sync/runs/{id}` | 当前阶段、进度、统计与错误 |
| `GET /api/v1/admin/sync/runs/{id}/records` | 分页查询逐条导入结果，可按 `status`、`reviewOnly` 筛选 |
| `GET /api/v1/admin/sync/runs/{id}/records/{sourceId}` | 查看该任务归档中的来源原文 |
| `GET /api/v1/admin/sync/runs/{id}/events` | 阶段与错误事件 |
| `GET /api/v1/admin/sync/runs/{id}/audit` | 该任务实际变更的审计记录 |
| `GET /api/v1/admin/sync/audit` | 所有操作留痕，可按 `cameraId` 筛选 |
| `GET /api/v1/admin/sync/runs/{id}/file` | 下载该次任务的原始 JSON |

分页接口使用 `offset` 和 `limit`，`limit` 最大 500。探头和原有导入接口见 [接口文档](backend/接口文档.md)。

## VPS 部署与排查

在 VPS 上安装 Docker Compose，把 `deploy/.env.example` 复制为 `deploy/.env`，填写数据库密码、管理员密码和躲猫猫令牌，设置 `APP_AUTH_ENABLED=true`、`APP_SYNC_ENABLED=true`，然后在项目根目录执行：

```bash
mkdir -p 定时数据抓取清单
sudo chown 10001:10001 定时数据抓取清单
docker compose --env-file deploy/.env -f deploy/compose.yaml up --build -d
```

容器内应用使用 UID 10001，归档目录需允许该用户写入。Compose 只把应用端口绑定到 `127.0.0.1:8080`；对公网提供服务时需另配 HTTPS 反向代理。检查状态可访问 `/actuator/health`，或查看容器日志：

```bash
docker compose --env-file deploy/.env -f deploy/compose.yaml logs --tail=200 app
```

排查顺序：先在 `/sync.html` 看凭据、任务阶段与错误；“抓取失败”检查令牌和上游连通性，“归档失败”检查目录写权限和磁盘，“入库失败”查看逐条记录和 `import_batch` 错误。文件未生成时先确认任务是否完成抓取；重启后的中断任务可手动重跑。数据库和归档目录应定期备份；VPS 更新代码后执行上述 `up --build -d`，Flyway 会在应用启动时执行迁移。
