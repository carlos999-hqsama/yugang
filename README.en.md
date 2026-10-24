# YuGang (鱼缸)

[中文](README.md)

Keep different fish (Bots) in your tank, then add plugins to make it fit your needs.

An open-source Android App built on DeepSeek Agent Harness. Chat with Bots, or let them help build plugins that turn your ideas into tools on your phone.

## Why “YuGang”?

“YuGang” means fish tank. DeepSeek is represented by a whale, so think of your main Bot as the big whale and the other Bots as smaller fish with their own jobs.

Each Bot has a workspace for its role prompt, memory, chat archives and tool settings. They share a common persona and can exchange messages to work together.

Plugins are the things you add to the tank. Add a productivity tool, a reading tool or something else you need. Everyone ends up with a different environment.

## How would I use it?

Say you want a small tool for yourself:

1. Talk through the idea with the main Bot.
2. Ask it to pass the requirements to the plugin Bot for implementation.
3. The plugin Bot checks the page and behavior through an offscreen preview channel, without switching your foreground page.
4. It reports back to the main Bot for another check. Once installed, the plugin is available from the menu.

On first use, the Plugins entry takes you to the plugin Bot. Choose the AI News example, edit the request and send it. Once the Agent actually builds and installs a plugin, that plugin replaces the empty entry.

You do not have to build plugins. Give Bots different roles and let them manage their own material while you chat and work. Bring your own model account: enter a key, validate it and retrieve available models before choosing one. Business plugins are not preinstalled.

## What is included

- Multi-Bot chat and separate workspaces. Context is assembled at runtime: a single-source shared persona is inherited, and identity, roles, memory and tools are added per current configuration instead of one copied fixed prompt.
- Messages between Bots, with history and archives managed internally.
- File, shell and web tools, with progress updates interleaved with execution.
- Independent plugin installation, enable/disable and removal, with page state retained across menu switches.
- Shared page components, monochrome styling, network/streaming requests, models and translation.
- Draft offscreen preview, screenshots, background jobs, notifications, media and native TTS.

## Architecture

There are two parts:

- **Agent Harness**: adapted from DeepSeek Agent Harness for conversations, context, tool calls and Bot collaboration.
- **Plugin foundation**: the Android host supplies the shared framework and general capabilities. Plugins provide their own features, pages and settings.

```text
YuGang App
├─ Conversation and Bots
│  ├─ Shared persona + individual roles
│  └─ Separate workspaces, memory and archives
├─ Local Agent Harness
└─ Android plugin host
   ├─ Shared page framework and general capabilities
   └─ Independently installed plugins
```

Model inference uses your configured service. “Local Harness” does not mean offline model inference on the phone. See [ARCHITECTURE.md](ARCHITECTURE.md) for layers, context assembly, data ownership and cancellation.

Manual persona settings and structured learned tendencies are stored separately and composed for subsequent replies. Plugins own their scene-specific style. Core validates, stores and composes candidates; memory sources and nightly reflection belong to separate adapters.

## What is open source?

The public source includes **conversation and the complete general foundation**. Real implementations for files, networking, model access, media, preview and background work remain available.

Business plugins, private companion services, remote business connections and tokens, private configuration and history are excluded. Accounts, chats, memory, attachments, installed plugins and signing keys are not included in source or APK exports. Sharing the APK does not share your personal environment. Business plugins seen in demos (such as Tibo, Codex or Bilibili-related plugins) are not preinstalled Core and are not shipped here.

See [CORE_SPLIT.md](CORE_SPLIT.md) for the boundary.

## Run and build

Android 10+, ARM64. OS battery policy can affect background work.

Build with JDK 17, the Android SDK, Python and Node.js, preparing the runtime from pinned inputs. Upstream download URLs can disappear, so the public source ships the three original DEBs, matching complete source and the Termux build recipe (about 11.7 MB); builds verify them against lockfiles and SHA-256 digests instead of substituting locally compiled output. See [BUILD.md](BUILD.md) and the [host API contract](android/app/src/main/assets/plugin-developer/host-contract.md).

## Current status

This repository publishes YuGang Core source under GPL-3.0-only. It is an early version; source publication does not imply an official binary release or complete physical-device acceptance.

- A successful build is not proof of a working device. Offline tests and Debug/Release build commands are in [BUILD.md](BUILD.md); first boot, runtime initialization, background behavior, notifications and plugin installation on an ARM64 phone need separate acceptance.
- A previous round verified first boot, runtime initialization and restart on an ARM64 Android phone. This round skips valid-account model discovery at the user's request; physical-device checks for the new plugin guide and the image fix are still pending. Acceptance of the private full App does not replace public Core acceptance.
- The final source passed a clean Release rebuild, 167 unit checks, Release lint and R8 in an independent Linux environment. npm/Gradle caches were initially empty; the final rebuild cleared compiled outputs and reused only dependencies downloaded by that Linux environment. No Mac build output was copied. Future changes still need version-specific checks. The pinned upstream PRoot DEB URL now returns 404; the public source keeps the originally pinned inputs with digest verification. See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for components and corresponding source.

## Security

PRoot, Bot directories and capability declarations are not a hostile-code sandbox. Plugins and Agent tools share the App identity. Install only code you have reviewed and trust. The host encrypts stored credentials and does not return keys through plugin APIs, but this does not make malicious same-identity code safe. See [SECURITY.md](SECURITY.md).

## License and contributions

Host source is **GPL-3.0-only**. Third-party components retain their own licenses. Binary distributions need matching license texts and required corresponding source. See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

Issues and improvements are welcome. Read [CONTRIBUTING.md](CONTRIBUTING.md) first, and keep private data and secrets out of issues.


[Code quality and remaining work](QUALITY.md) · [Dependency review record](DEPENDENCY_REVIEW.json)
