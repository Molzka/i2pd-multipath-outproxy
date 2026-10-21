param([switch]$SkipNative, [string[]]$Abis = @("arm64-v8a", "x86_64"))
$ErrorActionPreference = "Stop"
$android = $PSScriptRoot
$root = Split-Path $android -Parent
$assets = Join-Path $android "app\src\main\assets"
$out = Join-Path $android "native\out"
New-Item -ItemType Directory -Force $assets | Out-Null

foreach ($abi in $Abis) {
    if (-not $SkipNative -or -not (Test-Path "$out\$abi\libi2pd.so")) {
        Write-Host "==> i2pd + agg-tunnel + i2pmux for $abi (Docker, NDK)"
        docker build -f "$android\native\Dockerfile" --build-arg "ABI=$abi" --output "type=local,dest=$out" $root
        if ($LASTEXITCODE) { throw "native build failed ($abi)" }
    }
    $jni = Join-Path $android "app\src\main\jniLibs\$abi"
    New-Item -ItemType Directory -Force $jni | Out-Null
    Copy-Item "$out\$abi\*.so" $jni -Force
}
if (Test-Path "$assets\certificates") { Remove-Item -Recurse -Force "$assets\certificates" }
Copy-Item -Recurse "$out\certificates" "$assets\certificates"
Copy-Item "$root\client\multipath\multipath.cfg" "$assets\multipath.cfg" -Force

$props = Join-Path $android "keystore.properties"
if (-not (Test-Path $props)) {
    Write-Host "==> generating release keystore"
    $pw = -join ((48..57) + (97..122) | Get-Random -Count 24 | ForEach-Object { [char]$_ })
    keytool -genkeypair -keystore "$android\release.jks" -storetype PKCS12 -alias release `
        -keyalg EC -groupname secp256r1 -validity 10000 -storepass $pw -keypass $pw `
        -dname "CN=Multipath I2P" | Out-Null
    if ($LASTEXITCODE) { throw "keytool failed" }
    "storeFile=release.jks`nstorePassword=$pw`nkeyAlias=release`nkeyPassword=$pw`n" |
        Set-Content -Encoding ascii $props
}

$local = Join-Path $android "local.properties"
if (-not (Test-Path $local)) {
    $sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:LOCALAPPDATA\Android\Sdk" }
    [IO.File]::WriteAllText($local, "sdk.dir=$($sdk.Replace('\','\\').Replace(':','\:'))`n")
}

Write-Host "==> gradle assembleRelease"
Push-Location $android
try {
    & .\gradlew.bat --no-daemon assembleRelease
    if ($LASTEXITCODE) { throw "gradle failed" }
} finally { Pop-Location }

$dist = Join-Path $root "dist"
New-Item -ItemType Directory -Force $dist | Out-Null
foreach ($abi in $Abis) {
    $name = if ($abi -eq "arm64-v8a") { "multipath-i2p.apk" } else { "multipath-i2p-$abi.apk" }
    Copy-Item "$android\app\build\outputs\apk\release\app-$abi-release.apk" "$dist\$name" -Force
    Write-Host "==> $dist\$name"
}
