# Yugang core

This repository contains the chat harness and the reusable Android plugin host.

- Keep generic capabilities and their real implementations: Bots, messaging, persona/context, local files, network/streams, model access, media/TTS, preview, background tasks and notifications.
- Business plugins, companion servers, service-specific connections and credentials do not belong in this repository. Do not add placeholder implementations in place of working host capabilities.
- Preserve the established UI and monochrome theme. Changes need a concrete requirement and relevant regression checks.
- Read ARCHITECTURE.md and BUILD.md for the current contracts and verification commands. Keep plugin developer references in sync with the host API; disclose instructions only when needed.
- Never add account data, API keys, sessions, installed plugins, signing keys, build caches or runtime state to source control or source exports.
- Source exports use scripts/release/export-source.py. Build APKs from the same exported source and pinned runtime inputs. Do not publish or push without explicit authorization.
- Report local checks separately from device acceptance. A successful build is not proof of a working device interaction.
