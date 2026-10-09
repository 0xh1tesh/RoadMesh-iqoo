# Push a file to every phone linked in vivo/iQOO Office Kit, with no adb and no USB debugging.
#
# This drives Office Kit's own "Use vivo Office Kit to send to vivo device" Explorer menu item:
#   pcsuite.exe --target=/filemanager --deviceid=<id> --filepath=<JSON {"mode":0,"path":[...]}>
# Device ids come from %APPDATA%\pcsuite\RightClickMenu\config.xml (one <item> per linked phone).
# Files land in Download/Office Kit/ on the phone; tap the APK there to install.
#
#   powershell -File tools/send-officekit.ps1 [path\to\file.apk]
param([string]$File = "")
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
if (-not $File) { $File = Get-ChildItem "$root\dist\ResiLane-*.apk" | Sort-Object LastWriteTime -Descending | Select-Object -First 1 -ExpandProperty FullName }
if (-not $File -or -not (Test-Path $File)) { throw "No APK found. Run tools/deploy.sh send (builds and copies to dist/)." }
$File = (Resolve-Path $File).Path -replace '\\', '/'

$cfg = "$env:APPDATA\pcsuite\RightClickMenu\config.xml"
if (-not (Test-Path $cfg)) { throw "Office Kit menu config not found ($cfg). Is Office Kit installed?" }
$exe = "C:\Program Files (x86)\pcsuite\pcsuite.exe"
$items = ([xml](Get-Content $cfg -Raw)).root.menu.item | Where-Object { $_.arguments -like "*--deviceid=*" }
if (-not $items) { throw "No phone linked in Office Kit. Connect one in the Office Kit window first." }

foreach ($it in $items) {
    $json = '{\"path\":[\"' + $File + '\"],\"mode\":0}'
    Start-Process -FilePath $exe -WorkingDirectory (Split-Path $exe) `
        -WindowStyle Hidden `
        -ArgumentList "--target=/filemanager --deviceid=$($it.name) `"--filepath=$json`""
    Write-Output "sent $File -> $($it.text) ($($it.name)); check Office Kit's transfer list"
}
Write-Output "On each phone: Files > Download > Office Kit > tap the APK > Install."
