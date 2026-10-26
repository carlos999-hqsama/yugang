# Core-only 构建与测试

以下命令假设从 `scripts/release/export-source.py` 导出的新目录开始，不复制开发者缓存、签名密钥、`.env`、数据库、已安装插件或用户目录。宿主构建需要 Python 3.11+、Node.js 22+ / npm 10+、JDK 17、Android SDK Platform 37、AGP 对应 Build Tools、首次运行时下载和网络、约 12 GB 可用空间；构建宿主本身不需要 ARM64。`compileSdk=37` 对应 SDK Manager 包名 `platforms;android-37.0`。

## 1. 无网络发布工具回归

```sh
python3 -m unittest discover -s scripts/release-tests
```

该组测试使用 Python 标准库和仓库 fixture，验证 core-only 导出 allowlist、显式二进制准入与许可渲染器行为。

## 2. Android 宿主与固定运行时

```sh
python3 scripts/release/fetch-native.py --cache .rtbuild/native
bash android/tools/runtime/build-runtime.sh
cd android
./gradlew --no-daemon testDebugUnitTest lintRelease assembleRelease
cd ..
python3 scripts/check-app-distribution.py android/app/build/outputs/apk/release/app-release-unsigned.apk
```

运行时由锁定的 Debian ARM64 层、Node.js、独立 npm 包和 DSH package-lock 构建。下载摘要由锁文件校验；DSH 使用 `npm ci --ignore-scripts`。工作区、权限、历史检索、Bot 通信和文件发布补丁位于 `fixed_workspace.py`、`pack.py` 与 overlay。Release/Debug 资产合并会校验 runtime manifest 记录的源输入集合与摘要（包括下载脚本和构建入口）；源码或 overlay 变更后必须重新打包，并更新实际第三方声明。未生成 `android/runtime-assets/runtime/runtime.tar.xz` 与固定 `jniLibs` 时，不能得到可安装 APK。

固定的 Termux 原生依赖及其完整对应源码、构建配方随 `scripts/release/native-inputs/` 提供，每份都以 `native-lock.json` 的 SHA-256 校验。它们是上游原始输入，不是开发者生成的 `.so` 或运行时缓存；无需依赖会移除旧版本的滚动软件源。Node、Debian 和 npm 依赖仍由网络按锁文件下载。

图片处理依赖虽然由上游 npm 声明为 optional，在本 ARM64 宿主中仍是实际必需项。下载明确包含 optional；下载后和打包前都按锁文件核对 sharp、ARM64 addon 与 libvips，并检查原生库为 ARM64 ELF。缺包或错误架构直接失败，不能把 npm 的成功退出当作目标依赖齐全。Mac 文件元数据不会进入运行时或源输入清单。

Gradle 使用 `android/gradle/verification-metadata.xml` 校验构建插件与依赖制品。升级依赖须审查来源、更新校验文件和许可清单，不能为绕过失败关闭校验。


构建消费运行时的方式：`pack.py` 的临时归档与进程锁位于 `android/build/runtime-pack`，不进入资产根。完整归档与 manifest 成对发布；Gradle 的 `prepareRuntimeAssets` 从已打开文件复制并核对独立副本，只把 `runtime.json` 和 `runtime.tar.xz` 放入 `app/build/generated/runtime-assets/runtime`。资产合并消费这个稳定快照，源文件被并发替换时不会重读另一份归档；捕获到混合版本则明确失败，保留上一份有效快照。Debug 与 Release 均依赖此任务；源输入变化仍必须重打包。源输入、许可和依赖守卫分别挂在无输出的 `preDebugBuild` / `preReleaseBuild`，不依赖增量资产合并是否执行；即使合并输出未变，修改 producer 或 overlay 后不重打包也会明确失败。

## 3. 现有离线测试

```sh
node scripts/page-framework-tests/run.mjs
node --experimental-vm-modules --disable-warning=ExperimentalWarning scripts/interaction-tests/run.mjs
node scripts/chat-scroll-tests/chat-scroll.test.mjs
node scripts/skill-runtime-tests/skill-disclosure.test.mjs
node scripts/test-model-editor.mjs
python3 -m unittest discover -s scripts/release-tests
```

滚动回归包含原生滚动事件延迟到达、顶部负偏移回弹、短内容回弹、内容收缩后恢复，以及加载更早历史后保持消息身份和像素位置。`chat-scroll-tests` 验证共用控制器，`interaction-tests` 验证实际对话渲染与历史前插；它们不替代实体设备上的 WebView 手势验收。

生成 `.rtbuild/dl/dsh` 后运行依赖真实 DSH 编译器的检查：

```sh
PD_DSH_VENDOR_ROOT=.rtbuild/dl/dsh python3 scripts/workspace-tests/check-policy.py
PD_DSH_VENDOR_ROOT=.rtbuild/dl/dsh node scripts/check-runtime-tool-schemas.mjs
PD_DSH_VENDOR_ROOT=.rtbuild/dl/dsh node --experimental-loader ./scripts/plugin-tests/vendor-loader.mjs --test scripts/plugin-tests/*.test.mjs
PD_DSH_VENDOR_ROOT=.rtbuild/dl/dsh node --test scripts/skill-runtime-tests/source-fidelity.test.mjs scripts/workspace-tests/mcp-redirect.test.mjs scripts/workspace-tests/fflate-zip64.test.mjs
```

## 4. 插件包与设备验收

业务插件不随仓库提供。第三方插件可放在任意源码目录，每个插件包含 `web/plugin.json`，可选根级 `main.mjs` 作为原生动作：

```sh
node scripts/build-page-plugins.mjs <插件源码根目录> --out <输出目录>
```

构建器校验 API 版本、公共框架引用、资源路径、导入闭包和 2 MiB 上限，输出独立 JSON 包；不会把插件写入 APK assets。

专用 ARM64 模拟器可运行 `PluginDeviceTest` 合成 fixture，覆盖页面缓存、媒体方向、预览生命周期、后台计划、通知、移除恢复、许可窗口和插件契约。构建并安装 Debug 与 androidTest APK 后：

```sh
adb -s <emulator> shell am instrument -w -e scenario previewLifecycle com.personaldashboard.core.debug.test/com.personaldashboard.app.PluginDeviceTest
```

真实设备仍须验收 ARM64 运行时、后台驻留、系统通知、WebView 行为、宿主 TTS 和用户自己的插件安装。androidTest 与合成 fixture 默认只用于专用模拟器；实体手机上的临时测试须获得机主授权，使用独立安装身份，保留已有数据并在验收后清理。

## 5. 发布材料

- 使用 `python3 scripts/release/export-source.py <新目录>` 生成可重复 core-only 源码树；目标目录必须不存在，防止覆盖旧发布。
- 使用 `scripts/release/generate-sbom.py` 基于实际许可证目录与 Debian 对应源码生成 SBOM。
- 使用 `scripts/release/render-notices.py` 与 `complete-license-metadata.py` 处理实际 runtime/Android 依赖声明。
- 发布 APK 必须与公开源码、固定 runtime 输入和第三方源码/许可材料一一对应。
- 第三方对应源码归档生成后，执行 `python3 scripts/release/normalize-source-archive.py <原始.tar.gz> <新的公开.tar.gz>`。发布规范化后的文件，逐项复核 `SOURCE_INDEX.json` 中的内容摘要；不要直接分发本机 `tar.add` 生成、带用户名或 PAX 私人路径的归档。规范化只清理外层归档元数据，保留上游源码与许可证原文。

本项目固定运行时输入版本和下载摘要，不承诺两次构建产生字节完全相同的 APK/runtime；发布记录保存实际产物摘要。


## 6. 发布签名与恢复

发布签名密钥和口令文件保存在工程之外，以私有权限保护，不进入源码、公开产物、日志或普通备份。后续升级沿用同一发布身份；遗失私钥无法直接覆盖原包。调试签名不替代发布签名。

构建未签名 Release 后，可使用 Android SDK Build Tools 签名；下列变量只引用发布者自己的私有文件，不包含实际秘密：

```sh
zipalign -c -P 16 4 android/app/build/outputs/apk/release/app-release-unsigned.apk
apksigner sign --ks "$YUGANG_KEYSTORE" --ks-key-alias "$YUGANG_KEY_ALIAS" \
  --ks-pass "file:$YUGANG_STORE_PASSWORD_FILE" \
  --out yugang-core-release.apk android/app/build/outputs/apk/release/app-release-unsigned.apk
apksigner verify --verbose --print-certs yugang-core-release.apk
```

保存签名 APK、源码归档、runtime、许可材料与 SBOM 的摘要及公开证书指纹。比较签名前后 ZIP 的原成员内容；只允许增加签名元数据，不更换代码或资源。设备安装后再次核对 APK 摘要、包名、版本和非 Debuggable 状态。

首次安装测试使用独立测试环境，检查冷启动、运行时初始化、空白配置、默认 Bot、插件引导和重启。更新安装须使用相同包名和签名，保留已有数据；回退时使用兼容的版本码，不通过清除用户数据绕过兼容问题。

## 文本文件回归

Android JVM 定向运行 FileTextContentTest、PluginMimeTest、PluginFetchResponseTest（最后一组需要本机环回端口）。设备验收需合成 HTML/MD/CSV/JSON 与超 2 MiB 文件，核对 App 内查看器、超限下载提示及本 App 下载 URI 的系统查看器打开。JVM 通过不替代手机验收。文本预览只在内存，下载保留在系统 Download/鱼缸；回滚代码不删除已有下载。

浏览器/会话设备验收尚需在专用空白 ARM64 模拟器或授权设备逐项检查：WebView MULTI_PROFILE、独立 profile、Keystore 重载、更新保留、卸载与晚到响应、下载、无宿主桥；通知还需核对真实系统允许/拒绝及预览 status/request 边界。公开工程现有 browserBoundary、notificationDenied/Granted 等旧场景不覆盖上述完整新流程；没有 browserSeed/browserVerify/apiSessionSeed/apiSessionVerify/notificationFoundation 场景，不应作为可执行命令。使用无凭据合成服务，临时服务和模拟器在验收后清理；真实用户登录不以合成结果冒充。页面框架检查入口已纳入预览结构化拒绝的 JavaScript 回归。

## 7. 对话附件回归（2026-10-26）

```sh
PD_DSH_VENDOR_ROOT="$(pwd)/.rtbuild/dl/dsh" node --test scripts/workspace-tests/chat-attachments.test.mjs
```

该组直接执行生产附件 handler，覆盖精确历史引用授权、冷历史读取、Unicode 名称、完整流和 prefix/suffix/open Range、越界 416、中止、背压及生命周期释放。Android 的 BotAttachmentTransferTest 用本机 HTTP 测试服务核对真实上传字节、认证仅在原生、receipt 复用/失效、响应上限与取消；ChatContentPolicyTest 验证 Bot 工作区、特殊文件名、附件地址和范围读取。界面交互回归覆盖 6 图独立预览/移除、图片回显交接、文件发送结果未知的重试、上传取消和暂存凭据失效。

设备仍须实测系统选择器（含云文件）、大视频上传取消/重试、后台切换、真实 WebView MULTI_PROFILE、视频编码/拖动播放、PDF/Office 系统查看器、Download/鱼缸保存与打开。浏览器合成数据截图和 JVM/Node 检查不替代这些设备业务验收。

Core6 补充验证：`node --test scripts/page-framework-tests/core-bridge.test.mjs` 覆盖真实宿主桥的交互期限、上传取消和迟到回执。`ChatContentPolicyTest` 覆盖 HTML/SVG 隔离、上游 CSP 保留及媒体 Range。浏览器夹具验证交互脚本/CDN 可运行且同源文件读取与存储被阻止；此结果不代替 Android WebView 真机验收。资源紧张时构建使用 `--max-workers=1`，避免与其他重构建并行。
