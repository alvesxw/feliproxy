# FeliProxy - liga e desliga o ProxiFyre (proxy só para o Discord).
# Pede permissão de administrador (UAC) uma vez ao abrir, porque ligar/desligar
# um serviço do Windows exige isso.

$ServiceName  = 'ProxiFyreService'
$ConfigPath   = 'C:\Program Files\ProxiFyre\app-config.json'
$DiscordNames = @('Discord', 'DiscordPTB', 'DiscordCanary')

# --- Elevação: reabre o script como administrador -------------------------
$principal = New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    try {
        Start-Process powershell.exe -Verb RunAs -WindowStyle Hidden -ArgumentList @(
            '-NoProfile', '-ExecutionPolicy', 'Bypass', '-WindowStyle', 'Hidden', '-File', "`"$PSCommandPath`"")
    } catch { }   # UAC cancelado: só sai
    exit
}

# --- Uma janela só por vez ------------------------------------------------
$created = $false
$mutex = New-Object System.Threading.Mutex($true, 'Global\FeliProxyToggleUI', [ref]$created)
if (-not $created) { exit }

Add-Type -AssemblyName System.Windows.Forms, System.Drawing
[System.Windows.Forms.Application]::EnableVisualStyles()

# --- Funções --------------------------------------------------------------
function Get-TunnelState {
    $svc = Get-Service -Name $ServiceName -ErrorAction SilentlyContinue
    if (-not $svc) { return 'Missing' }
    return $svc.Status.ToString()
}

function Get-ProxyInfo {
    try {
        $cfg = Get-Content -LiteralPath $ConfigPath -Raw | ConvertFrom-Json
        $p = $cfg.proxies[0]
        return "Proxy: $($p.socks5ProxyEndpoint)   ($((@($p.supportedProtocols)) -join ' + '))"
    } catch {
        return 'Proxy: configuração não encontrada'
    }
}

function Find-DiscordShortcut([string]$name) {
    $roots = @("$env:APPDATA\Microsoft\Windows\Start Menu\Programs",
               "$env:ProgramData\Microsoft\Windows\Start Menu\Programs")
    foreach ($r in $roots) {
        $lnk = Get-ChildItem -LiteralPath $r -Recurse -Filter "$name.lnk" -ErrorAction SilentlyContinue |
               Select-Object -First 1
        if ($lnk) { return $lnk.FullName }
    }
    return $null
}

function Restart-Discord {
    $procs = @(Get-Process -Name $DiscordNames -ErrorAction SilentlyContinue)
    if ($procs.Count -eq 0) { return $false }
    $running = $procs | Select-Object -ExpandProperty Name -Unique
    $procs | Stop-Process -Force -ErrorAction SilentlyContinue
    Start-Sleep -Milliseconds 1500
    foreach ($n in $running) {
        $lnk = Find-DiscordShortcut $n
        # Abre pelo explorer para o Discord NÃO rodar como administrador.
        if ($lnk) { Start-Process explorer.exe -ArgumentList "`"$lnk`"" }
    }
    return $true
}

# --- Cores (estilo Discord) -----------------------------------------------
$bg      = [Drawing.Color]::FromArgb(49, 51, 56)
$fg      = [Drawing.Color]::FromArgb(242, 243, 245)
$muted   = [Drawing.Color]::FromArgb(181, 186, 193)
$green   = [Drawing.Color]::FromArgb(35, 165, 90)
$red     = [Drawing.Color]::FromArgb(218, 55, 60)
$blurple = [Drawing.Color]::FromArgb(88, 101, 242)
$gray    = [Drawing.Color]::FromArgb(128, 132, 142)

# --- Janela ---------------------------------------------------------------
$form = New-Object Windows.Forms.Form
$form.Text            = 'FeliProxy'
$form.ClientSize      = New-Object Drawing.Size(340, 210)
$form.FormBorderStyle = 'FixedSingle'
$form.MaximizeBox     = $false
$form.StartPosition   = 'CenterScreen'
$form.BackColor       = $bg
$form.ForeColor       = $fg
$form.Font            = New-Object Drawing.Font('Segoe UI', 10)
try { $form.Icon = [Drawing.Icon]::ExtractAssociatedIcon("$env:LOCALAPPDATA\Discord\Update.exe") } catch { }

$dot = New-Object Windows.Forms.Label
$dot.Text     = [string][char]0x25CF
$dot.Font     = New-Object Drawing.Font('Segoe UI', 22)
$dot.AutoSize = $true
$dot.Location = New-Object Drawing.Point(18, 10)
$form.Controls.Add($dot)

$status = New-Object Windows.Forms.Label
$status.Font     = New-Object Drawing.Font('Segoe UI Semibold', 15)
$status.AutoSize = $true
$status.Location = New-Object Drawing.Point(56, 20)
$form.Controls.Add($status)

$info = New-Object Windows.Forms.Label
$info.ForeColor = $muted
$info.AutoSize  = $true
$info.Location  = New-Object Drawing.Point(22, 62)
$form.Controls.Add($info)

$button = New-Object Windows.Forms.Button
$button.Size      = New-Object Drawing.Size(296, 44)
$button.Location  = New-Object Drawing.Point(22, 94)
$button.FlatStyle = 'Flat'
$button.FlatAppearance.BorderSize = 0
$button.ForeColor = [Drawing.Color]::White
$button.Font      = New-Object Drawing.Font('Segoe UI Semibold', 11)
$button.Cursor    = [Windows.Forms.Cursors]::Hand
$form.Controls.Add($button)

$restart = New-Object Windows.Forms.CheckBox
$restart.Text      = 'Reiniciar o Discord ao ligar/desligar'
$restart.Checked   = $true
$restart.AutoSize  = $true
$restart.ForeColor = $muted
$restart.Location  = New-Object Drawing.Point(22, 150)
$form.Controls.Add($restart)

$hint = New-Object Windows.Forms.Label
$hint.ForeColor = $gray
$hint.Font      = New-Object Drawing.Font('Segoe UI', 8.5)
$hint.AutoSize  = $true
$hint.Location  = New-Object Drawing.Point(22, 180)
$form.Controls.Add($hint)

$script:busy = $false

function Update-UI {
    if ($script:busy) { return }
    $info.Text = Get-ProxyInfo
    switch (Get-TunnelState) {
        'Running' {
            $dot.ForeColor = $green;  $status.Text = 'Ativado'
            $button.Text = 'Desativar'; $button.BackColor = $red; $button.Enabled = $true
        }
        'Stopped' {
            $dot.ForeColor = $gray;   $status.Text = 'Desativado'
            $button.Text = 'Ativar';  $button.BackColor = $blurple; $button.Enabled = $true
        }
        'Missing' {
            $dot.ForeColor = $red;    $status.Text = 'ProxiFyre não instalado'
            $button.Text = '-'; $button.BackColor = $gray; $button.Enabled = $false
        }
        default {
            $dot.ForeColor = $gray;   $status.Text = 'Aguarde...'
            $button.Enabled = $false
        }
    }
}

$button.Add_Click({
    $script:busy   = $true
    $button.Enabled = $false
    $wasRunning    = (Get-TunnelState) -eq 'Running'
    $status.Text   = if ($wasRunning) { 'Desativando...' } else { 'Ativando...' }
    $hint.Text     = ''
    $form.Refresh()
    try {
        if ($wasRunning) { Stop-Service  -Name $ServiceName -Force -ErrorAction Stop }
        else             { Start-Service -Name $ServiceName -ErrorAction Stop }
        if ($restart.Checked) {
            $status.Text = 'Reiniciando o Discord...'; $form.Refresh()
            if (Restart-Discord) { $hint.Text = 'Discord reiniciado.' }
        } else {
            $hint.Text = 'Reinicie o Discord para valer nas conexões abertas.'
        }
    } catch {
        [Windows.Forms.MessageBox]::Show("Não foi possível mudar o serviço:`n$($_.Exception.Message)",
            'FeliProxy', 'OK', 'Error') | Out-Null
    }
    $script:busy = $false
    Update-UI
})

$timer = New-Object Windows.Forms.Timer
$timer.Interval = 2000
$timer.Add_Tick({ Update-UI })
$timer.Start()

Update-UI
[void]$form.ShowDialog()
$timer.Stop()
$mutex.ReleaseMutex()
