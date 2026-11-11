# TN Assistant repository rules

- This repository contains the independent Kotlin Android, Go backend, React admin and deployment source for TN Assistant.
- Production application ID/namespace is `vn.tn.assistant`; Preview is `vn.tn.assistant.preview`. Do not reuse the original application's ID.
- Rebuild both Basic and Premium behavior and UI. Passing fixtures or a local entitlement is not whole-feature acceptance. Real payment processing may remain omitted.
- When TN adds behavior without an original-app counterpart, reuse the closest original UI component and styling while preserving the new behavior, permission checks and consent.
- TN's default voice is female Southern Vietnamese (`agent_voice=south`). Locally generated prompts use Thùy Dung unless the user explicitly chose another voice.
- Validate rebuilt Android behavior only on Android SDK emulators. Do not use, start or install into LDPlayer.
- Original-app reference material remains outside this repository under the existing Kiki workspace. Do not copy, decompile or commit it here.
- Credentials, release keys, provider configuration, personal data and mutable databases must remain outside Git. Release signing is supplied through `tnSigningProperties` or `TN_ANDROID_SIGNING_PROPERTIES`.
- Never commit `.runtime`, reports, audits, backups, APKs, build outputs, logs, temporary files or generated dependency directories.
- Keep source changes accompanied by proportional unit, lint, build and device acceptance. Reports produced during a run are temporary and must be removed before commit.
