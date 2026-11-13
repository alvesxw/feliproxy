# Compila libhev-socks5-tunnel.so (tun2socks) com o NDK e copia para app/src/main/jniLibs.
$ErrorActionPreference = 'Stop'
$here = $PSScriptRoot
$ndk  = Join-Path $env:LOCALAPPDATA 'Android\Sdk\ndk\27.2.12479018\ndk-build.cmd'
$src  = Join-Path $here 'hev-socks5-tunnel'
$out  = Join-Path $here 'out'
$jni  = Join-Path $here '..\app\src\main\jniLibs'

& $ndk -C $src `
    "NDK_PROJECT_PATH=$src" "APP_BUILD_SCRIPT=$src\Android.mk" "NDK_APPLICATION_MK=$src\Application.mk" `
    "NDK_OUT=$out\obj" "NDK_LIBS_OUT=$out\libs" `
    "APP_ABI=arm64-v8a armeabi-v7a x86_64" "APP_MODULES=hev-socks5-tunnel" "APP_PLATFORM=android-29" `
    -j $env:NUMBER_OF_PROCESSORS
if ($LASTEXITCODE -ne 0) { throw "ndk-build falhou ($LASTEXITCODE)" }

foreach ($abi in 'arm64-v8a', 'armeabi-v7a', 'x86_64') {
    New-Item -ItemType Directory -Force (Join-Path $jni $abi) | Out-Null
    Copy-Item (Join-Path $out "libs\$abi\libhev-socks5-tunnel.so") (Join-Path $jni $abi) -Force
}
Get-ChildItem $jni -Recurse -File | ForEach-Object { "{0}  {1:N0} KB" -f $_.FullName.Substring($jni.Length), ($_.Length / 1KB) }
