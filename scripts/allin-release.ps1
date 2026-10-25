# AllIn: builds a signed release APK and publishes it as a GitHub release that installed copies update from.
#
# Usage (from the Signal-Android folder):  powershell -File scripts\allin-release.ps1 [-Notes "What changed"]
# Before running: bump allinRevision in gradle.properties and commit, or the installed app won't see an update.
# Needs: JDK 21 (JAVA_HOME_21), Android SDK (ANDROID_HOME), gh logged in, and the key in ..\keys.

param(
  [string]$Notes = "AllIn update",
  [string]$Repo = "JasonXgit/AllIn-Android"
)

$ErrorActionPreference = "Stop"
Set-Location (Split-Path $PSScriptRoot -Parent)

if (-not $env:JAVA_HOME_21) { $env:JAVA_HOME_21 = [Environment]::GetEnvironmentVariable("JAVA_HOME_21", "User") }
$env:JAVA_HOME = $env:JAVA_HOME_21
if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = [Environment]::GetEnvironmentVariable("ANDROID_HOME", "User") }

if (git status --porcelain) { throw "Commit your changes first; releases are built from a clean tree so the source on GitHub matches." }

Write-Host "Building websiteProdRelease..."
.\gradlew.bat --console=plain assembleWebsiteProdRelease
if ($LASTEXITCODE -ne 0) { throw "Build failed" }

$apk = Get-ChildItem app\build\outputs\apk\websiteProd\release -Filter "*universal*.apk" | Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $apk) { throw "No universal release APK found" }

$aapt = Get-ChildItem "$env:ANDROID_HOME\build-tools" -Recurse -Filter aapt2.exe | Sort-Object FullName -Descending | Select-Object -First 1
$badging = & $aapt.FullName dump badging $apk.FullName | Select-String "^package:"
$versionCode = [int]([regex]::Match($badging, "versionCode='(\d+)'").Groups[1].Value)
$versionName = [regex]::Match($badging, "versionName='([^']+)'").Groups[1].Value
$sha256 = (Get-FileHash $apk.FullName -Algorithm SHA256).Hash.ToLower()
$tag = "v$versionName"
$assetName = "AllIn-$versionName.apk"

$out = Join-Path $env:TEMP "allin-release-$versionName"
New-Item -ItemType Directory -Force $out | Out-Null
Copy-Item $apk.FullName (Join-Path $out $assetName) -Force

# The format Signal's ApkUpdateJob reads.
@{
  versionCode     = $versionCode
  versionName     = $versionName
  url             = "https://github.com/$Repo/releases/download/$tag/$assetName"
  sha256sum       = $sha256
  uploadTimestamp = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
} | ConvertTo-Json | Set-Content -Encoding ascii (Join-Path $out "latest.json")

Write-Host "Publishing $tag (versionCode $versionCode, sha256 $sha256)..."
git push allin HEAD:allin
if ($LASTEXITCODE -ne 0) { throw "git push failed" }
gh release create $tag (Join-Path $out $assetName) (Join-Path $out "latest.json") --repo $Repo --target (git rev-parse HEAD) --title "AllIn $versionName" --notes $Notes
if ($LASTEXITCODE -ne 0) { throw "gh release create failed" }

Write-Host "Released: https://github.com/$Repo/releases/tag/$tag"
