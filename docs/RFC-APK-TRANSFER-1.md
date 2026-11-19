# RFC-APK-TRANSFER-1: inspect and transfer a phone APK to the selected TV

Status: approved scope from the repository owner's request (2026-11-14); implementation in progress on `feature/apk-transfer`.

## User flow

1. From the selected TV's Manage surface, open a clearly named **Install APK from phone** action.
2. Pick one local `.apk` file using Android's system document picker. Do not request broad storage access.
3. Copy the selected content URI into app-private temporary storage while streaming and enforcing a documented maximum size. Do not trust the display name or MIME type as proof that the file is an APK.
4. Inspect the copied file with the existing `:android:platform` `PackageManagerApkInspector`. Show package name, version name/code, single signing-certificate SHA-256, APK SHA-256 and exact size. Refuse malformed APKs and archives with missing or multiple current signers; explain the reason.
5. Show a review screen naming the selected TV and the inspected APK. Nothing is pushed or installed before the person chooses Transfer.
6. Transfer the exact inspected bytes to a unique app-owned temporary path on the connected TV using the existing authenticated ADB session and typed transfer/session APIs. Verify the remote size and SHA-256 before offering installation.
7. Only after a separate, explicit Install confirmation, invoke the existing guarded install path. Report the actual result; do not claim success based only on a successful push.
8. Clean up the phone and TV temporary files on cancel, failure, completion and screen exit where safe. If cleanup cannot be verified, disclose that state and retain enough information for a retry.

## Scope and constraints

- First release supports one standalone APK file. Split APK sets / XAPK / APKS are explicitly deferred.
- Phone-to-TV only; never install the APK on the phone.
- Use Android's Storage Access Framework picker and content resolver streams; no broad storage permission.
- Reuse the existing `PackageManagerApkInspector`, authenticated `AdbSessionManager`, ADB push/streaming primitives and guarded install machinery where their contracts fit. Do not create a second ADB connection or a raw-shell path from UI text.
- Bind the operation to the selected `TvId`; re-check that the live session is still the same TV before transfer and install.
- Do not install automatically after transfer. Transfer and install are separate confirmed actions.
- Treat APK metadata as untrusted. Never execute it on the phone. Reject path traversal, empty files, oversized files, malformed package metadata and unsupported signing layouts.
- Keep secrets, local paths, URI contents and APK bytes out of logs, diagnostics, reports and analytics.
- Do not overwrite an installed package silently. The review must show package identity and make replacement clear; rely on Android's package manager to reject incompatible signatures and report its real error.
- Preserve the app's safety conventions: preview the target and operation, explicit confirmation, verify the result, and clean up/undo temporary state.
- Localize all new visible strings in English and Tamil.

## Acceptance tests

- Picker cancellation, inaccessible URI, revoked permission, empty file, misleading MIME/name and oversized file.
- Valid APK metadata, version fields, signer digest, SHA-256 and byte count; malformed APK, no signer and multiple signers refused.
- TV identity changes or disconnects between selection, preview, transfer and install.
- Transfer interruption, insufficient TV storage, remote size/hash mismatch and cleanup failure.
- No install command before the explicit install confirmation; cancel after transfer cleans up without installing.
- Install success, downgrade/incompatible signer/package-manager rejection and actual error surfaced without false success.
- Per-TV routing, lifecycle/process recreation, English/Tamil resource parity, unit/integration tests, emulator coverage and a real Android TV smoke test.
- Full CI passes before opening a non-draft PR. Physical TV testing is reported separately from CI.

## Existing code to reuse

- `:android:platform`: `PackageManagerApkInspector` already computes package/version, single signer digest, SHA-256 and size.
- `:adb:session`: existing authenticated TV session and transfer primitives.
- `:core:engine` / `:core:flows`: existing guarded install, journal, confirmation and verification patterns.

No split-APK support is included in this first implementation.
