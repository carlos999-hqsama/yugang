# 文本交付文件

## 文本文件预览与下载打开（通用宿主）

- `native.previewFile({mime,name,html})`，或传 `text` / base64 `dataUrl` 中的一项。接受 UTF-8 文本，最多 2 MiB；HTML 在无宿主桥、无远程资源的独立 App 内查看器中打开。Markdown、CSV、JSON 按可读文本展示。返回 `{preview:true}` 表示已排入展示；不打开 Mac，不读取任意文件路径。
- `native.openFile({uri})` 打开本 App 已保存到系统 `Download/鱼缸` 的下载句柄。只接受本 App 所有的 MediaStore Downloads 内容 URI；不接受路径、网络地址或其他 App 文件。返回 `{opened:true,...}`，无查看器则返回 `{opened:false,reason}`。APK 只保留下载结果，需用户从系统下载管理器手动打开。
- 下载连接器应返回实际保存后的 `uri`、`displayPath`、`name`、`mime`、`bytes`。`displayPath` 是给用户看的位置，调用打开时使用 `uri`。本 API 不增加共享存储读取权限。
- 文件来源、账号连接和鉴权由各自连接器负责；公开 Core 不内置私人 Codex 连接配置。
