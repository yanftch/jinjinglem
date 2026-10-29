# VPS 部署指南

本指南适用于当前 `jinjing2`：Java 17 后端同时提供网页和接口，PostgreSQL 由 Docker Compose 启动。部署目标是现有的 Oracle Ubuntu 24.04 ARM64 VPS；服务器已有 Docker 和 Compose。以下命令默认在项目根目录执行，服务器登录用户为 `ubuntu`。

**当前部署（2026-10-29）**：项目位于 VPS 的 `/home/ubuntu/jinjing2`，已迁移本机的 7,304 条探头及历史任务；`APP_SYNC_ENABLED=true`，下次定时执行为北京时间 2026-10-30 03:00。Cloudflare 的 `oracle-vps-newapi` Tunnel 已新增 `jinjinglem.yixiangshe.com → http://localhost:8080` 路由并创建 CNAME 记录。正式页面可从 [网页地址.md](网页地址.md) 点击进入。

## 1. 发布代码与准备环境

先在 Mac 上提交本次要发布的代码并推送到 GitHub。VPS 通过仓库地址获取代码；只在本机存在、尚未推送的提交不会出现在 VPS 上。

```bash
cd /Users/qiuyanfeng/Documents/develop/Oracle/jinjing2
git status -sb
git push origin main
ssh -i ~/.ssh/oracle-osaka-vps.key ubuntu@141.147.157.68
```

首次在 VPS 上执行：

```bash
git clone https://github.com/yanftch/jinjinglem.git ~/jinjing2
cd ~/jinjing2
cp deploy/.env.example deploy/.env
chmod 600 deploy/.env
nano deploy/.env
```

在 `deploy/.env` 中至少填写 `DB_PASSWORD`、`APP_ADMIN_PASSWORD`（至少 12 位）、`DUOMAOMAO_BEARER_TOKEN`；保持 `APP_AUTH_ENABLED=true`。地图使用高德时填写 `AMAP_JS_KEY`、`AMAP_SECURITY_JS_CODE`，并设 `MAP_COORDINATE_SYSTEM=GCJ02`。这些值只放在服务器的 `.env` 中，不提交到 Git。示例文件里的 `DB_URL` 是本机开发地址；生产 Compose 会覆盖为容器内的数据库地址，不需要照着它修改数据库端口。

`APP_SYNC_CRON=0 0 3 * * *` 表示每天北京时间 03:00。部署完成后将 `APP_SYNC_ENABLED=true`；如果要先迁移本机数据，暂时保持 `false`，待迁移验证完再开启。开发机继续保持 `false`，避免两边重复定时抓取。

本 VPS 已有 163 SMTP 凭据文件 `/opt/maintenance/secrets/alert.env`。把它复制到本项目专用、仅容器用户可读的文件，原文件保持不变；不要把口令写入仓库或 Compose 命令：

```bash
cd ~/jinjing2
sudo install -o 10001 -g 10001 -m 0400 /opt/maintenance/secrets/alert.env deploy/mail.env
```

在 `deploy/.env` 中设 `APP_MAIL_TO=yanftch@163.com`、`APP_PUBLIC_BASE_URL=https://jinjinglem.yixiangshe.com`，摘要邮件将带审核页链接。`deploy/compose.vps.yaml` 把 `deploy/mail.env` 只读挂载到应用容器，不会将凭据打进镜像；该文件已被 Git 忽略。检查合并后的 Compose 配置：

```bash
sudo docker compose -p jinjing2 --env-file deploy/.env -f deploy/compose.yaml -f deploy/compose.vps.yaml config --quiet
```

## 2. 选择数据初始化方式

### 方式 A：从空数据库开始

如果不需要本机现有探头、历史任务和留痕，在 `.env` 中设 `APP_SYNC_ENABLED=true`，然后执行：

```bash
cd ~/jinjing2
mkdir -p 定时数据抓取清单
sudo chown 10001:10001 定时数据抓取清单
sudo docker compose -p jinjing2 --env-file deploy/.env -f deploy/compose.yaml -f deploy/compose.vps.yaml up --build -d
```

应用启动时 Flyway 自动建表。定时任务会在下一个北京时间 03:00 运行；也可登录 `/sync.html`，点击“立即抓取”验证。抓取完成后会生成归档和 `/review.html` 审核预览，并向配置的邮箱发送表格摘要；**此时统一探头表不会变化**。管理员逐条处理疑点并确认整批入库后，数据才显示在地图。空库上线不会带入本机已有的约七千条探头数据。

### 方式 B：迁移本机数据库和历史文件

这条路径保留探头、人工修改、导入批次、任务流水和操作留痕。仅适用于 VPS 上**尚未导入过数据**的全新数据库；若 VPS 已有数据，应先备份并制定合并方案，不能直接恢复到现有库。迁移期间先等待本机正在运行的抓取完成，并让 VPS 的 `APP_SYNC_ENABLED=false`。

在 Mac 的项目根目录导出数据库和文件。以下命令适用于本机使用项目的 Compose 开发数据库；若 IDEA 连接的是另一个 PostgreSQL 实例，应从实际数据库运行 `pg_dump -Fc`：

```bash
cd /Users/qiuyanfeng/Documents/develop/Oracle/jinjing2
mkdir -p ~/jinjing2-migration
docker compose --env-file deploy/.env -f deploy/compose.yaml -f deploy/compose.dev.yaml exec -T db \
  pg_dump -U jinjing2 -d jinjing2 -Fc > ~/jinjing2-migration/jinjing2.dump
tar -czf ~/jinjing2-migration/files.tar.gz -C . data/imports 定时数据抓取清单
scp -i ~/.ssh/oracle-osaka-vps.key \
  ~/jinjing2-migration/jinjing2.dump ~/jinjing2-migration/files.tar.gz \
  ubuntu@141.147.157.68:/home/ubuntu/
```

在 VPS 上创建空数据库并恢复。恢复出错时先查明原因，避免在残留的半成品数据库上继续：

```bash
cd ~/jinjing2
sudo docker compose -p jinjing2 --env-file deploy/.env -f deploy/compose.yaml up -d db
sudo docker compose -p jinjing2 --env-file deploy/.env -f deploy/compose.yaml exec -T db \
  pg_restore --exit-on-error --no-owner --no-acl -U jinjing2 -d jinjing2 \
  < ~/jinjing2.dump
tar -xzf ~/files.tar.gz -C .
sudo chown -R 10001:10001 定时数据抓取清单
```

本机数据库里的历史文件路径是 Mac 的绝对路径。将它们改成容器内路径，历史任务的 JSON 下载才会正常工作：

```bash
sudo docker compose -p jinjing2 --env-file deploy/.env -f deploy/compose.yaml exec -T db \
  psql -U jinjing2 -d jinjing2 -v ON_ERROR_STOP=1 <<'SQL'
BEGIN;
UPDATE import_batch
SET file_path = '/app/data/imports/' || regexp_replace(file_path, '^.*/', '');
UPDATE sync_run
SET archive_path = '/app/data/snapshots/' || regexp_replace(archive_path, '^.*/', '')
WHERE archive_path IS NOT NULL;
COMMIT;
SQL
```

启动应用，再把本机历史导入副本放入应用使用的 `import_data` 卷：

```bash
sudo docker compose -p jinjing2 --env-file deploy/.env -f deploy/compose.yaml -f deploy/compose.vps.yaml up --build -d app
sudo docker compose -p jinjing2 --env-file deploy/.env -f deploy/compose.yaml -f deploy/compose.vps.yaml cp \
  data/imports/. app:/app/data/imports/
sudo docker compose -p jinjing2 --env-file deploy/.env -f deploy/compose.yaml -f deploy/compose.vps.yaml exec -u root app \
  chown -R 10001:10001 /app/data/imports
```

确认页面、历史任务下载和数据数量后，编辑 `deploy/.env`，将 `APP_SYNC_ENABLED=true`，然后重建应用容器使环境变量生效：

```bash
sudo docker compose -p jinjing2 --env-file deploy/.env -f deploy/compose.yaml -f deploy/compose.vps.yaml \
  up -d --force-recreate --no-deps app
```

## 3. 检查服务与访问页面

在 VPS 上查看容器、健康状态和日志：

```bash
cd ~/jinjing2
sudo docker compose -p jinjing2 --env-file deploy/.env -f deploy/compose.yaml -f deploy/compose.vps.yaml ps
curl -fsS http://127.0.0.1:8080/actuator/health
sudo docker compose -p jinjing2 --env-file deploy/.env -f deploy/compose.yaml -f deploy/compose.vps.yaml logs --tail=200 app
```

应用只绑定 VPS 的 `127.0.0.1:8080`，不会直接开放公网 8080。可先在 Mac 建立 SSH 隧道；本机 8080 已用于 IDEA 时，改用本机 18080：

```bash
ssh -i ~/.ssh/oracle-osaka-vps.key -L 18080:127.0.0.1:8080 ubuntu@141.147.157.68
```

保持该 SSH 会话打开，然后访问：

| 页面 | 隧道访问地址 | 用途 |
| --- | --- | --- |
| 地图首页 | <http://127.0.0.1:18080/> | 探头分布 |
| 数据管理 | <http://127.0.0.1:18080/admin.html> | 探头增删改查 |
| 定时抓取 | <http://127.0.0.1:18080/sync.html> | 手动抓取、进度、历史和留痕 |
| 抓取审核 | <http://127.0.0.1:18080/review.html> | 逐条预览、处理疑点、人工确认入库与邮件状态 |

`/sync.html`、`/review.html` 和 `/api/v1/admin/**` 使用管理员 HTTP Basic 认证，账号和密码来自 `.env`。公网通过 VPS 已运行的 Cloudflare Tunnel 访问；`cloudflared` 使用主机网络，路由将 `jinjinglem.yixiangshe.com` 指向 `http://localhost:8080`。管理页面请使用 HTTPS，地址见 [网页地址.md](网页地址.md)。

## 4. 日常更新与备份

代码推送后，在 VPS 项目目录执行：

```bash
cd ~/jinjing2
git pull --ff-only
sudo docker compose -p jinjing2 --env-file deploy/.env -f deploy/compose.yaml -f deploy/compose.vps.yaml \
  up --build -d app
```

当前 VPS 的通用备份脚本**尚未包含 `jinjing2`**。上线前需把以下三类数据加入定期备份，并做一次恢复演练：

1. PostgreSQL `jinjing2` 数据库：使用 `pg_dump -Fc` 导出；其中包含探头、任务、导入和审计记录。
2. Compose 命名卷 `jinjing2_import_data`：保存每次导入时按 SHA-256 留存的 JSON 副本。
3. 项目目录 `~/jinjing2/定时数据抓取清单`：保存每天的抓取 JSON。

在项目根目录手动备份一次可使用：

```bash
backup_dir="$HOME/jinjing2-backups/$(date +%Y%m%d-%H%M%S)"
mkdir -p "$backup_dir/imports"
sudo docker compose -p jinjing2 --env-file deploy/.env -f deploy/compose.yaml exec -T db \
  pg_dump -U jinjing2 -d jinjing2 -Fc > "$backup_dir/jinjing2.dump"
sudo docker compose -p jinjing2 --env-file deploy/.env -f deploy/compose.yaml -f deploy/compose.vps.yaml cp \
  app:/app/data/imports/. "$backup_dir/imports/"
tar -czf "$backup_dir/snapshots.tar.gz" 定时数据抓取清单
```

备份完成后检查三个产物，再将这些步骤接入 VPS 的定期备份方案，并把备份复制到 VPS 以外的位置。

`deploy/.env` 和 `deploy/mail.env` 也应通过安全方式单独备份。仅备份数据库会导致历史原始 JSON 无法下载；仅备份文件则无法恢复探头、审核决定和留痕。升级前先备份数据库和文件；Flyway 迁移可能改变表结构，代码回退时也要评估数据库版本。不要运行 `docker compose down -v`，它会删除数据库和导入副本的命名卷。

## 5. 常见问题

| 现象 | 检查位置 |
| --- | --- |
| “立即抓取”不可用，显示“未配置” | `.env` 中的 `DUOMAOMAO_BEARER_TOKEN`；修改后重建应用容器 |
| 每天 03:00 没有任务 | `APP_SYNC_ENABLED=true`、`APP_SYNC_CRON`、应用日志；调度时区固定为 `Asia/Shanghai` |
| 抓取失败 | `/sync.html` 的任务错误、令牌及上游连接 |
| 归档失败 | `定时数据抓取清单` 所有权、磁盘空间和挂载 |
| 抓取完成但地图没有新记录 | 正常等待人工审核；打开 `/review.html` 处理疑点并确认入库 |
| 邮件未配置或发送失败 | `deploy/mail.env` 是否存在且 UID 10001 可读、`APP_MAIL_TO`、163 SMTP 连通性；查看审核页邮件状态并重发 |
| 入库失败 | `/sync.html` 的逐条结果、应用日志和数据库健康状态 |
| 旧任务 JSON 无法下载 | `import_batch.file_path`、`sync_run.archive_path` 是否为容器内路径，以及相应文件是否已迁移 |
| 页面仍是旧版 | 先确认 Git 已推送并拉取，再执行 `up --build -d app`，检查浏览器缓存 |

项目配置和接口详情另见 [README.md](README.md) 与 [接口文档.md](backend/接口文档.md)。
