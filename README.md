<div align="center">
  <img src="docs/icon.png" alt="RikkaPlus icon" width="100" />
  <h1>RikkaPlus · 1.0</h1>
  <p>A rebuild of RikkaPlus on the latest RikkaHub foundation.</p>

English | [简体中文](README_ZH_CN.md) | [繁體中文](README_ZH_TW.md)
</div>

## About this branch

The `1.0` branch is the new rebuilding branch of [RikkaPlus](https://github.com/Hoixg/rikkaplus), maintained by Hoixg.

Starting from upstream [RikkaHub](https://github.com/rikkahub/rikkahub), this branch will review and rebuild the custom features developed on `custom`. The aim is to address accumulated technical debt, clarify responsibilities between features, and make future development and upstream synchronization easier.

## Branches

| Branch | Purpose |
| --- | --- |
| [`1.0`](https://github.com/Hoixg/rikkaplus/tree/1.0) | New rebuilding branch, starting from upstream RikkaHub. |
| [`custom`](https://github.com/Hoixg/rikkaplus/tree/custom) | Existing customized implementation, retained for use and feature reference. |

`1.0` is the branch name. The current upstream application baseline is **RikkaHub 3.0.0**.

## Rebuilding goals

- Review the custom features individually and define what to retain, simplify, or redesign.
- Separate feature logic from chat generation, interface code, and persistence.
- Review unused tables, redundant fields, and historical compatibility code against their actual use.
- Keep the Material 3 Expressive design consistent across the application.
- Verify each rebuilt feature and keep its changes easy to review and synchronize with upstream.

Custom features such as multiple API keys, scheduled tasks, subagents, image generation approvals, and tool interactions will be evaluated as part of this work. Their scope and progress will be documented as implementation proceeds.

## Current status

**The rebuilding work is at its starting stage.** The code is based on upstream RikkaHub; the custom features have not yet been rebuilt on this branch.

Old-data compatibility, database migration, import, and upgrade behavior remain undecided. This introduction does not establish a data migration policy.

Existing customized builds are available from [RikkaPlus Releases](https://github.com/Hoixg/rikkaplus/releases). Check the release notes for the branch associated with each build.

### Completed cleanup

- Removed the Web server, browser-based chat interface, related background service, and frontend build dependencies.

## Automated builds and releases

The default development branch is `1.0`.

- **Daily builds:** 02:00 and 17:00 Asia/Shanghai, skipping unchanged source. Signed APKs are published under [1.0-nightly](https://github.com/Hoixg/rikkaplus/releases/tag/1.0-nightly).
- **Manual cloud builds:** open [Actions](https://github.com/Hoixg/rikkaplus/actions/workflows/release.yml), select branch `1.0`, and choose `release` or `debug`. Download the artifacts after the workflow completes. This does not publish a release.
- **Versioned releases:** an annotated tag such as `1.0-v3.0.1`, matching the application's versionName, triggers a signed release. Historical custom release tags remain separate.
- **Release notes:** maintain categorized Chinese notes in `docs/releases/<versionName>.md`; see the [template](docs/releases/TEMPLATE.md). Other builds use categorized commit subjects and bodies.

Cloud builds use repository secrets `KEY_BASE64`, `SIGNING_CONFIG`, and `GOOGLE_SERVICES_JSON`. An optional `GOOGLE_SERVICES_JSON_1_0` takes precedence for this branch. When reusing the shared Firebase project configuration, the build matches its client entry to the package name; configure a registered Android client in the dedicated secret for Firebase services.

## Development

Built with Kotlin, Jetpack Compose, Material 3 Expressive, Room, and DataStore.

Use Android Studio and follow the upstream build configuration. Android builds require the appropriate `app/google-services.json` and local SDK configuration.

Feature plans and changes for this branch should be discussed in [this repository](https://github.com/Hoixg/rikkaplus/issues).

## Upstream and license

RikkaPlus is based on [RikkaHub](https://github.com/rikkahub/rikkahub), created by [re-ovo](https://github.com/re-ovo). Thanks to the original author and contributors.

The project retains the [GNU Affero General Public License v3.0](LICENSE) and original copyright notices.
