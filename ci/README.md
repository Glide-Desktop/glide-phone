# CI signing

`glide-ci-test.keystore` is a **public** test key (password `glide-ci-test`). Builds signed
with it install and update each other, but anyone can sign an "update" with it — use it for
testing only.

For real releases add these repository secrets; the workflow then signs with your key:

| Secret | Value |
|---|---|
| `GLIDE_KEYSTORE_BASE64` | `base64 -w 0 release.jks` |
| `GLIDE_KEYSTORE_PASSWORD` | the keystore password |
| `GLIDE_KEY_ALIAS` | the key alias |
| `GLIDE_KEY_PASSWORD` | the key password (defaults to the keystore password) |

Create a key with:

```sh
keytool -genkeypair -keystore release.jks -alias glide -keyalg RSA -keysize 4096 -validity 36500
```

Releases: push a tag `vX.Y.Z`; the APK (versionName `X.Y.Z`, versionCode = the CI run number, so
newer builds always update older ones) is attached to the GitHub release of that tag. Every push to
`main`, `fix/**`, `feat/**` and every pull request builds the same APK as a workflow artifact.
