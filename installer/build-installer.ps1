# Gera dist\FeliProxy-Setup-<versão>.exe (compila o FeliProxy.exe antes).
# A versão vem do arquivo VERSION na raiz do projeto.
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot
$iscc = Join-Path $env:LOCALAPPDATA 'Programs\Inno Setup 6\ISCC.exe'
if (-not (Test-Path $iscc)) { $iscc = Join-Path ${env:ProgramFiles(x86)} 'Inno Setup 6\ISCC.exe' }
if (-not (Test-Path $iscc)) { throw 'Inno Setup 6 não encontrado (ISCC.exe).' }

$version = (Get-Content (Join-Path $root 'VERSION') -Raw).Trim()

& powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $root 'build.ps1')
if ($LASTEXITCODE -ne 0) { throw 'Falha ao compilar o FeliProxy.exe' }

& $iscc /Q "/DAppVersion=$version" (Join-Path $PSScriptRoot 'FeliProxy.iss')
if ($LASTEXITCODE -ne 0) { throw "ISCC falhou ($LASTEXITCODE)" }
$setup = Join-Path $root "dist\FeliProxy-Setup-$version.exe"
"OK: $setup ($([math]::Round((Get-Item $setup).Length / 1MB, 2)) MB)"
