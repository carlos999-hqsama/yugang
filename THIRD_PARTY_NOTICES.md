# 第三方组件与分发责任

宿主源码为 GPL-3.0-only；下列组件保留自己的许可证、商标和版权声明。源码树内明确附带的 Termux 原始二进制包，同时附有完整对应源码及 Termux 构建配方；其它第三方二进制不随源码树提供。发布 APK、runtime 归档、Docker 镜像或其它二进制功能时，必须为该次实际构建提供组件清单、许可正文，以及 GPL/LGPL 等许可证要求的对应源码和构建脚本。

| 组件 | 固定版本 / 依据 | 许可与处理 |
| --- | --- | --- |
| PRoot | 5.1.107.98，官方 Termux 包及源码摘要 | GPL-2.0-or-later；保留源码、构建配方、补丁及声明 |
| libandroid-shmem | 0.7 | BSD-3-Clause，不能误归为 GPL |
| talloc | 2.5.0 | 库为 LGPL-3.0-or-later；完整源码中的其他文件各自许可 |
| Debian ARM64 | runtime-lock.json 中固定 layer digest | 各包许可；保留 copyright 与 common-licenses，按实际安装版本匹配源包 |
| Node.js | v24.22.1 | MIT 及其所含组件许可；保留发行包 LICENSE |
| npm | 11.21.1，独立固定并替换 Node 自带版本 | Artistic-2.0 及所含依赖各自许可；保留完整发布包与声明 |
| DeepSeek Harness | @deepseek-ai/dsh 0.2.0-rc.2 | MIT；依赖各自许可，npm package-lock 固定传递依赖 |
| sharp / libvips | 以实际 runtime-packages 清单为准 | Apache-2.0 / LGPL 等；须随二进制提供准确的许可与对应源码材料 |
| LibreOffice Kit JS wrapper | 0.1.3 | MPL-2.0；大型可选 wasm 运行时未打包 |
| AndroidX、Kotlin、OkHttp 等 | Gradle 实际 releaseRuntimeClasspath | 各 Maven POM、source JAR 与发行包中的声明 |
| XZ for Java | 1.10 | 0BSD 等，以该版本源码声明为准 |
| Gradle Wrapper | 9.3.1 | Apache-2.0；Gradle 下载分发包由官方摘要校验 |

原生库取自官方 Termux 包，不从其它 App 的 APK 或未注明版本的仓库复制。为适应 Android nativeLibraryDir，打包脚本将 talloc 的 SONAME/NEEDED 文件名改成 `.so`，不修改库代码；转换脚本随源代码提供。PRoot 使用环境变量指定 loader 路径。

DSH 并非完全未修改：手机 Bot 工作区、工具、权限策略、会话检索及原子文件写入的补丁在 `android/tools/runtime/fixed_workspace.py`、`pack.py` 和 overlay 中，可随宿主源码审阅与重建。

`scripts/release/render-notices.py` 只把可解码、非空且无异常控制字符的 UTF-8 许可正文渲染进离线 HTML；源码归档保持原样分发。`copyright-release.pdf` 这类辅助二进制不会被转换成无效 HTML 文本，而会列入跳过报告。跳过不代表免除责任：若该辅助文件是某个组件的主要法定声明，发布者必须人工寻找并保留可读文本或原样提供该二进制。

Office wrapper 固定在 DSH 声明的兼容范围 `^0.1.1` 内的 0.1.3；对应公开源码为 b19bb73c74ed893b8a5d1716d32df32a113ed31b。0.1.5 的公开源码版本未匹配，故本发布不分发该版本。大型可选 Office 引擎依旧不包含在轻量运行时中。
