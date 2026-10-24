# Public source boundary

YuGang Core contains the Android conversation system and complete general plugin host. The exporter is `scripts/release/export-source.py`; it creates a new directory and records the files in `SOURCE_MANIFEST.json`.

## Included

- Android UI, local Agent Harness, Bot workspaces and messaging.
- Shared plugin framework, installation, lifecycle and permissions contracts.
- File, network/streaming, model, media, TTS, preview, background and notification capabilities.
- Default templates, tests, build tools, pinned runtime inputs, licenses and corresponding source.
- Reviewed documentation images explicitly admitted by the exporter.

## Excluded

Business plugins and companion services are separate projects. Accounts, credentials, signing keys, conversations, memory, installed plugins, runtime data, caches and local development records do not belong in source exports.

## Repeatable export

```sh
python3 -m unittest discover -s scripts/release-tests
python3 scripts/release/export-source.py /path/to/new-public-core
```

The destination must be new. The exporter selects fixed public templates, explicit source roots, permitted text types and individually admitted binary assets. Locked native inputs are verified against their digests and accompanied by corresponding source and build recipes.

The exporter rejects symbolic links in source and template paths. Content still needs review; an allowed filename does not prove that a file contains no secrets. Screenshots must be checked for accounts, conversations and other personal details before being admitted.

## Application identity

- Application ID: `com.personaldashboard.core` (debug adds `.debug`).
- Embedded local runtime port: `3090`.
- Current version: see `android/app/build.gradle.kts`.
