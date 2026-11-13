; Instalador do FeliProxy (Inno Setup 6) com o ProxiFyre junto.
; Compile com:  powershell -ExecutionPolicy Bypass -File installer\build-installer.ps1

#define AppName      "FeliProxy"
#ifndef AppVersion
  #define AppVersion "1.0.0"
#endif
#define AppExe       "FeliProxy.exe"
#define ProxiFyreSetup "ProxiFyre-2.6.4-win-x64-setup.exe"

[Setup]
AppId={{CB32A51C-A699-4DC2-8D2E-5B6B65357F84}
AppName={#AppName}
AppVersion={#AppVersion}
AppVerName={#AppName} {#AppVersion}
AppPublisher={#AppName}
DefaultDirName={autopf}\{#AppName}
DefaultGroupName={#AppName}
DisableProgramGroupPage=yes
PrivilegesRequired=admin
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
MinVersion=10.0
OutputDir=..\dist
OutputBaseFilename=FeliProxy-Setup-{#AppVersion}
SetupIconFile=..\src\app.ico
UninstallDisplayIcon={app}\{#AppExe}
UninstallDisplayName={#AppName}
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
CloseApplications=yes
RestartApplications=no
VersionInfoVersion={#AppVersion}
VersionInfoDescription=Instalador do {#AppName}

[Languages]
Name: "ptbr"; MessagesFile: "compiler:Languages\BrazilianPortuguese.isl"

[Tasks]
Name: "desktopicon"; Description: "Criar atalho na Área de Trabalho"; GroupDescription: "Atalhos:"

[Files]
Source: "..\dist\{#AppExe}"; DestDir: "{app}"; Flags: ignoreversion
Source: "THIRD-PARTY-NOTICES.txt"; DestDir: "{app}"; Flags: ignoreversion
Source: "..\vendor\{#ProxiFyreSetup}"; DestDir: "{tmp}"; Flags: dontcopy

[Icons]
Name: "{autoprograms}\{#AppName}"; Filename: "{app}\{#AppExe}"
Name: "{autodesktop}\{#AppName}"; Filename: "{app}\{#AppExe}"; Tasks: desktopicon

[Run]
Filename: "{app}\{#AppExe}"; Description: "Abrir o {#AppName} agora"; Flags: postinstall nowait skipifsilent shellexec

[UninstallRun]
Filename: "{sys}\taskkill.exe"; Parameters: "/IM {#AppExe} /F"; Flags: runhidden; RunOnceId: "KillApp"

[Code]
const
  ProxiFyreServiceKey = 'SYSTEM\CurrentControlSet\Services\ProxiFyreService';
  UninstallRoot = 'SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall';

function ProxiFyreInstalled: Boolean;
begin
  Result := RegKeyExists(HKLM64, ProxiFyreServiceKey);
end;

{ Instala o ProxiFyre (e o driver Windows Packet Filter) se ainda não estiver instalado }
procedure InstallProxiFyre;
var
  ResultCode: Integer;
begin
  if ProxiFyreInstalled then
  begin
    Log('ProxiFyre já instalado, pulando.');
    Exit;
  end;
  WizardForm.StatusLabel.Caption := 'Instalando o ProxiFyre e o driver Windows Packet Filter...';
  ExtractTemporaryFile('{#ProxiFyreSetup}');
  if not Exec(ExpandConstant('{tmp}\{#ProxiFyreSetup}'), '/passive /norestart', '',
              SW_SHOW, ewWaitUntilTerminated, ResultCode) then
    MsgBox('Não foi possível executar o instalador do ProxiFyre: ' + SysErrorMessage(ResultCode),
           mbError, MB_OK)
  else if (ResultCode <> 0) and (ResultCode <> 3010) then
    MsgBox('O instalador do ProxiFyre terminou com o código ' + IntToStr(ResultCode) + '.' + #13#10 +
           'Você pode tentar de novo pelo botão "Instalar ProxiFyre" dentro do FeliProxy.',
           mbError, MB_OK)
  else if ResultCode = 3010 then
    MsgBox('O ProxiFyre foi instalado, mas o Windows pede uma reinicialização antes de usar.',
           mbInformation, MB_OK);
end;

procedure CurStepChanged(CurStep: TSetupStep);
begin
  if CurStep = ssPostInstall then
    InstallProxiFyre;
end;

{ Procura o comando de desinstalação silenciosa do ProxiFyre (pacote principal) }
function FindProxiFyreUninstall(var Cmd: String): Boolean;
var
  Keys: TArrayOfString;
  I: Integer;
  Name, Quiet: String;
begin
  Result := False;
  if not RegGetSubkeyNames(HKLM64, UninstallRoot, Keys) then Exit;
  for I := 0 to GetArrayLength(Keys) - 1 do
  begin
    if RegQueryStringValue(HKLM64, UninstallRoot + '\' + Keys[I], 'DisplayName', Name) and
       (Name = 'ProxiFyre') and
       RegQueryStringValue(HKLM64, UninstallRoot + '\' + Keys[I], 'QuietUninstallString', Quiet) and
       (Quiet <> '') then
    begin
      Cmd := Quiet;
      Result := True;
      Exit;
    end;
  end;
end;

procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
var
  ResultCode: Integer;
  Cmd: String;
begin
  if CurUninstallStep <> usPostUninstall then Exit;
  if not ProxiFyreInstalled then Exit;

  { Desliga o proxy do Discord de qualquer forma }
  Exec(ExpandConstant('{sys}\sc.exe'), 'stop ProxiFyreService', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);

  if UninstallSilent then Exit;
  if MsgBox('Remover também o ProxiFyre e o driver Windows Packet Filter?' + #13#10 + #13#10 +
            'Escolha "Não" se você usa o ProxiFyre para outros programas.',
            mbConfirmation, MB_YESNO or MB_DEFBUTTON2) <> IDYES then Exit;

  if FindProxiFyreUninstall(Cmd) then
  begin
    if not Exec(ExpandConstant('{cmd}'), '/C "' + Cmd + '"', '', SW_HIDE, ewWaitUntilTerminated, ResultCode) then
      MsgBox('Não foi possível remover o ProxiFyre. Remova pelo "Adicionar ou remover programas".', mbError, MB_OK);
  end
  else
    MsgBox('Não encontrei o desinstalador do ProxiFyre. Remova pelo "Adicionar ou remover programas".', mbInformation, MB_OK);
end;
