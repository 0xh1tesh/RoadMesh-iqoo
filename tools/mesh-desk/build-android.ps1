param(
    [string]$GradleHome = 'D:/dev-cache/gradle',
    [string]$JavaHome = 'D:/dev-cache/jdks/jdk-17.0.20.1+1'
)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$dist = Get-ChildItem -LiteralPath (Join-Path $GradleHome 'wrapper/dists/gradle-8.14.6-bin') -Filter gradle.bat -Recurse | Select-Object -First 1
if (!$dist) { throw 'Gradle 8.14.6 distribution is not in the supplied cache.' }
$env:GRADLE_RO_DEP_CACHE = Join-Path $GradleHome 'caches'
$env:JAVA_HOME = $JavaHome
Set-Location -LiteralPath $repo
& $dist.FullName --gradle-user-home "$PSScriptRoot/artifacts/gradle-home" "-Dorg.gradle.java.home=$JavaHome" --no-daemon --max-workers=1 '-Pkotlin.compiler.execution.strategy=in-process' '-Pkotlin.incremental=false' --project-cache-dir "$PSScriptRoot/artifacts/gradle-project-cache" --init-script "$PSScriptRoot/android-build.init.gradle" :app:assembleDebug --offline
exit $LASTEXITCODE
