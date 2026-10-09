# Build toolchain (verified 9 Oct)

| Item | Version / path |
|---|---|
| Gradle (wrapper) | 8.14.6 (also unzipped at `C:\Users\hites\dev_tools\gradle-8.14.6`) |
| Android Gradle Plugin | 8.13.2 (AGP 9.x is too new for current Compose pins) |
| Kotlin | 2.2.21 + Compose compiler plugin |
| Compose BOM | 2026.02.01 (2026.04+ needs AGP 9.1 / compileSdk 37; do not bump) |
| compileSdk / targetSdk / minSdk | 36 / 36 / 31 |
| JDK | 17: `C:\Users\hites\dev_tools\jdk-17.0.14+7` (PATH default is JDK 8, do not use) |
| SDK | `C:\Users\hites\dev_tools\android-sdk` |

## Build in PowerShell
```powershell
$env:JAVA_HOME="C:\Users\hites\dev_tools\jdk-17.0.14+7"
$env:ANDROID_HOME="C:\Users\hites\dev_tools\android-sdk"
.\gradlew.bat assembleDebug testDebugUnitTest
```
APK: `app/build/outputs/apk/debug/app-debug.apk` (about 27 MB). Move to a phone with Office Kit file transfer, install from the file manager.

Each teammate needs a `local.properties` (git-ignored) with `sdk.dir=C:/path/to/android-sdk`.
