# Gera tudo o que vai numa release do GitHub em release\v<versão>\:
#   FeliProxy-<versão>.exe           programa (portátil, pede admin)
#   FeliProxy-Setup-<versão>.exe     instalador (instala o ProxiFyre junto)
#   FeliProxy-<versão>.apk           Android (assinado com android\release.jks)
#   SHA256SUMS.txt
# A versão vem do arquivo VERSION.
#   powershell -ExecutionPolicy Bypass -File release.ps1 [-SkipAndroid]
param([switch]$SkipAndroid)

$ErrorActionPreference = 'Stop'
$root    = $PSScriptRoot
$version = (Get-Content (Join-Path $root 'VERSION') -Raw).Trim()
$outDir  = Join-Path $root "release\v$version"

# --- Windows: .exe + instalador ---
& powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $root 'installer\build-installer.ps1')
if ($LASTEXITCODE -ne 0) { throw 'Falha no build do Windows.' }

# --- Android: .apk ---
if (-not $SkipAndroid) {
    if (-not (Test-Path (Join-Path $root 'android\keystore.properties'))) {
        throw 'android\keystore.properties não encontrado: sem ele o APK sai sem assinatura. Use -SkipAndroid para pular.'
    }
    if (-not $env:JAVA_HOME) {
        $jdk = Get-ChildItem (Join-Path $env:LOCALAPPDATA 'Programs\Temurin') -Directory -Filter 'jdk-17*' -ErrorAction SilentlyContinue |
               Select-Object -Last 1
        if ($jdk) { $env:JAVA_HOME = $jdk.FullName }
    }
    Push-Location (Join-Path $root 'android')
    try {
        & .\gradlew.bat --no-daemon -q assembleRelease
        if ($LASTEXITCODE -ne 0) { throw "Gradle falhou ($LASTEXITCODE)" }
    } finally { Pop-Location }
    Copy-Item (Join-Path $root 'android\app\build\outputs\apk\release\app-release.apk') (Join-Path $root 'dist\FeliProxy.apk') -Force
}

# --- Junta os arquivos da release ---
if (Test-Path $outDir) { Remove-Item $outDir -Recurse -Force }
New-Item -ItemType Directory -Force $outDir | Out-Null
Copy-Item (Join-Path $root 'dist\FeliProxy.exe') (Join-Path $outDir "FeliProxy-$version.exe")
Copy-Item (Join-Path $root "dist\FeliProxy-Setup-$version.exe") $outDir
if (-not $SkipAndroid) { Copy-Item (Join-Path $root 'dist\FeliProxy.apk') (Join-Path $outDir "FeliProxy-$version.apk") }

$sums = Get-ChildItem $outDir -File | Sort-Object Name | ForEach-Object {
    '{0}  {1}' -f (Get-FileHash $_.FullName -Algorithm SHA256).Hash.ToLower(), $_.Name
}
[IO.File]::WriteAllLines((Join-Path $outDir 'SHA256SUMS.txt'), [string[]]$sums)

''
"Release v$version pronta em $outDir"
Get-ChildItem $outDir -File | ForEach-Object { '  {0,-32} {1,8:N2} MB' -f $_.Name, ($_.Length / 1MB) }
''
'Para publicar: crie a tag e suba os arquivos acima na página de releases do GitHub,'
'ou, com o GitHub CLI (gh) instalado:'
"  git tag v$version; git push origin v$version"
"  gh release create v$version release\v$version\* --title `"FeliProxy $version`" --generate-notes"
