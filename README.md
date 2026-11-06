# 进京了（jinjing2）

Java 17、Spring Boot、PostgreSQL 项目。`backend` 负责地图、探头管理、定时任务和统一数据表；`duomaomao-spring` 作为 Maven 依赖提供躲猫猫采集能力。运行时只启动 `backend`，无需另起采集服务。

## 本机 IDEA 启动

1. 在 IDEA 中打开本目录的 `pom.xml`，选择 Java 17 SDK，等待两个 Maven 模块导入完成。
2. 在 `deploy/.env` 配置数据库地址、密码和本机管理开关；若尚未配置，参考 `deploy/.env.example`。本机启动数据库：

   ```bash
   docker compose --env-file deploy/.env -f deploy/compose.yaml -f deploy/compose.dev.yaml up -d db
   ```

3. 在 IDEA 运行 `backend` 模块的 `JinjingApplication`。建议工作目录设为 `backend`；程序也能从项目根目录运行。默认读取 `deploy/.env`，打开 [地图首页](http://127.0.0.1:8080/) 或 [统一管理后台](http://127.0.0.1:8080/manage.html)。后台左侧菜单可切换探头管理、定时抓取、抓取审核和用户上报审核。

本机 `APP_AUTH_ENABLED=false` 时管理操作无需账号密码。生产环境使用 `APP_AUTH_ENABLED=true` 和不少于 12 位的 `APP_ADMIN_PASSWORD`，仅有 `APP_ADMIN_USER` 对应的一个管理员账号，当前用户名为 `adsmaxa`（在 `deploy/.env` 中配置）。地图首页 `/` 和公开查询接口无需登录；访问 `/manage.html` 或任一管理单页会跳转到 `/login.html`，登录后使用会话 Cookie 并可从后台退出。所有管理页面资源及 `/api/v1/admin/**` 均要求管理员会话，管理写操作还需 CSRF 令牌，网页自动处理；脚本调用方法见 [接口文档](backend/接口文档.md)。VPS 通过 `APP_SESSION_COOKIE_SECURE=true` 强制会话 Cookie 仅随 HTTPS 请求发送，管理员请使用正式 HTTPS 地址。未配置躲猫猫凭据时 backend 仍能启动，定时抓取页会显示“未配置”，手动抓取按钮不可用。

小程序路线页会并行请求躲猫猫路线和高德探头避让路线，最多等待 3 分钟后展示已有结果；地图用不同颜色显示两条线路。高德路线由 Java 后端调用 Web 服务 API，需另配 `AMAP_WEB_SERVICE_KEY`，不使用网页地图的 `AMAP_JS_KEY`；未配置时躲猫猫路线仍可用（需有效试用或会员），弹窗说明高德未配置。高德路线只校验数据库中未删除、非废弃、GCJ-02 的探头位置，默认避让半径 100 米，可用 `APP_ROUTE_CAMERA_RADIUS_METERS` 调整。高德不判断进京证条件，证件选择仅用于躲猫猫路线。详见 [路线规划方案](路线规划方案.md)。

## 抓取凭据与调度

躲猫猫云端令牌通过 `DUOMAOMAO_BEARER_TOKEN` 或 `DUOMAOMAO_BEARER_TOKEN_FILE` 提供，优先使用前者。IDEA 可把这两个配置之一写在被 Git 忽略的 `deploy/.env`，文件形式可填绝对路径；Docker Compose 从 `deploy/.env` 读取 `DUOMAOMAO_BEARER_TOKEN` 注入容器。令牌不要写入代码、README、提交记录或镜像。采集服务使用的云端地址和加密公钥来自配置，默认值见 `backend/src/main/resources/application.yml`；需要调整时用 `DUOMAOMAO_BASE_URL`、`DUOMAOMAO_PUBLIC_KEY` 覆盖。

`APP_SYNC_ENABLED=false` 是开发默认值；管理员仍可在网页点击“立即抓取”。VPS 上把它设为 `true`，按北京时间每日 03:00 执行；可用 `APP_SYNC_CRON` 修改 Spring cron 表达式。不要让本机和 VPS 同时向同一个数据库运行自动调度。一个来源同时只允许一条活动任务；应用重启后，未结束任务标为“已中断”，可重新触发。每次全量抓取会请求约 7,300 条探头详情，耗时取决于上游服务；可用 `DUOMAOMAO_EXPORT_CONCURRENCY` 调整详情并发数，默认 6。

任务状态为“等待执行、正在抓取、正在归档、正在生成预览、等待人工审核、正在入库、已完成、部分完成、失败、已中断”。每日 03:00 或手动抓取完成后只归档并生成审核暂存表，**不会自动更新统一探头表**。管理员在 `/review.html` 逐条确认疑点或排除记录，再点“审核通过并入库”才执行原有清洗、去重和更新逻辑。少量评论详情失败时继续生成预览，审核入库后保留详情失败探头的旧评论；抓取失败或得到空列表时不入库。页面展示预览、进度、原始 JSON、逐条结果和审计记录；同一来源等待审核的旧批次不妨碍次日继续抓取。

每次抓取预览完成后向 `APP_MAIL_TO` 发送中文 HTML 表格，列出分类数量和最多 100 条变化记录；抓取失败也发送失败摘要。邮件不代表已入库。VPS 沿用现有 163 SMTP，推荐把已有 `/opt/maintenance/secrets/alert.env` 复制为权限受限的 `deploy/mail.env`，通过 `deploy/compose.vps.yaml` 只读挂载，内容使用 `SMTP_EMAIL` 和 `SMTP_AUTH_CODE`；收件地址在 `deploy/.env` 设置为 `qiuyanfeng678@163.com`，`APP_PUBLIC_BASE_URL` 可填正式 HTTPS 地址以生成审核链接。本机可通过 `APP_MAIL_FROM`、`APP_MAIL_AUTH_CODE`、`APP_MAIL_TO` 配置，不配置也能启动和手动抓取，页面会显示邮件“未配置”。发信失败留痕并可在审核页重发，不影响归档和审核。

## 数据归档与清洗

成功抓取后，JSON 原子写入根目录 `定时数据抓取清单/duomaomao_yyyyMMdd.json`。同日再次成功抓取会替换当天文件；每次导入还会按文件 SHA-256 保存不可变副本到 `APP_IMPORT_DIRECTORY`，因此历史任务仍能下载当时的数据。两个目录应纳入 VPS 备份。Docker Compose 将项目根目录的归档目录挂载到容器 `/app/data/snapshots`，并用命名卷保存导入副本；使用 `APP_SNAPSHOT_DIRECTORY` 可指定其他位置。归档文件、导入副本和凭据均不提交到 Git。

来源 ID 只在各自数据源内唯一，统一探头使用数据库主键。同一名称经空白、方向括号和“六环外”等标记规范化后，若方向明确且 GCJ-02 坐标相距不超过 100 米，则关联同一个统一探头；远距同名、无明确方向或两条人工保护记录会进入待核对列表。合并时保留来源关联、评论和图片及审计记录。人工删除不会被同步恢复；来源本次未出现的记录保持原状。人工修正的名称、坐标受到保护，类型、图片和评论仍可同步。来源评论按昵称、时间、内容去重；网站访客评论单独保存并标记来源。图片按 URL 去重。`aa` 原值保存在 `type_code_raw`，类型显示为中文。

数据库的 `sync_run`、`sync_event` 保存任务、阶段和邮件状态；`sync_review_item` 持久化每条预览、审核决定、排除原因和最终结果；`import_batch`、`import_record` 保存每批和每条入库结果；`camera_audit_log` 保存管理员操作、网站评论及审核入库造成的探头、评论、图片变更前后内容。审核前的预览不会更新统一探头表。所有结构变更由 Flyway 迁移管理。

小程序“我的 → 用户反馈”用于上报遗漏探头，填写昵称和文字线索；截图选填，上传时最大 1 MB。`camera_report` 在 PostgreSQL 中持久化原始上报及可选截图；管理员在 [用户上报审核页](http://127.0.0.1:8080/manage.html?page=reports) 左侧补齐探头名称、GCJ-02 坐标、原始类型编码，右侧核对上报信息。通过后才新增探头并保留上报关联，驳回会保留原因。截图默认只作为管理员审核材料；有截图时审核员可开启“将截图设为探头图片”，开启后才会在探头详情公开，选择会持久化并写入审计。该页面和原始截图接口仅管理员可访问。VPS 升级后 Flyway 自动迁移数据库；请把数据库纳入备份。小程序后台需为正式域名配置 `request` 合法域名；上传截图还需配置 `uploadFile` 合法域名。

上报审核页可先用道路名或路口名称搜索。页面调用高德地理编码显示北京候选地址，选中后将小地图移动到大致位置，并根据地址匹配精度调整缩放；此时不会填写探头经纬度。管理员再点击地图、拖动候选标记或使用地图中心选定具体位置，回填 GCJ-02 坐标。依据二环和六环的实际边界自动推荐二环内、六环内或六环外，边界附近提示人工复核；管理员可手动改为其他类型，最终选择会写入 `camera.camera_type`。环路边界源自 OpenStreetMap 路线关系（[二环](https://www.openstreetmap.org/relation/295094)、[六环](https://www.openstreetmap.org/relation/295982)），遵循 ODbL，来源与更新脚本见 `scripts/update-ring-boundaries.py`。

用户新评论日报默认仅统计 `camera_comment.origin=USER`，按评论写入时间选取北京时间昨日 00:00 至今日 00:00 的记录，每天 00:00 发往 `qiuyanfeng678@163.com`。邮件以探头名称为一级标题，下面列出新增评论正文、昵称和时间；即使无评论也发送空日报。VPS 的 `deploy/compose.vps.yaml` 默认启用 `APP_COMMENT_REPORT_ENABLED=true`，沿用现有只读挂载的 163 SMTP 凭据；本机默认关闭自动发送。可以用 `APP_COMMENT_REPORT_TO` 调整收件地址。发送状态、次数和错误持久化在 `daily_comment_report`，启动时会补发尚未发送的昨日日报，已发送的日期不会重复发送；失败时可在 [新增评论页](https://jinjingle.yixiangshe.com/manage.html?page=comments) 重试。该页及对应接口只允许管理员访问。

## 管理接口

| 地址 | 用途 |
| --- | --- |
| `GET /api/v1/admin/sync/config` | 凭据状态、调度开关、下次执行时间和归档目录 |
| `POST /api/v1/admin/sync/runs` | 手动发起全量抓取，返回任务 ID；已有活动任务返回 409 |
| `GET /api/v1/admin/sync/runs` | 分页查询历史任务 |
| `GET /api/v1/admin/sync/runs/{id}` | 当前阶段、进度、统计与错误 |
| `GET /api/v1/admin/sync/runs/{id}/review-summary` | 分类统计及未处理疑点数量 |
| `GET /api/v1/admin/sync/runs/{id}/reviews` | 分页查询逐条预览，可按 `prediction`、`decision` 筛选 |
| `GET /api/v1/admin/sync/runs/{id}/reviews/{rowNumber}` | 查看来源原文及现有探头快照 |
| `POST /api/v1/admin/sync/runs/{id}/reviews/{rowNumber}/confirm` | 确认疑点记录 |
| `POST /api/v1/admin/sync/runs/{id}/reviews/{rowNumber}/reject` | 排除该条记录，可传 `{"reason":"..."}` |
| `POST /api/v1/admin/sync/runs/{id}/reviews/{rowNumber}/reset` | 撤销逐条审核决定 |
| `POST /api/v1/admin/sync/runs/{id}/approve` | 整批审核通过并开始入库 |
| `POST /api/v1/admin/sync/runs/{id}/mail/retry` | 重发失败或未配置的摘要邮件 |
| `GET /api/v1/admin/sync/runs/{id}/records` | 分页查询逐条导入结果，可按 `status`、`reviewOnly` 筛选 |
| `GET /api/v1/admin/sync/runs/{id}/records/{sourceId}` | 查看该任务归档中的来源原文 |
| `GET /api/v1/admin/sync/runs/{id}/events` | 阶段与错误事件 |
| `GET /api/v1/admin/sync/runs/{id}/audit` | 该任务实际变更的审计记录 |
| `GET /api/v1/admin/sync/audit` | 所有操作留痕，可按 `cameraId` 筛选 |
| `GET /api/v1/admin/sync/runs/{id}/file` | 下载该次任务的原始 JSON |

分页接口使用 `offset` 和 `limit`，`limit` 最大 500。探头和原有导入接口见 [接口文档](backend/接口文档.md)。

## VPS 部署与排查

完整的首次部署、现有数据迁移、访问与备份步骤见 [VPS部署指南.md](VPS部署指南.md)。

在 VPS 上安装 Docker Compose，把 `deploy/.env.example` 复制为 `deploy/.env`，填写数据库密码、管理员密码和躲猫猫令牌，设置 `APP_AUTH_ENABLED=true`、`APP_SYNC_ENABLED=true`，并按 [VPS部署指南.md](VPS部署指南.md) 准备 `deploy/mail.env`，然后在项目根目录执行：

```bash
mkdir -p 定时数据抓取清单
sudo chown 10001:10001 定时数据抓取清单
docker compose --env-file deploy/.env -f deploy/compose.yaml -f deploy/compose.vps.yaml up --build -d
```

容器内应用使用 UID 10001，归档目录需允许该用户写入。Compose 只把应用端口绑定到 `127.0.0.1:8080`；对公网提供服务时需另配 HTTPS 反向代理。检查状态可访问 `/actuator/health`，或查看容器日志：

```bash
docker compose --env-file deploy/.env -f deploy/compose.yaml logs --tail=200 app
```

排查顺序：先在 `/sync.html` 看凭据、任务阶段与错误；“抓取失败”检查令牌和上游连通性，“归档失败”检查目录写权限和磁盘，“邮件未配置/失败”检查 `deploy/mail.env`、`APP_MAIL_TO` 和 SMTP 连通性；在 `/review.html` 处理疑点并人工入库，“入库失败”查看逐条记录和 `import_batch` 错误。文件未生成时先确认任务是否完成抓取；重启后的中断任务可手动重跑。数据库和归档目录应定期备份；VPS 更新代码后执行上述 `up --build -d`，Flyway 会在应用启动时执行迁移。

## Android 设备会员（本地代码，尚未部署 VPS）

Android 客户端首次启动自动登记设备标识，用户无需注册或输入小程序动态码。管理员可在 `/manage.html?page=android-users` 查找设备并手动设置 30 天或 365 天会员；操作历史在 `/manage.html?page=android-user-history`。每次路线规划前 Android 会向后端查询当前会员状态，后端规划及异步结果接口也再次验证。Android 账号与微信小程序账号不互通；清除 App 数据或卸载重装会生成新设备账号。详见 [Android 使用说明](Android/README.md) 和 [接口文档](backend/接口文档.md)。

Android 个人中心新增“联系客服”和“开通会员”，均打开微信二维码页。管理员在 Android 用户管理页底部上传二维码图片，保存在数据库中；用户联系并付款后，发送设备标识，由管理员核对并开通会员。尚未上传二维码时 App 明确提示未配置。

## 会员体系（待微信真机联调）

地图查询免费。小程序启动时通过微信 OpenID 登录：**首次创建的账号自动获得一次 30 天会员**，重复启动、重新安装或重新登录都不会重复领取；赠送权益从首次登录起算，不再额外叠加两天试用。升级前已存在的账号不补领，仍按原有两天试用或会员权益处理。躲猫猫与高德路线规划均由后端验证会员 Bearer 会话，异步结果也核对任务归属。月会员 10 元（30 天）、年会员 108 元（365 天），从现有到期时间继续累加。“我的 → 会员服务”展示状态、订单、套餐和十分钟一次性 App 登录动态码。小程序原有的 App 动态码接口继续保留，但 Android 客户端使用上文独立的设备账号；iOS 客户端尚未开发。

在私有 `deploy/.env` 填写 `WECHAT_APP_ID`、`WECHAT_APP_SECRET`，以及微信虚拟支付后台的 `WECHAT_VIRTUAL_PAY_OFFER_ID`、`WECHAT_VIRTUAL_PAY_APP_KEY`、`WECHAT_VIRTUAL_PAY_MONTH_PRODUCT_ID`、`WECHAT_VIRTUAL_PAY_YEAR_PRODUCT_ID`、`WECHAT_VIRTUAL_PAY_MESSAGE_TOKEN`、`WECHAT_VIRTUAL_PAY_MESSAGE_AES_KEY`。小程序 AppID 必须一致；商品须在小程序后台发布，月卡价格 1000 分、年卡 10800 分。微信消息推送配置 JSON 安全模式，URL 为 `https://jinjingle.yixiangshe.com/api/v1/members/payment-notify`。凭据不得提交到仓库。**虚拟支付审核期间可以只配置 AppID 和 AppSecret 上线赠送权益**；未配置支付时后端仍启动、购买按钮显示“暂不可购买”。

后台通过 `/manage.html?page=members` 管理会员，通过 `/manage.html?page=member-history` 查询独立的管理员操作历史。首登赠送记录在 `member_entitlement`，来源为 `WELCOME`，不是管理员操作；每次人工补发、调整、撤销都要求原因，成功写入和审计同一事务，失败尝试单独留痕。支付平台通知另存 `member_payment_event`。接口字段和状态码见 [接口文档](backend/接口文档.md)。虚拟支付通过审核后，再用真机核对微信订单、权益、退款和 iOS 消息推送，验证完成前保持购买配置为空。
