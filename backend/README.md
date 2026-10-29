# jinjing2 Java 后端

Java 17 + Spring Boot + PostgreSQL。当前实现第一来源 JSON 导入、原始记录存储、统一探头、评论去重、探头查询和基础人工管理 API。`aa` 原样保存为 `type_code_raw`；统一探头另有按名称分类的 `camera_type`。

## 本机运行

在 `deploy` 目录复制 `.env.example` 为 `.env`，设置两个密码，然后执行：

```bash
docker compose up --build -d
```

应用地址为 `http://127.0.0.1:8080`，数据库仅在容器网络中开放。

## IDEA 中运行 Java 代码

项目根目录打开 `jinjing2` 即可，`backend` 应被识别为 Maven 模块。项目已配置 Java 17 SDK。IDEA 自动生成的 `JinjingApplication` 运行配置可直接使用；后端会从项目根目录的 `deploy/.env` 自动读取本机数据库地址和配置。本机开发配置 `APP_AUTH_ENABLED=false`，访问接口无需账号密码。

本机首次配置已准备好 `deploy/.env`，数据库已由 Compose 启动。在 IDEA 选择 `JinjingApplication` 并点击运行；访问 `http://127.0.0.1:8080/` 打开探头分布查询首页，访问 `http://127.0.0.1:8080/admin.html` 打开原有增删改查管理页面。两个页面和 `/api/v1` 接口由同一个 Spring Boot 进程提供，无需单独启动前端服务。

管理页面支持按名称、探头类型和原始类型编码查询、分页、查看详情及图片评论、新增、编辑、软删除探头。接口仍可独立调用；页面使用 `GET /api/v1/cameras`、`GET /api/v1/cameras/count`、`GET /api/v1/cameras/{id}` 和 `/api/v1/admin/cameras` 写接口。

查询首页参考地图类网站的布局，提供位置搜索、类型筛选、分页和探头详情。地图配置从本机已忽略的 `deploy/.env` 读取：`AMAP_JS_KEY`（Web 端开发 Key）、`AMAP_SECURITY_JS_CODE`（安全密钥）和 `MAP_COORDINATE_SYSTEM=GCJ02`。躲猫猫来源已确认使用 GCJ-02，数据库迁移会标记现有点位，后续导入会继承来源的坐标系。地图接口只返回已标记为 `GCJ02` 的探头；新来源或人工新增的未知坐标不会直接上图。若地图配置缺失，列表与详情仍可使用。修改配置后重新启动 IDEA 中的应用。生产环境建议按高德要求通过服务代理设置安全密钥，不要在公开页面明文暴露。地图点位通过 `/api/v1/cameras/map` 按可视范围、名称及探头类型查询。

地图右下角的定位按钮点击后请求当前位置，并将地图中心移至定位点。浏览器需要允许定位权限；部署到 VPS 后应使用 HTTPS，以便移动端浏览器正常调用定位。

`camera_type` 是非空字符串，数据库限制为 `DISCARD`、`SIX_RING_OUT`、`TWO_RING_IN`、`SIX_RING_IN`。名称含“已废弃”优先归为 `DISCARD`，否则含“六环外”归为 `SIX_RING_OUT`，否则含“二环内”归为 `TWO_RING_IN`，其他名称归为 `SIX_RING_IN`。数据库迁移会补齐旧数据；新增、人工编辑或来源同步更新名称时重新计算。人工保护的名称不会被来源同步覆盖，因此对应的探头类型也保持不变。

若数据库容器被手动停止，从项目根目录执行：

```bash
docker compose -f deploy/compose.yaml -f deploy/compose.dev.yaml up -d db
```

本机 `deploy/.env` 已包含以下配置。它是本地文件，已被 `.gitignore` 排除；不需要在 IDEA 运行配置里再次填写：

```text
DB_URL=jdbc:postgresql://127.0.0.1:15433/jinjing2
DB_USER=jinjing2
DB_PASSWORD=<deploy/.env 中的 DB_PASSWORD>
APP_ADMIN_PASSWORD=<deploy/.env 中的 APP_ADMIN_PASSWORD>
APP_AUTH_ENABLED=false
```

IDEA 与 Compose 的 `app` 服务不要同时启动，因为两者都会占用本机 8080 端口。使用 IDEA 调试时只启动 `db` 服务。IDEA 中的后端和数据库只监听本机回环地址，不向局域网或公网开放。

## 导入文件

```bash
curl -F file=@../duomaomao-cameras.json \
  http://127.0.0.1:8080/api/v1/admin/imports
```

返回 `batchId` 后可查询：

```text
GET /api/v1/admin/imports/{batchId}
GET /api/v1/admin/imports/{batchId}/records?status=FAILED
GET /api/v1/cameras?name=顺义区&limit=100
GET /api/v1/cameras/{id}
GET /api/v1/cameras/{id}/comments?limit=100&offset=0
GET /api/v1/cameras/map?west=116.2&south=39.8&east=116.8&north=40.3
```

本机配置下管理员 API 无需登录。正式部署默认启用 HTTP Basic（`APP_AUTH_ENABLED=true`），并应通过 HTTPS 反向代理访问；不要将本机的 `APP_AUTH_ENABLED=false` 复制到公网环境。探头 CRUD 使用 `POST /api/v1/admin/cameras`、`PATCH /api/v1/admin/cameras/{id}`、`DELETE /api/v1/admin/cameras/{id}?version=...`、`POST /api/v1/admin/cameras/{id}/restore`。修改请求需提供 `version`。

首次导入此文件按来源 ID 建立 7,322 条探头；同名探头不自动合并。评论按探头内昵称、时间、内容精确去重。重新导入不会产生重复探头或评论。人工修改后的名称和坐标受保护，来源类型仍可以同步更新；详情失败时不清空评论。

## 当前边界

- 当前只接入 `duomaomao` JSON 格式；定时抓取项目、多来源冲突处理、类型映射和图片导入随后接入。
- 地图接口当前返回最多 10,000 个已确认 GCJ-02 的点，尚未做缩放聚合或其他坐标系转换。
- 导入任务在单实例后台串行执行。应用重启时未完成任务需重新上传同一文件，导入具备幂等性。生产多实例部署前需将任务调度迁移到数据库锁或任务队列。
- 本 Compose 仅供本机开发和 VPS 内部试运行。公网发布还需配置域名、HTTPS、Nginx 及备份任务。
