# 进京了 Android

原生 Android 客户端，使用 Kotlin + XML（无 Compose），直接访问现有 Java 后端。用 Android Studio 打开本目录 `Android/`，等待 Gradle 同步后运行 `app`。默认服务地址为 `https://jinjingle.yixiangshe.com`；需要切换开发服务时修改 `app/build.gradle.kts` 的 `API_BASE_URL`，并仅在本地使用 HTTPS 测试地址。

**Android Studio 打开路径：`/Users/qiuyanfeng/Documents/develop/Oracle/jinjing2/Android`。** 请选择该目录中的 `settings.gradle.kts`，不要把 `Android/app` 当作独立项目打开。`gradlew` 和 Gradle `wrapper` 任务都位于 `Android` 根目录。如果遇到 `Task 'wrapper' not found in project ':app'`，先关闭当前项目，再通过「File → Open」打开上述 `Android` 目录，等待 Gradle 同步。旧的 `Android/app` 项目可从 Android Studio 的 Recent Projects 列表中移除；这只移除 IDE 入口，不删除代码。

## 已迁移页面

| 小程序 | Android | 接口/行为 |
| --- | --- | --- |
| 首页地图 | `MainActivity` | 按可视范围加载探头、搜索并缩放到结果、定位、最近新增弹窗 |
| 探头详情/评论 | `CameraActivity` | 详情、图片、评论列表、发表评论 |
| 路线规划 | `RouteActivity`、`PlacePickerActivity` | 起终点/途经点选取与搜索、证件/偏好、双接口并行请求、`PENDING` 轮询、三分钟截止、路线折线与步骤对比 |
| 我的 | `ProfileActivity` | 昵称、会员、开通会员、联系客服、路线、上报、限行说明、帮助/关于 |
| 会员 | `MemberActivity` | 自动登记设备、查看设备标识和会员到期时间、刷新会员状态 |
| 客服与开通会员 | `ContactActivity` | 直接显示内置的微信二维码，长按保存到相册、复制设备标识，供人工核对开通 |
| 上报探头 | `ReportActivity` | 昵称、文字、选填截图及 1MB 校验、审核前上报 |
| H5 容器 | `WebPageActivity` | HTTPS 网页加载，限行说明入口 |

小程序里标为“暂不可用”的进京证、实时导航在 Android 也保留相同提示。Android 会员由管理员在后台手动开通；本版没有注册、动态码登录或 Android 支付入口。需要补充的发布事项见[待确认事项.md](待确认事项.md)。

## 设备账号与会员

首次同意隐私提示后，App 使用 `ANDROID_ID`、包名和本次安装生成的随机盐计算 SHA-256 设备标识；原始 Android ID 不上传。App 另生成随机设备凭据并保存在本机，首次打开时把设备标识、厂商、品牌、型号、Android 版本和 App 版本提交到 `POST /api/v1/android/users/register`，服务端按设备标识只创建一条 `android_user` 记录，并签发 30 天设备会话。以后启动先查询会话状态；仅在会话过期、丢失或旧版升级补传设备资料时再次登记，仍不会重复计入新用户。设备标识可在“会员服务”页复制，供管理员在 `/manage.html?page=android-users` 查询并设置月卡、年卡或指定到期时间。每次开始路线规划前，App 调用 `GET /api/v1/android/users/me` 重新确认会员；两条路线接口及异步结果查询在服务端再次校验有效期和任务归属。地图与探头查询不要求会员。

设备标识与随机凭据都保存在应用私有数据中。卸载重装或清除应用数据会生成新标识，视为新用户；原会员不会自动迁移，需将新的设备标识交给管理员处理。请勿向他人提供本机设备凭据或会话令牌。

个人中心“联系客服”和“开通会员”都打开同一页面，直接显示内置的 `品牌设计/微信二维码.jpg`。长按图片可保存原图到系统相册，再从微信扫一扫的相册中识别添加好友；Android 9 及以下首次保存需授予相册写入权限。用户付款后可复制设备标识发给客服，管理员核对后在 `/manage.html?page=android-users` 设置会员有效期；付款本身不自动授予会员。二维码是微信联系方式，付款金额与方式应先由双方确认。更换二维码图片需要重新构建和发布 Android App；此前后台上传的二维码接口保留，但当前 Android 页面不再使用它。

## 配置地图 Key

高德 Android SDK 需要**Android 平台 Key**，网页 JS Key 和 Web 服务 Key 均不能代替。开发时在个人 `~/.gradle/gradle.properties` 中加入：

```properties
amapAndroidKey=你的Android平台Key
```

当前包名为 `com.jinjingle.and`。高德控制台还需绑定此包名和应用签名 SHA-1；未配置时项目仍能编译，地图/选点搜索无法正常显示。发布签名和调试签名的 SHA-1 通常不同。不要把 Key、签名文件或 `local.properties` 提交到仓库。改包名后，旧包名绑定的高德 Key 不能直接用于新包名，需要在高德控制台更新包名绑定或创建新的 Android Key。

## 构建

```bash
cd Android
./gradlew :app:assembleDebug
```

APK 输出在 `app/build/outputs/apk/debug/`。需本机 Android SDK 34 和 JDK 17。后端需先升级至包含 `V15__android_users.sql` 的版本；Android 设备会员与小程序微信会员相互独立。当前代码尚未部署到 VPS，联调时需将 `API_BASE_URL` 指向已更新的后端。
