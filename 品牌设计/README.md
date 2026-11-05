# 进京了么品牌资源

以「京」的字形为核心，将上方点画做成古代印章的菱形印记，字中金色圆点代表地图定位。主色为朱红 `#BD342F`，字形为暖白 `#FFF8EE`，强调色为金 `#F4C96C`。四端共用同一个图形，避免用户在网页、小程序和手机桌面看到不同标识。

| 平台 | 文件 | 用途 |
| --- | --- | --- |
| 网页 | `网页/logo-mark.svg`、`网页/logo-horizontal.svg`、`网页/favicon.svg`、`网页/favicon-32.png`、`网页/apple-touch-icon.png` | 页眉、浏览器标签和添加到主屏幕 |
| 微信小程序 | `微信小程序/小程序头像-1024.png`、`微信小程序/小程序头像-144.png` | 在微信公众平台上传小程序头像；项目名称已设为「进京了么」 |
| Android | `Android/play-store-512.png`、`Android/res/` | 应用商店图标、各密度桌面图标及自适应图标；集成时将 `res` 文件复制到 App 模块 |
| iOS | `iOS/AppIcon.appiconset/` | 将资产目录复制到 Xcode 的 `Assets.xcassets`，设置为 App Icon |

`logo-mark.svg` 是原始矢量稿，`logo-horizontal.svg` 是横版文字标识。四端图标以矢量稿导出；保留源文件以便后续调整。`预览.png` 展示实际图标和横版标志。

网页运行时使用的图标副本位于 `backend/src/main/resources/static/brand/`。若修改矢量稿，请重新运行 `generate.py` 同步所有尺寸与网页副本：

```bash
# macOS 如未安装 Cairo，先执行：brew install cairo
python3 -m pip install cairosvg pillow
python3 品牌设计/generate.py
```

小程序头像需在微信公众平台上传，无法仅靠 `project.config.json` 自动生效。当前小程序项目使用体验版 `touristappid`；正式发布时还需在微信开发者工具中换成自己的 AppID。
