# 盘库助手 PanScan

条码扫描盘库工具：扫设备 SN → 按栈板归集 → 导出 Excel。

## 两种形态，同一套代码

| 形态 | 位置 | 说明 |
|---|---|---|
| 网页版（PWA） | `app/` | 手机浏览器打开 https 链接，可加到桌面，离线可用 |
| 安卓 APK | `盘库助手_v1.7.apk` | 原生相机扫码 + WebView 界面，装完即用 |

APK 里的 `android/assets/` 是 `app/` 的副本，由 `makedist.sh` 自动同步，不要手改。

## APK 的扫码实现（重要）

v1.0 曾用 WebView 的 `getUserMedia` 扫码，在 Android 上不可靠（权限时序 + WebView 内核差异），
v1.1 起改为 **原生扫码**：

- `android/src/.../ScanOverlay.java`：`android.hardware.Camera` 取景 + `com.google.zxing:core` 纯 Java 解码，
  覆盖在 WebView 之上，扫到的结果通过 `AndroidBridge` 注入网页（`window.__nativeScan`）。
- 依赖 `android/libs/core.jar`（ZXing core 3.5.3，Java 8 字节码，无需 Gradle）。
- 网页里只有浏览器环境才走 `getUserMedia` 路线；APK 内 `nativeScan` 为真，自动切到原生。
- 相机在 Activity `onPause` 时立即释放，否则会占住摄像头导致别的 App（包括浏览器网页版）打不开。

### 解码方向（易错点）

一维码只有在画面里「竖着排」时才解得出来，所以每帧要把 Y 平面旋转到正立。旋转时**输出宽高互换，
行跨度必须用 h 而不是 w**，否则 1280×720 这种非正方形帧会整幅错位，表现就是「相机正常但永远扫不出码」。
`android/test/DecodeSelfTest.java` 可离线验证这段逻辑：

```bash
cd android/test && java -cp ../libs/core.jar DecodeSelfTest.java
```

期望：正立 / 顺 90° / 逆 90° / 大帧都能解出 SN，横放那张失败，旋转可逆性差异为 0 像素。

## 安装

手机传 APK → 点击安装 → 允许「安装未知应用」→ 首次打开允许摄像头。

- 支持 Android 7.0（API 24）及以上
- 数据存在 App 私有目录，卸载即清空，收工前先导出
- 网页版与 APK 的数据互不相通；网页版可在「设置」导出 CSV，再在 APK 里导入

## 自动更新（v1.7 起）

App 不经过应用商店，自己搬包：读仓库根目录的 `update.json` → 比对 `versionCode` →
下载 Release 里的 APK → 按清单里的 `sha256` 校验 → 交给系统安装器。

- 清单地址写在 `android/src/com/pankscan/app/AppConfig.java` 的 `OWNER` / `REPO` / `BRANCH`，
  **只改这一处**，`publish.sh` 用 grep 从同一个文件读，脚本和 App 不会对不上。
- 安装包地址只认 HTTPS；版本号不大于本机时不提示，写错清单也不会降级。
- Android 8 起首次安装需允许「安装未知应用」，App 会先跳去设置页，不硬装。

发布一个版本：

```bash
gh auth login          # 只需一次；令牌过期后要重做
bash publish.sh        # 打包 → 写 update.json → 建 Release → 推送
bash publish.sh --dry  # 只打包并生成本地清单，不碰 GitHub
```

更新说明写在 `更新说明.txt`，发布会原样进 `update.json` 的 `notes`，App 里点「检查更新」能看到。

> GitHub 在国内访问可能偏慢。`检查更新` 失败会明确提示「连不上 GitHub」，
> 也可以照旧直接发 APK 手动安装，两种方式并存。

## 重新打包

```bash
bash makedist.sh
```

流程：同步 `app/` → `android/assets` → 复制工程到 `C:/Users/yuguo/pankscan-build/proj`
→ aapt2/javac/d8/zipalign/apksigner → 产物拷回本目录。
构建工具链在 `C:/Users/yuguo/pankscan-build/tc`（JDK 17 + build-tools 34 + platform 34）。

> 为什么不在本项目目录直接构建：aapt2 无法处理含中文的路径（`盘库助手`），会报
> 找不到 android.jar。所以构建固定在纯英文路径下进行。

## 签名密钥（重要）

`android/pankscan.jks`（口令 `pankscan`，别名 `pankscan`）。

**不要删这个文件。** 后续版本必须用同一密钥签名，否则手机拒绝覆盖安装，只能卸载重装（数据会丢）。

## 目录

```
app/            网页版源码（index.html / styles.css / app.js / data/parts.js）
                界面内置「操作说明」（顶栏 ? 与设置页）与「关于」模块
android/        安卓工程（Manifest、MainActivity、ScanOverlay、ScanProfile、
                UpdateManager、ImageOps、TonePlayer）
makedist.sh     一键打包（自动归档到 releases/）
publish.sh      一键发布到 GitHub（生成 update.json + Release）
update.json     自动更新的版本清单（由 publish.sh 生成，不要手改）
更新说明.txt     下一个版本的更新内容，发布会写进 update.json
盘库助手_v1.7.apk          最新版，根目录只放这一份
releases/       历史版本归档（每版都保留，含版本清单.md）
盘库助手软件使用说明书.docx
```

## 版本保留规则

**每发布一版都保留安装包，不覆盖、不删除。** `bash makedist.sh` 会把产物同时写到根目录和
`releases/盘库助手_vX.Y.apk`，版本号从 `AndroidManifest.xml` 的 `versionName` 读取，只改一处。
`releases/版本清单.md` 记录每版的时间、大小、SHA-256、可用状态与变更内容。
