# build.ps1 - Gradle-free APK build for CallBot.
#
# WHY: this bench PC's endpoint security breaks Java NIO Selector.open()
# ("Unable to establish loopback connection"), which Gradle needs even in
# --no-daemon mode. This script builds the APK with the SDK tools directly:
#     aapt2 compile/link -> javac -> d8 -> zip -> zipalign -> apksigner
# None of these require Java networking. The Gradle files are kept in the
# repo for use on unrestricted machines.
#
# Usage:  pwsh -File build.ps1            (or .\build.ps1 from a pwsh prompt)
# Output: build_manual\app-debug.apk

$ErrorActionPreference = 'Stop'

$SDK      = 'D:\Essence_SC\dev\android-sdk'
$BT       = "$SDK\build-tools\34.0.0"
$PLATFORM = "$SDK\platforms\android-34\android.jar"
$PROJ     = $PSScriptRoot
$SRC      = "$PROJ\app\src\main"
$OUT      = "$PROJ\build_manual"
$PKG      = 'com.essence.callbot'
$MIN_SDK  = 29
$TARGET_SDK = 34
$VERSION_CODE = 1
$VERSION_NAME = '1.0.0'

foreach ($p in @("$BT\aapt2.exe", "$BT\d8.bat", "$BT\zipalign.exe", "$BT\apksigner.bat", $PLATFORM)) {
    if (-not (Test-Path $p)) { throw "missing SDK piece: $p (install build-tools;34.0.0 + platforms;android-34)" }
}
$javac = (Get-Command javac.exe).Source
Write-Host "javac: $javac"

if (Test-Path $OUT) { Remove-Item $OUT -Recurse -Force }
New-Item -ItemType Directory -Force "$OUT\gen", "$OUT\classes", "$OUT\dex" | Out-Null

# --- manifest: inject the package attribute (required by raw aapt2;
#     forbidden by AGP 8, so it must not live in the source manifest) -------
$manifest = Get-Content "$SRC\AndroidManifest.xml" -Raw
$manifest = $manifest -replace '<manifest ', "<manifest package=`"$PKG`" "
Set-Content "$OUT\AndroidManifest.xml" $manifest -Encoding UTF8
Write-Host '[1/7] manifest prepared'

# --- aapt2 compile + link ---------------------------------------------------
& "$BT\aapt2.exe" compile --dir "$SRC\res" -o "$OUT\res.zip"
if ($LASTEXITCODE -ne 0) { throw 'aapt2 compile failed' }
& "$BT\aapt2.exe" link -o "$OUT\app-unsigned.apk" -I $PLATFORM `
    --manifest "$OUT\AndroidManifest.xml" `
    --min-sdk-version $MIN_SDK --target-sdk-version $TARGET_SDK `
    --version-code $VERSION_CODE --version-name $VERSION_NAME `
    --java "$OUT\gen" "$OUT\res.zip"
if ($LASTEXITCODE -ne 0) { throw 'aapt2 link failed' }
Write-Host '[2/7] resources linked'

# --- javac -------------------------------------------------------------------
$sources = @(Get-ChildItem "$SRC\java" -Recurse -Filter *.java | ForEach-Object FullName)
$sources += @(Get-ChildItem "$OUT\gen" -Recurse -Filter *.java | ForEach-Object FullName)
$sources | Set-Content "$OUT\sources.txt" -Encoding ASCII
& $javac -source 17 -target 17 -encoding UTF-8 -nowarn `
    -classpath $PLATFORM -d "$OUT\classes" "@$OUT\sources.txt" 2>&1 |
    Where-Object { $_ -notmatch 'bootstrap classpath' -and $_ -notmatch '^warning:' -and $_ -notmatch '^\d+ warning' }
if ($LASTEXITCODE -ne 0) { throw 'javac failed' }
Write-Host '[3/7] java compiled'

# --- d8 ----------------------------------------------------------------------
# pass one jar instead of many .class args (d8.bat arg handling chokes on long lists)
Add-Type -AssemblyName System.IO.Compression.FileSystem
[System.IO.Compression.ZipFile]::CreateFromDirectory("$OUT\classes", "$OUT\classes.jar")
& "$BT\d8.bat" --release --lib $PLATFORM --min-api $MIN_SDK --output "$OUT\dex" "$OUT\classes.jar"
if ($LASTEXITCODE -ne 0) { throw 'd8 failed' }
Write-Host '[4/7] dexed'

# --- add classes.dex to the APK ----------------------------------------------
Add-Type -AssemblyName System.IO.Compression, System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open("$OUT\app-unsigned.apk", 'Update')
try {
    $entry = $zip.CreateEntry('classes.dex')
    $es = $entry.Open()
    $fs = [System.IO.File]::OpenRead("$OUT\dex\classes.dex")
    $fs.CopyTo($es); $fs.Dispose(); $es.Dispose()
} finally { $zip.Dispose() }
Write-Host '[5/7] classes.dex packed'

# --- zipalign + sign ----------------------------------------------------------
& "$BT\zipalign.exe" -f 4 "$OUT\app-unsigned.apk" "$OUT\app-aligned.apk"
if ($LASTEXITCODE -ne 0) { throw 'zipalign failed' }

$ks = "$env:USERPROFILE\.android\debug.keystore"
if (-not (Test-Path $ks)) {
    New-Item -ItemType Directory -Force (Split-Path $ks) | Out-Null
    $keytool = Join-Path (Split-Path (Split-Path $javac)) 'bin\keytool.exe'
    & $keytool -genkeypair -keystore $ks -storepass android -keypass android `
        -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 `
        -dname 'CN=Android Debug,O=Android,C=US'
    if ($LASTEXITCODE -ne 0) { throw 'keytool failed' }
    Write-Host '      debug keystore created'
}
Write-Host '[6/7] aligned'

& "$BT\apksigner.bat" sign --ks $ks --ks-pass pass:android --key-pass pass:android `
    --ks-key-alias androiddebugkey --out "$OUT\app-debug.apk" "$OUT\app-aligned.apk"
if ($LASTEXITCODE -ne 0) { throw 'apksigner failed' }
Write-Host "[7/7] signed -> $OUT\app-debug.apk"
