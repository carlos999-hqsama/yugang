# Core-only split record

This file is the review boundary for the public source tree. The executable source of truth is
`scripts/release/export-source.py`; run it against a new destination rather than deleting files by
hand. `SOURCE_MANIFEST.json` records the resulting files.

## Removed wholesale

The initial sanitized export contained these companion/business roots. None is selected by the
core exporter, and a re-added path with one of these prefixes fails export:

- `plugins/` — all five business plugin sources.
- `mac/`, `nas/`, `server/`, `deploy/` — private business services and host-specific deployment.

## Android service boundary

Removed business implementations and transport configuration:

- `Bili*`, `News*`, `LocalQuota`, `QuotaParser`
- `Diary*`, `DocumentStore`
- `CodexFiles`, `CodexPlugin*`, `CodexTools`
- `MiniTools`, `MiniToolsPolicy`, `MiniPairing`, `MemoryAgentTools`
- `Config` fields/actions for NAS/Bili/Mini/quota providers
- `Net` target `nas` and `codexFile`
- bridge/plugin methods `news`, `quota`, `bili`, `biliVideo`, `diary`, `documents`,
  `miniTools`, and `codexFile`
- `ReportPreview`, which was reachable only through the removed Codex report transport

Generic host capabilities remain real implementations:

- `HostRuntimeControl` replaces the former Mini socket name while preserving same-UID plugin
  management, plugin capability dispatch, long-running invocation, cancellation, and preview IPC.
  It no longer routes Mini/Codex/Bili/Hindsight commands.
- `PD_HOST_RUNTIME_SOCKET` replaces `PD_MINI_SOCKET` in the runtime/plugin child environment.
- `plugin.media` and `PluginImages` preserve bounded native attachments, image import/decode,
  preview staging, and cleanup.
- `PluginModel`, `PluginFetch`, `PluginFetchStream`, `PluginBackground`, `PluginBackgroundStore`,
  `PluginPreview*`, `PluginVoice`, and `AgentVoiceBuffer` remain active host services.
- `AppControl`, `BotWorkspaceStore`, `BotWorkspaces`, and runtime overlay modules preserve Bot
  routing, local memory, archives, history search, and Bot-to-Bot messaging.

## Runtime overlay boundary

Removed:

- `bili-tools.mjs`, `codex-tools.mjs`, `mini-tools.mjs`, `memory-tools.mjs`
- skills `bili-agent`, `codex-monitor`, `mac-mini`, and the remote Hindsight `memory` skill
- private quota/NAS/Mini model UI helpers from the shared framework

Kept or generalized:

- `app-host-bridge.mjs` is the same-UID native channel used by plugin execution and preview.
- `plugin-tools.mjs`, `plugin-runner.mjs`, `app-tools.mjs`, `app-sessions.mjs`,
  `bot-workspace.mjs`, `bot-messages.mjs`, `bot-archives.mjs`, and `history-tools.mjs` continue
  to provide real host/plugin/Bot tools.
- Default expression comes from the APK seed; explicit shared manual requirements are read from `CONFIG/PERSONA.md`, while validated learned tendencies remain in `CONFIG/PERSONA_LEARNED.json`. A consumed one-time migration record preserves later manual saves. The unconsumed static `agent-voice.mjs` copy and mobile generation dependency were removed. No new TTS/P1/P2 implementation was invented here.

## Core identity

- Public application ID: `com.personaldashboard.core` (debug adds `.debug`).
- Public embedded runtime port: `3090`, so it can coexist with rather than overwrite the private
  full application.
- Version seed: `2.0.0-core.1` / code `20001`.

## Repeatable export

```sh
python3 -m unittest discover -s scripts/release-tests
python3 scripts/release/export-source.py /path/to/new-public-core
```

The destination must not already exist. The exporter admits only explicit core roots, fixed public
templates, text files, individually reviewed binary assets, and the locked native archives with their complete corresponding sources and recipes. It rejects the business roots and known private
runtime/framework files listed above even if they are accidentally copied back.

## 本轮主控整合
已合并通用 TTS（系统中文优先、真实音频文件、宿主首帧计时、停止与 revision 清理）、当前 Bot 语音事件和流式请求的身份/取消修复。业务服务没有恢复；以上均为通用宿主能力。主控已补全公开运行时和固定原生库，正式 Gradle 构建与完整单元检查按本轮交付记录为准，worker 环境限制不是最终构建状态。

导出不会跟随源码根、已选文件父目录或公共模板父目录的符号链接；发现此类路径直接失败，避免把工程外内容当作源码。文本内容仍需独立审阅与密钥扫描。

最终构建复审补充：运行时归档检查拒绝绝对成员名、逃逸路径以及指向 BOT/CONFIG/PLUGINS 等私人目录的软/硬链接；正常的公开 rootfs 绝对链接仍可用。原始 assets 路径不再被合并任务直接消费，失败临时归档和混合 generation 不进入 APK。
