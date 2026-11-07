# ManyManager Simple release guide

This guide targets `main` in `RearmostP/ManyManager-Private-version` (formerly `original` in `RearmostP/ManyManager`): use `simple-v<VERSION>` and `ManyManager-Simple-<VERSION>.apk` for this branch. Simple uses its own package identity, `com.manymanager.privateversion`, and retains the existing signing configuration.

Run commands from the repository root, using a clean, reviewed commit and a compatible JDK. The current checked-in configuration on this branch is `com.manymanager.privateversion`, versionName `0.3.0`, versionCode `3`. Recheck [androidApp/build.gradle.kts](../androidApp/build.gradle.kts) before each release; this is not a claim that an APK was published.

## Independent releases

| Repository / branch | Application | Tag | APK | Release title |
| --- | --- | --- | --- | --- |
| `RearmostP/ManyManager` / `main` | ManyManager | `manymanager-v<VERSION>` | `ManyManager-<VERSION>.apk` | `ManyManager <VERSION>` |
| `RearmostP/ManyManager-Private-version` / `main` | ManyManager Simple | `simple-v<VERSION>` | `ManyManager-Simple-<VERSION>.apk` | `ManyManager Simple <VERSION>` |

Each application can advance and publish independently. Each release contains only its own APK and its tag points to the exact build commit. Filter tags by application prefix rather than GitHub's global “Latest”. Preserve historical tags/releases, including `0.1.0`. Existing tags/releases remain in the old repository; the source-branch migration does not republish releases, APKs or signing material.

Simple (`com.manymanager.privateversion`) and the personal edition (`com.example.manymanager`) have distinct package identities and can be installed side by side. Each package has separate app-private data; changing applicationId does not automatically transfer the previous package’s database or preferences. VersionCode can advance independently for each package. Normal updates require the same applicationId and signing identity and a versionCode higher than every previously distributed build for that package; update versionName accordingly.

## Build and verify

Use the existing release key:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/release.ps1
```

[release.ps1](../scripts/release.ps1) defaults to Android Studio JBR at `C:\Program Files\Android\Android Studio\jbr`; use `-JavaHome` for another supported JDK. It runs `:androidApp:assembleRelease --no-configuration-cache --console=plain`. Avoid debug logging/build scans containing credentials. Missing signing configuration fails explicitly rather than producing an unsigned/debug-signed substitute.

With JAVA_HOME set to a supported JDK, verify the output (adjust the installed build-tools path if necessary):

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\build-tools\36.0.0\apksigner.bat" verify --verbose --print-certs androidApp/build/outputs/apk/release/androidApp-release.apk
& "$env:LOCALAPPDATA\Android\Sdk\build-tools\36.0.0\aapt.exe" dump badging androidApp/build/outputs/apk/release/androidApp-release.apk
& "$env:LOCALAPPDATA\Android\Sdk\build-tools\36.0.0\aapt.exe" dump xmltree androidApp/build/outputs/apk/release/androidApp-release.apk AndroidManifest.xml
Get-FileHash -Algorithm SHA256 androidApp/build/outputs/apk/release/androidApp-release.apk
git status --short
git rev-parse HEAD
```

Confirm package/version, non-debuggable/non-testOnly manifest, backup settings, valid signature and the same release certificate as a trusted previous APK/key. A debug-signed installation cannot be updated by a differently signed release; stop and inspect rather than uninstalling or clearing data.

Copy only the verified APK to the ignored `private-release/` directory using the table's filename (for example `ManyManager-Simple-0.1.0.apk`). Do not overwrite an existing delivery file without investigating. Hash the delivered copy too and record its checksum, certificate and build commit. Never include signing material with the APK. Tag/publish/upload only when authorized, using the exact build commit and naming above.

## Signing configuration and continuity

- Default signing directory: `%USERPROFILE%\.manymanager\signing`, outside the repository.
- Key file: `manymanager-release.p12`; alias: `manymanager-release`.
- Credential file: `release-credential.clixml`, a PSCredential protected by Windows DPAPI for the current account/computer.
- `-SigningDirectory` may select another secure directory outside the repository.
- Gradle process variables: `MANYMANAGER_RELEASE_STORE_FILE`, `MANYMANAGER_RELEASE_STORE_PASSWORD`, `MANYMANAGER_RELEASE_KEY_ALIAS`, `MANYMANAGER_RELEASE_KEY_PASSWORD`. Never commit their values. Use `--no-configuration-cache`; the script clears its process credentials on completion.
- `-InitializeKey` is initial provisioning only, requiring both signing files to be absent. Never generate a replacement key for an update.

Back up the p12 and password separately (encrypted backup and password manager), retaining restricted directory permissions. CLIXML alone is not a portable backup: DPAPI may not decrypt on another account/computer. Loss of key/password can prevent update continuity; backup restoration has not yet been tested.

For deliberate local password-manager backup, the script's `-CopyPassword` option copies the password to the clipboard without printing it. Paste only into a secure password manager, then run `Set-Clipboard -Value $null`. Never paste it into chat, logs or source.

## Related records

- [Current application and maintenance guidance](README.md), including schema preservation, backup eligibility and isolated instrumentation precautions.
- [Historical release preparation](archive/release-preparation.md): original 0.1.0 artifact/certificate records and 0.2.0 preparation; historical “not published” statements apply to their original dates.
- [Persistence acceptance](archive/persistent-data-acceptance.md) and [expense/details delivery](archive/expense-details.md): historical verification and remaining gaps.
