# Compila dist\FeliProxy.exe com o compilador C# que já vem no Windows (.NET Framework 4.x).
# O instalador oficial do ProxiFyre vai embutido no .exe (instala sozinho se faltar).
#   powershell -ExecutionPolicy Bypass -File build.ps1

$ErrorActionPreference = 'Stop'
$root   = $PSScriptRoot
$csc    = Join-Path $env:windir 'Microsoft.NET\Framework64\v4.0.30319\csc.exe'
$setup  = Join-Path $root 'vendor\ProxiFyre-2.6.4-win-x64-setup.exe'
$sha256 = 'afaf3ac8a2069f9653adb1b4a63b31d1edd8aa109ccdc367cf0d4462e78f5027'  # digest publicado no GitHub (v2.6.4)
$icon   = Join-Path $root 'src\app.ico'
$out    = Join-Path $root 'dist\FeliProxy.exe'
$setupUrl = 'https://github.com/wiresock/proxifyre/releases/download/v2.6.4/ProxiFyre-2.6.4-win-x64-setup.exe'
$version  = (Get-Content (Join-Path $root 'VERSION') -Raw).Trim()
if ($version -notmatch '^\d+\.\d+\.\d+$') { throw "VERSION inválida: '$version' (use X.Y.Z)" }

if (-not (Test-Path $csc))   { throw "Compilador não encontrado: $csc" }
if (-not (Test-Path $setup)) {
    # O instalador do ProxiFyre não vai no repositório: baixa o oficial do GitHub (o hash é conferido abaixo).
    "Baixando $setupUrl"
    New-Item -ItemType Directory -Force (Split-Path $setup) | Out-Null
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    (New-Object Net.WebClient).DownloadFile($setupUrl, $setup)
}
if ((Get-FileHash $setup -Algorithm SHA256).Hash.ToLower() -ne $sha256) { throw 'O hash do instalador do ProxiFyre não confere.' }

# --- Ícone: túnel branco sobre quadrado arredondado azul (gerado aqui) ---
Add-Type -AssemblyName System.Drawing
function New-IconPng([int]$size) {
    $bmp = New-Object Drawing.Bitmap $size, $size
    $g = [Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = 'AntiAlias'
    $g.Clear([Drawing.Color]::Transparent)
    $r = [single]($size * 0.22); $d = $r * 2; $w = [single]($size - 1)
    $path = New-Object Drawing.Drawing2D.GraphicsPath
    $path.AddArc(0, 0, $d, $d, 180, 90); $path.AddArc($w - $d, 0, $d, $d, 270, 90)
    $path.AddArc($w - $d, $w - $d, $d, $d, 0, 90); $path.AddArc(0, $w - $d, $d, $d, 90, 90)
    $path.CloseFigure()
    $g.FillPath((New-Object Drawing.SolidBrush ([Drawing.Color]::FromArgb(88, 101, 242))), $path)
    # arco do túnel
    $pen = New-Object Drawing.Pen ([Drawing.Color]::White), ([single]($size * 0.11))
    $m = [single]($size * 0.22); $aw = [single]($size - 2 * $m)
    $g.DrawArc($pen, $m, $m + $size * 0.06, $aw, $aw * 1.1, 180, 180)
    $y0 = [single]($m + $size * 0.06 + $aw * 0.55); $y1 = [single]($size * 0.80)
    $g.DrawLine($pen, $m, $y0, $m, $y1); $g.DrawLine($pen, $m + $aw, $y0, $m + $aw, $y1)
    # ponto verde (ligado)
    $dd = [single]($size * 0.22)
    $g.FillEllipse((New-Object Drawing.SolidBrush ([Drawing.Color]::FromArgb(35, 165, 90))), ($size - $dd) / 2, $size * 0.50, $dd, $dd)
    $g.Dispose()
    $ms = New-Object IO.MemoryStream
    $bmp.Save($ms, [Drawing.Imaging.ImageFormat]::Png); $bmp.Dispose()
    return ,$ms.ToArray()
}
$sizes = 16, 24, 32, 48, 64, 256
$pngs  = foreach ($s in $sizes) { ,(New-IconPng $s) }
$fs = [IO.File]::Create($icon); $bw = New-Object IO.BinaryWriter $fs
$bw.Write([uint16]0); $bw.Write([uint16]1); $bw.Write([uint16]$sizes.Count)
$offset = 6 + 16 * $sizes.Count
for ($i = 0; $i -lt $sizes.Count; $i++) {
    $s = $sizes[$i]; $b = if ($s -ge 256) { 0 } else { $s }
    $bw.Write([byte]$b); $bw.Write([byte]$b); $bw.Write([byte]0); $bw.Write([byte]0)
    $bw.Write([uint16]1); $bw.Write([uint16]32); $bw.Write([uint32]$pngs[$i].Length); $bw.Write([uint32]$offset)
    $offset += $pngs[$i].Length
}
foreach ($p in $pngs) { $bw.Write($p) }
$bw.Close()

# --- Versão (do arquivo VERSION) ---
$objDir = Join-Path $root 'obj'
New-Item -ItemType Directory -Force $objDir | Out-Null
$verCs = Join-Path $objDir 'AssemblyVersion.cs'
Set-Content -LiteralPath $verCs -Encoding UTF8 -Value @"
[assembly: System.Reflection.AssemblyVersion("$version.0")]
[assembly: System.Reflection.AssemblyFileVersion("$version.0")]
[assembly: System.Reflection.AssemblyInformationalVersion("$version")]
"@

# --- Compilação ---
New-Item -ItemType Directory -Force (Split-Path $out) | Out-Null
& $csc /nologo /target:winexe /platform:anycpu /optimize+ /codepage:65001 `
    "/out:$out" "/win32icon:$icon" "/win32manifest:$(Join-Path $root 'src\app.manifest')" `
    "/resource:$setup,ProxiFyreSetup.exe" `
    /r:System.dll /r:System.Core.dll /r:System.Drawing.dll /r:System.Windows.Forms.dll `
    /r:System.ServiceProcess.dll /r:System.Web.Extensions.dll `
    (Join-Path $root 'src\FeliProxy.cs') $verCs
if ($LASTEXITCODE -ne 0) { throw "A compilação falhou (código $LASTEXITCODE)." }
"OK: $out v$version ($([math]::Round((Get-Item $out).Length / 1MB, 2)) MB)"
