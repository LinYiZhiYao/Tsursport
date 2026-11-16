# build.ps1 (ASCII only) -- build Tsursport.apk without Gradle
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File .\build.ps1
#   powershell -ExecutionPolicy Bypass -File .\build.ps1 -Sdk "D:\Android\Sdk"
#
# Requirements:
#   * Android SDK build-tools (aapt2 / d8 / zipalign / apksigner) + platform android.jar
#   * JDK 11 or newer (javac / keytool)
#
# Env:
#   ANDROID_HOME or ANDROID_SDK_ROOT  -> SDK root
#   JAVA_HOME                          -> JDK root

param(
  [string]$Sdk = "",
  [string]$Javac = "",
  [string]$Keytool = "",
  [string]$VersionName = "2.1",
  [int]$VersionCode = 21
)

$ErrorActionPreference = "Continue"
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$App = Join-Path $Root "app"

# ---------- locate SDK ----------
if (-not $Sdk) {
  if ($env:ANDROID_HOME) { $Sdk = $env:ANDROID_HOME }
  elseif ($env:ANDROID_SDK_ROOT) { $Sdk = $env:ANDROID_SDK_ROOT }
}
if (-not $Sdk -or -not (Test-Path $Sdk)) {
  throw "Android SDK not found. Pass -Sdk <path> or set ANDROID_HOME."
}

$btRoot = Join-Path $Sdk "build-tools"
if (-not (Test-Path $btRoot)) { throw "build-tools missing under $Sdk" }

# tools may live directly in build-tools (unversioned) or in a version subfolder
function Test-Bt([string]$dir) {
  return (Test-Path (Join-Path $dir "aapt2.exe")) -and (Test-Path (Join-Path $dir "d8.bat"))
}
$bt = $null
if (Test-Bt $btRoot) {
  $bt = $btRoot
} else {
  $cand = Get-ChildItem $btRoot -Directory | Where-Object { Test-Bt $_.FullName } |
          Sort-Object Name -Descending | Select-Object -First 1
  if ($cand) { $bt = $cand.FullName }
}
if (-not $bt) { throw "no usable build-tools (need aapt2 + d8) under $btRoot" }
$androidJar = Join-Path $Sdk "platform\android.jar"
if (-not (Test-Path $androidJar)) {
  $plat = Get-ChildItem (Join-Path $Sdk "platforms") -Directory -ErrorAction SilentlyContinue |
          Sort-Object Name -Descending | Select-Object -First 1
  if ($plat) { $androidJar = Join-Path $plat.FullName "android.jar" }
}
if (-not (Test-Path $androidJar)) { throw "android.jar not found. Install a platform via sdkmanager." }

# ---------- locate JDK tools ----------
function Find-Tool([string]$explicit, [string]$name) {
  if ($explicit -and (Test-Path $explicit)) { return $explicit }
  if ($env:JAVA_HOME) {
    $p = Join-Path $env:JAVA_HOME ("bin\" + $name + ".exe")
    if (Test-Path $p) { return $p }
  }
  $cmd = Get-Command $name -ErrorAction SilentlyContinue
  if ($cmd) { return $cmd.Source }
  throw "$name not found. Install a JDK and set JAVA_HOME."
}
$Javac = Find-Tool $Javac "javac"
$Keytool = Find-Tool $Keytool "keytool"

Write-Host ("SDK      : " + $Sdk)
Write-Host ("build-tools: " + (Split-Path -Leaf $bt))
Write-Host ("javac    : " + $Javac)

# ---------- build ----------
$out = Join-Path $Root "build"
$classes = Join-Path $out "classes"
$dex = Join-Path $out "dex"
Remove-Item $out -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $classes, $dex | Out-Null

$src = Join-Path $App "src\com\tsursport\Tsursport.java"
& $Javac -nowarn -encoding UTF-8 -source 11 -target 11 -classpath $androidJar -d $classes $src 2>&1 | Out-Null
if (-not (Test-Path (Join-Path $classes "com\tsursport\Tsursport.class"))) { throw "javac failed" }
Write-Host "[1/5] javac ok" -ForegroundColor Green

$classFiles = Get-ChildItem $classes -Recurse -Filter *.class | ForEach-Object { $_.FullName }
& (Join-Path $bt "d8.bat") --min-api 29 --lib $androidJar --output $dex $classFiles 2>&1 | Out-Null
if (-not (Test-Path (Join-Path $dex "classes.dex"))) { throw "d8 failed" }
Write-Host "[2/5] d8 ok" -ForegroundColor Green

$unsigned = Join-Path $out "base-unsigned.apk"
$resDir = Join-Path $App "res"
$resFlat = Join-Path $out "res"
$linkRes = @()
if (Test-Path $resDir) {
  New-Item -ItemType Directory -Force -Path $resFlat | Out-Null
  & (Join-Path $bt "aapt2.exe") compile --dir $resDir -o $resFlat 2>&1 | Out-Null
  $linkRes = (Get-ChildItem $resFlat -Filter *.flat | ForEach-Object { @("-R", $_.FullName) })
}
& (Join-Path $bt "aapt2.exe") link -o $unsigned --manifest (Join-Path $App "AndroidManifest.xml") `
    -I $androidJar @linkRes --min-sdk-version 29 --target-sdk-version 34 `
    --version-code $VersionCode --version-name $VersionName 2>&1 | Out-Null
if (-not (Test-Path $unsigned)) { throw "aapt2 failed" }
Write-Host "[3/5] aapt2 ok" -ForegroundColor Green

Add-Type -AssemblyName System.IO.Compression, System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::Open($unsigned, "Update")
$old = $zip.Entries | Where-Object { $_.FullName -eq "classes.dex" }
if ($old) { $old.Delete() }
[void][IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, (Join-Path $dex "classes.dex"),
      "classes.dex", [IO.Compression.CompressionLevel]::Optimal)
$assets = Join-Path $App "assets"
if (Test-Path $assets) {
  foreach ($f in (Get-ChildItem $assets -File)) {
    $name = "assets/" + $f.Name
    $oldEntry = $zip.Entries | Where-Object { $_.FullName -eq $name }
    if ($oldEntry) { $oldEntry.Delete() }
    [void][IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $f.FullName, $name,
          [IO.Compression.CompressionLevel]::Optimal)
  }
}
$zip.Dispose()
Write-Host "[4/5] dex packaged" -ForegroundColor Green

$aligned = Join-Path $out "base-aligned.apk"
& (Join-Path $bt "zipalign.exe") -f 4 $unsigned $aligned 2>&1 | Out-Null
if (-not (Test-Path $aligned)) { Copy-Item $unsigned $aligned }

# debug keystore (generated on first run; keep it out of git)
$ksDir = Join-Path $Root ".keystore"
New-Item -ItemType Directory -Force -Path $ksDir | Out-Null
$ks = Join-Path $ksDir "debug.keystore"
if (-not (Test-Path $ks)) {
  & $Keytool -genkeypair -keystore $ks -storepass android -keypass android `
      -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 `
      -dname "CN=Android Debug,O=Android,C=US" 2>&1 | Out-Null
}
$signed = Join-Path $Root "Tsursport.apk"
& (Join-Path $bt "apksigner.bat") sign --ks $ks --ks-pass pass:android --key-pass pass:android --out $signed $aligned 2>&1 | Out-Null
if (-not (Test-Path $signed)) { throw "apksigner failed" }

$size = [math]::Round((Get-Item $signed).Length / 1KB)
$hash = (Get-FileHash $signed -Algorithm SHA256).Hash
Write-Host ("[5/5] signed -> " + $signed + "  " + $size + " KB") -ForegroundColor Green
Write-Host ("SHA256: " + $hash)
