# duomaomao-api-server

把「躲猫猫」APK 里的加密云函数接口，封装成一组本地 REST 接口的 Spring Boot 服务。

* 上游协议细节（加密帧、`ek`、错误面、实测数据）：见 [`../duomaomao-api-doc.md`](../duomaomao-api-doc.md)
* 实验原始数据：见 [`../_work/probe/RESULTS.md`](../_work/probe/RESULTS.md)
* 客户端实现来源：[`../java-cloud-api-client`](../java-cloud-api-client)（4 个源文件已复制进 `com.example.cloudapi`）

> 仅封装**只读**端点。`verifyLoginCode`（动态码换 token）和 `addComment`（发留言）是写接口，
> 会改服务端状态，本项目**故意不实现**。

---

## 1. 环境要求

| 项 | 版本 |
|---|---|
| JDK | 17 |
| Spring Boot | 3.5.16 |
| Maven | 3.9.x（本机未装，用 wrapper dists 里的那份） |

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
MVN=~/.m2/wrapper/dists/apache-maven-3.9.16/56ba1f9f/bin/mvn
```

## 2. 凭证

两样东西缺一不可，都来自 APK，属于敏感材料，**不要提交进仓库**：

| 材料 | 说明 | 注入方式 |
|---|---|---|
| Bearer JWT | CloudBase 项目级 ApiKey（`is_system_admin:true`，exp≈9999 年） | `DUOMAOMAO_BEARER_TOKEN` 或 `DUOMAOMAO_BEARER_TOKEN_FILE` |
| RSA 公钥 | 服务端公钥，用于封装一次性 AES key | 已内置默认值（源码里同样是明文常量），可用 `DUOMAOMAO_PUBLIC_KEY` 覆盖 |

## 3. 启动

### 3.1 IDEA 里直接跑

1. `File → Open` 选 `duomaomao-spring/pom.xml`（或整个目录），等 Maven 导入完；
2. 直接运行 `com.duomaomao.api.DuomaomaoApiApplication`；
3. 打开 <http://localhost:8080/>。

Token 不用配环境变量：找不到 `DUOMAOMAO_BEARER_TOKEN(_FILE)` 时，会按顺序找

```
apikey.txt              （工作目录）
../_work/apikey.txt     （IDEA 默认工作目录 = 模块根目录，你的布局正好命中这个）
_work/apikey.txt
```

都不存在才报错，并打印上面这份清单。想显式指定就在 Run Configuration 里加
`DUOMAOMAO_BEARER_TOKEN_FILE=/abs/path/to/_work/apikey.txt`。

> ⚠️ **macOS 系统代理的坑**：JVM 启动时会把系统代理写进 `http.proxyHost / socksProxyHost`。
> 如果你开着 Clash / Surge 但那个进程已经退出，端口还在、服务没了，所有上游请求会立刻失败：
> `java.net.SocketException: Can't connect to SOCKS proxy: Connection refused`（接口表现为 502「上游网络错误」）。
> 本项目默认**直连**（`duomaomao.use-system-proxy=false`）；确实要走代理再设成 `true`。

### 3.2 命令行

```bash
cd duomaomao-spring
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
MVN=~/.m2/wrapper/dists/apache-maven-3.9.16/56ba1f9f/bin/mvn

# 跑测试（38 个，不联网）
"$MVN" -B test

# 打包
"$MVN" -B -DskipTests package

# 启动（token 走文件，避免进 shell history）
DUOMAOMAO_BEARER_TOKEN_FILE=/abs/path/to/_work/apikey.txt \
PORT=8080 \
"$JAVA_HOME/bin/java" -jar target/duomaomao-api-server-0.1.0-exec.jar
```

缺 token 时启动直接失败，并打印应该设哪个环境变量——不会静默降级。

健康检查：`GET /actuator/health` → `{"status":"UP"}`

---

## 4. 前端页面与全量导出

打开 <http://localhost:8080/> 就是操作页（`src/main/resources/static/index.html`，纯 HTML/JS，零依赖、不用 npm）。

| 按钮 | 行为 |
|---|---|
| 开始抓取（全量 + 详情） | 拉 `labels` 两页去重 → 逐个查 `comment` → 写 `duomaomao-cameras.json` |
| 只抓列表（不抓详情） | 跳过 7300 次详情请求，几秒出文件 |
| 试跑 50 条 | 快速验证链路，不动全量 |
| 下载 JSON | `GET /api/v1/export/file` |

页面还会显示进度条、失败数、耗时、落盘绝对路径，并提供分页表格、关键字搜索（id / 名称 / href）、点行看留言详情。

### 4.1 接口

| 接口 | 说明 |
|---|---|
| `POST /api/v1/export/start?limit=&detail=` | 起后台任务，返回 202；已有任务在跑返回 **409** |
| `GET /api/v1/export/status` | state / done / total / failed / percent / file / sizeBytes |
| `GET /api/v1/export/preview?page=1&size=50&q=` | 分页摘要，**不含**留言正文，只给 `commentCount` |
| `GET /api/v1/export/cameras/{id}` | 单条完整详情（含全部留言） |
| `GET /api/v1/export/file` | 下载落盘文件（`Content-Disposition: attachment`） |

### 4.2 落盘位置

默认 `./duomaomao-cameras.json`，相对**进程工作目录**：

| 跑法 | 实际路径 |
|---|---|
| IDEA | `duomaomao-spring/duomaomao-cameras.json` |
| 命令行 | 你 `cd` 进去的目录 |
| Docker | 必须设 `DUOMAOMAO_EXPORT_FILE=/data/...` 并挂可写卷（compose 里已配） |

改路径：`DUOMAOMAO_EXPORT_FILE=/abs/path/cameras.json`。

写文件用「临时文件 + 原子重命名」，前端和下载永远不会读到半截 JSON。
任务在后台线程池里跑，HTTP 请求立即返回，不占 Tomcat 线程。

### 4.3 实测规模（2026-10-29 真机跑通）

| 项 | 数值 |
|---|---|
| 探头总数 | **7322**（page1 5000 + page2 2326 = 7326，其中 id 6657、6668 两页重复） |
| 全量详情请求 | 7322 次 `comment`（失败重试一次，最多 14644 次） |
| 并发 | `DUOMAOMAO_EXPORT_CONCURRENCY`；实测 8 并发 ≈ 28 req/s |
| 全量耗时 | 8 并发 **4 分 21 秒**（261 s）；6 并发约 6～7 分钟 |
| 落盘大小 | **11 MB**（11,471,318 字节，未压缩、缩进 JSON） |
| 留言总量 | 77353 条，5389 个探头有留言 |
| 详情失败 | **1 / 7322**（0.014%） |

> 加「失败重试一次」之前同参数跑到 56 条失败（0.76%），全是
> `上游网络错误: comment` 的**成批瞬时抖动**（失败 id 是连续区间，如 6063–6070、1958–1965，
> 一次抖动会带走一整批在飞请求）。重试一次后只剩上游自己坏掉的那条。

**已知会 400 的 id**：`7417` —— 上游 `comment` 函数自己抛
`FUNCTIONS_INVOCATION_FAILED`（每次必现，重试也没用），导出文件里该条只有基础字段 + `detailError`。

记录的排列**严格保持上游返回顺序**（大体 id 降序，但上游本身不严格有序：
7322 条里有 580 处相邻 id 是升的，page2 结尾还有 7430→7434 这种升序段）。
需要确定顺序请自己按 `id` 排序。

### 4.4 一个会把数据搞脏的坑（已处理）

上游对**不存在的 id** 不报错，而是返回「全站最新 7 条留言」且 `cameraName=""`。
导出时把这种记录当成「无详情」丢掉，否则文件里会混进完全无关的别人的留言。
默认只保留 `cameraName` 非空的详情。

### 4.5 路线规划测试页（`/route.html`）

`POST /api/v1/routes/plan` 的图形化测试台，从数据导出页右上角「路线规划测试 →」进入。

- **快速填充**：5 个预设，起终点坐标直接取自导出的探头（西单 / 国贸桥 / 北清路 / 中关村 / 大兴机场 / 三里屯 / 望京），
  覆盖「有证可行」「无证不可行」「带途经点」「不走高速」几种情况；
- **表单**：起点 / 终点 / 途经点（最多 6 个）+ `licence`（1 无证、2 六环外、3 六环内）+ `preference`（1 最快、2 最短、3 不走高速）；
- **实时计时**：规划单次 1~43 s，请求中会显示「已等待 x.x s」，不是白屏；
- **结果**：`isSuccessOnPlanRoute`、距离、用时、往返耗时、段数/点数；
  SVG 折线图（按纬度做经度压缩，起终点/途经点标注，不需要任何地图瓦片）；
  导航步骤表格（指令 / 道路 / 距离 / 时间 / 每段路况），以及路况统计（畅通 392、拥堵 11…）；
  原始 JSON 和请求体都能展开看；
- **复制等效 curl**：一键把当前表单变成可直接粘到终端的命令；
- **连点压力测试**：并发发 N 次（每次带不同 openid，所以缓存不命中、真的打上游），
  能直接看到 200 / 429 的分布，用来验证 `max-concurrent-route-plans` 闸门。

> 一个在页面里踩到的上游行为：`isSuccessOnPlanRoute=false` 时**几何仍然完整返回**，
> 但顶层 `distance` 被写成 `0m`、`useTime` 留空（例：昌平北清路 → 国贸桥、licence=1，
> 20 段导航、604 个坐标点，但顶层距离是 0m）。页面在这种情况下会用 `navigationSteps`
> 汇总出 31.7 km / 71 分，并标注「按步骤汇总」，别误以为上游返回了空路线。

---

## 5. 接口清单

所有接口默认返回 `application/json`（错误返回 `application/problem+json`）。

| 本服务 | 上游 | 说明 |
|---|---|---|
| `GET /api/v1/update` | `/androidCheckUpdate202609` | 版本信息 + 服务端下发的端点路径 |
| `GET /api/v1/paths` | 同上（取 `paths`） | 只回路径表，方便排查「上游改路由」 |
| `GET /api/v1/labels?page=1` | `/qwvsxdvglkj` | 全量探头；`page=1` 5000 条，`page=2` 2326 条 |
| `GET /api/v1/cameras/{id}` | 同上（扫全量） | 按 id 查单个探头，命中 labels 缓存 |
| `GET /api/v1/gaode-keys` | `/getgaodeSDK4Android202609` | 7 个 SDK key + 2 个 Web 服务 key |
| `GET /api/v1/users?openid=` | `/getUserInfo202609` | 用户信息；不传 → `temp_undefined` 临时用户 |
| `GET /api/v1/comments/{id}` | `/getCommentNew202609` | 探头留言（`dowhat=query`） |
| `POST /api/v1/tokens/validate` | `/validateToken202609` | 校验**用户** token（不是本服务的鉴权） |
| `POST /api/v1/routes/plan` | `/planRoute202503an202609` | 路线规划（消耗高德额度） |
| `GET /api/v1/routes/result?openid=` | `/getPlanResultios202609` | 该 openid 最后一次规划结果 |

## 6. curl 示例

```bash
BASE=http://127.0.0.1:8080

# 路径表 / 版本
curl -s $BASE/api/v1/paths
curl -s $BASE/api/v1/update

# 探头
curl -s "$BASE/api/v1/labels?page=2" | head -c 300
curl -s "$BASE/api/v1/cameras/752"

# 用户（VIP 假象：服务端对 test_android_vip_user 返回 isVIP=false）
curl -s "$BASE/api/v1/users?openid=test_android_vip_user"
curl -s "$BASE/api/v1/users"            # → userId=temp_undefined

# 留言
curl -s "$BASE/api/v1/comments/752"

# 高德 key
curl -s $BASE/api/v1/gaode-keys

# 校验用户 token（HTTP 200 也可能 valid=false）
curl -s -X POST $BASE/api/v1/tokens/validate \
  -H 'Content-Type: application/json' \
  -d '{"token":"TEST-VIP-001"}'

# 路线规划（licence=3 六环内进京证 + 短途，快且易命中缓存）
curl -s -X POST $BASE/api/v1/routes/plan \
  -H 'Content-Type: application/json' \
  -d '{
    "openid": "demo-1",
    "licence": 3,
    "preference": 1,
    "startPoint": {"name":"北京南站","latitude":39.865,"longitude":116.378},
    "endPoint":   {"name":"天坛公园","latitude":39.8822,"longitude":116.4066}
  }'

# 取该 openid 最后一次结果
curl -s "$BASE/api/v1/routes/result?openid=demo-1"
```

## 7. 参数语义（实测得出，不是猜的）

### `POST /api/v1/routes/plan`

```json
{
  "openid": "可选，服务端不校验，也不参与选路",
  "licence": 1,
  "preference": 1,
  "startPoint": {"name": "…", "address": "…", "latitude": 39.865, "longitude": 116.378},
  "endPoint":   {"name": "…", "address": "…", "latitude": 40.075, "longitude": 116.610},
  "waypoints":  []
}
```

| 字段 | 取值 | 默认 | 实测行为 |
|---|---|---|---|
| `licence` | 1 = 无证件；2 = 六环外进京证；3 = 六环内进京证 | 1 | 缺失时上游按 **3** 处理（不是 1） |
| `preference` | 1 = 最快；2 = 最短；3 = 不走高速 | 1 | 缺失时上游返回字面量 `null` |
| `waypoints` | 最多 6 个（UI 只允许 5 个） | `[]` | 6 个也被接受，返回多段二维坐标 |
| `openid` | 任意字符串 | 匿名 | 随机串与真实 VIP 账号回参**字节级相同**，不校验、不影响选路 |

`isSuccessOnPlanRoute` 的语义是「**当前进京证条件下是否存在可行路线**」，与账号是否 VIP 无关：

* `licence=1` + 终点在六环内 → `false`（这就是 UI 上「第1段规划结果：规划失败」的真因）
* `licence=3` → 同一条路线 `true`

### 坐标约定

* 上游 `position` 是 `[经度, 纬度]`（GCJ-02），**不是** `[lat, lng]`。
* 本服务对外统一用 `latitude` / `longitude` 具名字段，避免踩这个坑。

## 8. 响应归一化

上游有几个形状不稳定的地方，本项目做了显式处理：

| 情况 | 上游 | 本服务 |
|---|---|---|
| `routePlanResponse` 单段 | 一维数组 `[{lng,lat}, …]` | 统一成**二维** `[[…]]`，永不为 `null` |
| `routePlanResponse` 多段（带途经点） | 二维数组 | 原样 |
| `navigationSteps[].action` | 字符串，个别段是 `[]` | `JsonNode` **原样透出** |
| `navigationSteps[].assistant_action` | 字符串 或 `[]` | `JsonNode` 原样透出 |
| `navigationSteps[].toll_road` | 数组，个别段是字符串 | `JsonNode` 原样透出 |
| `labels` 的 `page=2` | `showPoints: null` | 原样（只有 `page=1` 有 10 条最新点位） |
| `comments` 的 `id` 不存在 | 不报错，返回全站最新 7 条，`cameraName=""`、`position=[]` | 原样，**调用方需自己判断 id 有效性** |

> 这三个 `JsonNode` 字段是踩坑踩出来的：真实响应里 `assistant_action` 和 `action` 会出现
> `[]`，`toll_road` 会出现字符串，强类型 `List<Object>` / `String` 都会让整条响应解码失败。
> 同时给上游解码器开了 `ACCEPT_SINGLE_VALUE_AS_ARRAY` 作为兜底（见 `UpstreamJson`），
> 但只作用于上游解码，不影响本服务自己的序列化。

坐标点保持**字符串**原样（`"116.377665"`），不转 `double`，避免精度/格式被改写。

## 9. 缓存与限流

| 数据 | TTL | 说明 |
|---|---|---|
| `update` / `paths` | 10 min | 路径表变化很慢 |
| `labels` | 10 min | `refresh=true` 可跳过本地缓存 |
| `gaode` | 1 h | key 基本不变 |
| `comments` | 2 min | 留言有新增可能 |
| `routes/plan` | 30 min（失败 5 min） | key = openid + licence + preference + 所有坐标 |

* 缓存是进程内 `ConcurrentHashMap` + 纳秒过期，不引入 Caffeine。
* `routes/plan` 有并发闸门：同时最多 `max-concurrent-route-plans`（默认 3）个在飞，
  排队超过 `route-queue-timeout`（默认 5 s）直接返回 **429 + `Retry-After: 5`**。
  这是防止把高德额度打爆，不是上游的限制。
* `GET /api/v1/cameras/{id}` 会先扫 `page=1`，没有再看 `page=2`，结果复用 labels 缓存。

## 10. 错误映射

| 场景 | HTTP | `title` |
|---|---|---|
| 参数不合法（缺字段、越界、`page<1`、途经点 > 6） | 400 | `Validation failed` / `Invalid request` |
| 探头 id 不存在 | 404 | `Camera not found` |
| 还没有导出文件就下载 / 读详情 | 404 | `Export file not found` |
| 已有导出任务在跑又点了一次 | 409 | `Export already running`（带 `Retry-After: 10`） |
| 上游返回非 2xx（如 400 `FUNCTIONS_INVOCATION_FAILED`） | 502 | `Upstream rejected the request`（附 `upstreamStatus` / `upstreamBody` 前 2000 字符） |
| 上游网络/解密/反序列化失败 | 502 | `Upstream call failed` |
| 规划并发排队超时 | 429 | `Too many route planning requests` |

错误体是 RFC 7807 `ProblemDetail`。**不会**把 Bearer token 或完整上游响应透出。

## 11. 测试

```bash
"$MVN" -B test
```

38 个测试，全部离线：

| 测试 | 覆盖 |
|---|---|
| `UpstreamJsonTest` | 真实响应 fixture 解码、`action`/`toll_road` 不规则形状、宽容模式不污染共享 mapper |
| `ProxySelectorsTest` | 默认直连（绕开 macOS 系统代理） |
| `RouteSegmentsDeserializerTest` | 单段/多段/`null`/`[]` 的二维归一化 |
| `ExportServiceTest` | 两页去重、顺序保持、单条失败不中断、丢弃「假详情」、limit/无详情模式、并发互斥、分页搜索、进程重启后从磁盘读 |
| `TtlCacheTest` | 过期、拒收空值、容量上限 |
| `ApiControllerTest` | 字段名契约、400/404/429/502 映射、路由体校验 |
| `ExportControllerTest` | 202/409/404、limit/detail 透传、分页摘要不含正文 |
| `DuomaomaoApiApplicationTests` | 上下文启动（用假 token，不联网） |

`src/test/resources/route-plan-real-shape.json` 是从真实 `planRoute` 响应里裁出来的样本
（4 个导航段 + 3 段路线，含所有不规则字段），用来防回归。

## 12. 风险

* **凭证即权限**：Bearer 是项目级管理 JWT，泄露等于把整个云函数开放出去。放环境变量/文件，别进 git。
* **额度**：`planRoute` 真实计费/计次，失败路径也会跑满约 20 s。批量实验请换坐标或依赖缓存。
* **上游会变**：路径可能被 `/androidCheckUpdate202609` 改掉。客户端库的策略是「先用内置常量，
  遇到 404 就拉一次路径表再重试一次」，所以路由变更不会导致硬失败，但会多一次往返。
  长期运行请留意 502 的 `upstreamBody`。
* **不是生产网关**：没有鉴权、没有多租户。定位是「本地调用别人的云函数」的适配层。
  `/api/v1/export/*` 同样无鉴权 —— 别把它暴露到公网，否则谁都能触发 7000+ 次上游请求、下载全部数据。
* **导出的数据是别人的**：探头名称/坐标/留言都是上游内容，`duomaomao-cameras.json` 别提交到公开仓库
  （`.gitignore` 已排除）。
* **重复导出**：全量一次约 7300 次请求。想刷新数据就再点一次，文件会被整体覆盖（不是增量合并）。
