# Restore the two detector models from the team's checksum-pinned public 0.4 release.
# Usage: powershell -File tools/fetch_prototype_models.ps1
$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$cacheDir = Join-Path $repoRoot 'build\release-review'
$apkPath = Join-Path $cacheDir 'RESILANE-0.4.apk'
$assetDir = Join-Path $repoRoot 'app\src\main\assets\models'
$apkHash = '9329b9b648d0edeb429a365eb427c86bb817489093c64380699dade7d45ee7bf'
$modelHashes = [ordered]@{
    'yolo11n_384x640.onnx' = '612029b66e0277fa2c1b428a4f75cc70286a122e9a494e7e8aaaadb86a26cd25'
    'road_yolo11n_384x640.onnx' = '56228d400cf6469cf4e65cb0b1ff7fb7a294c668e02528a1823a549682debe7b'
}
New-Item -ItemType Directory -Force -Path $cacheDir,$assetDir | Out-Null
if (-not (Test-Path -LiteralPath $apkPath)) {
    Invoke-WebRequest -UseBasicParsing -Uri 'https://github.com/0xh1tesh/RoadMesh-iqoo/releases/download/v0.4.0-preview/RESILANE-0.4.apk' -OutFile $apkPath
}
if ((Get-FileHash -LiteralPath $apkPath -Algorithm SHA256).Hash -ne $apkHash) {
    throw 'Cached release APK checksum differs. No models were extracted.'
}
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [IO.Compression.ZipFile]::OpenRead($apkPath)
try {
    foreach ($name in $modelHashes.Keys) {
        $target = Join-Path $assetDir $name
        if (-not (Test-Path -LiteralPath $target)) {
            $entry = $archive.GetEntry("assets/models/$name")
            if ($null -eq $entry) { throw "Release is missing $name" }
            [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $target)
        }
        if ((Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash -ne $modelHashes[$name]) {
            throw "Existing $name differs from the pinned release. It was preserved; resolve it explicitly."
        }
        Write-Output "Verified $name"
    }
} finally { $archive.Dispose() }
