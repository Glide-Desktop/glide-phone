# Glide Phone (Android)

The phone side of Phone Link for the [Glide desktop](https://github.com/Glide-Desktop): a
KDE Connect protocol client in a Material 3 design that pairs with
[glide-phone-desktop](https://github.com/Glide-Desktop/glide-phone-desktop).

Everything KDE Connect Android does (notifications, files, clipboard, media, remote input,
presenter, run commands, SMS, calls, contacts, SFTP, find my phone, …) plus Glide-only
extras: Wake-on-LAN, screenshot sync, charge alerts. See [docs/FEATURES.md](docs/FEATURES.md)
and [docs/PARITY.md](docs/PARITY.md).

## Download

APKs are built by GitHub Actions: every push to a work branch leaves one as a workflow
artifact, and a `vX.Y.Z` tag attaches it to that release. See [ci/README.md](ci/README.md)
for signing.

## Develop

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
./gradlew :app:connectedDebugAndroidTest            # on a device or emulator
scripts/live-desktop-test.sh <glide-phone binary>   # emulator against a real desktop build
```
