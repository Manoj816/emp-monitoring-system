; EmpVerify agent — single-file installer (Inno Setup 6).
; Wraps the signed MSIX, the dev certificate and Install-PerMachine.ps1 into one
; EmpVerify-Agent-Setup-<version>.exe that an administrator double-clicks on the
; employee's PC. Built by build-setup-exe.ps1, which passes the defines below.

#ifndef Version
  #error Pass /DVersion=x.x.x.x
#endif
#ifndef Msix
  #error Pass /DMsix=<full path to the .msix>
#endif
#ifndef MsixName
  #error Pass /DMsixName=<file name of the .msix>
#endif
#ifndef Cert
  #define Cert ""
#endif
#ifndef CertName
  #define CertName ""
#endif
#ifndef BackendUrl
  #define BackendUrl ""
#endif
#ifndef Token
  #define Token ""
#endif
#ifndef OutDir
  #define OutDir "out"
#endif

[Setup]
AppId={{7C2B1D4E-6A33-4F0E-9B8D-2E5F1A7C9D21}
AppName=EmpVerify Agent
AppVersion={#Version}
AppVerName=EmpVerify Agent {#Version}
AppPublisher=EmpVerify
DefaultDirName={autopf}\EmpVerify Agent
DisableDirPage=yes
DisableProgramGroupPage=yes
DisableReadyPage=no
PrivilegesRequired=admin
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
MinVersion=10.0.17763
OutputDir={#OutDir}
OutputBaseFilename=EmpVerify-Agent-Setup-{#Version}
Compression=lzma2/fast
SolidCompression=yes
WizardStyle=modern
SetupLogging=yes
UninstallDisplayName=EmpVerify Agent
UninstallDisplayIcon={sys}\shell32.dll,43

[Files]
Source: "{#Msix}"; DestDir: "{app}"; Flags: ignoreversion
#if Cert != ""
Source: "{#Cert}"; DestDir: "{app}"; Flags: ignoreversion
#endif
Source: "Install-PerMachine.ps1"; DestDir: "{app}"; Flags: ignoreversion
Source: "Uninstall.ps1"; DestDir: "{app}"; Flags: ignoreversion
Source: "INSTALL.md"; DestDir: "{app}"; Flags: ignoreversion

[UninstallRun]
Filename: "{sys}\WindowsPowerShell\v1.0\powershell.exe"; Parameters: "-NoProfile -ExecutionPolicy Bypass -File ""{app}\Uninstall.ps1"""; Flags: runhidden waituntilterminated; RunOnceId: "RemoveEmpVerifyAgent"

[Code]
var
  ProvPage: TInputQueryWizardPage;

procedure InitializeWizard;
begin
  ProvPage := CreateInputQueryPage(wpWelcome,
    'Connect this PC to EmpVerify', 'Backend address and provisioning token',
    'Both values come from your EmpVerify administrator. The token lets this PC register itself; ' +
    'leave it empty only if this PC was provisioned before.');
  ProvPage.Add('Backend URL:', False);
  ProvPage.Add('Provisioning token:', True);
  ProvPage.Values[0] := '{#BackendUrl}';
  ProvPage.Values[1] := '{#Token}';
end;

function NextButtonClick(CurPageID: Integer): Boolean;
begin
  Result := True;
  if (CurPageID = ProvPage.ID) and (Trim(ProvPage.Values[0]) = '') then
  begin
    MsgBox('Enter the backend URL (for example http://empverify-alb-....elb.amazonaws.com).', mbError, MB_OK);
    Result := False;
  end;
end;

function UpdateReadyMemo(Space, NewLine, MemoUserInfoInfo, MemoDirInfo, MemoTypeInfo,
  MemoComponentsInfo, MemoGroupInfo, MemoTasksInfo: String): String;
begin
  Result := 'The installer will:' + NewLine +
    Space + 'check this PC (Windows 10 1809+ / 11, 64-bit)' + NewLine +
    Space + 'trust the package certificate and install the WebView2 runtime if missing' + NewLine +
    Space + 'store the backend address and provisioning token' + NewLine +
    Space + 'install the EmpVerify agent for every user and start it' + NewLine + NewLine +
    'Backend URL: ' + ProvPage.Values[0] + NewLine +
    'Provisioning token: ' + Copy(StringOfChar('*', Length(ProvPage.Values[1])), 1, 12);
end;

function PsQuote(const S: String): String;
begin
  Result := '''' + StringChange(S, '''', '''''') + '''';
end;

procedure CurStepChanged(CurStep: TSetupStep);
var
  Script, Params, LogFile: String;
  ResultCode: Integer;
begin
  if CurStep <> ssPostInstall then Exit;

  Script := ExpandConstant('{app}\Install-PerMachine.ps1');
  LogFile := ExpandConstant('{app}\install.log');
  Params := '& ' + PsQuote(Script)
    + ' -MsixPath ' + PsQuote(ExpandConstant('{app}\{#MsixName}'))
    + ' -NoLaunch';
  if '{#CertName}' <> '' then
    Params := Params + ' -TrustCert ' + PsQuote(ExpandConstant('{app}\{#CertName}'));
  Params := Params + ' -BackendUrl ' + PsQuote(Trim(ProvPage.Values[0]));
  if Trim(ProvPage.Values[1]) <> '' then
    Params := Params + ' -ProvisioningToken ' + PsQuote(Trim(ProvPage.Values[1]));
  Params := Params + ' *> ' + PsQuote(LogFile) + '; exit $LASTEXITCODE';

  WizardForm.StatusLabel.Caption := 'Installing the EmpVerify agent (this takes a minute)...';
  if not Exec(ExpandConstant('{sys}\WindowsPowerShell\v1.0\powershell.exe'),
      '-NoProfile -ExecutionPolicy Bypass -Command "' + Params + '"',
      '', SW_HIDE, ewWaitUntilTerminated, ResultCode) then
    ResultCode := -1;

  if ResultCode <> 0 then
    MsgBox('The agent could not be installed (exit code ' + IntToStr(ResultCode) + ').' + #13#10 +
      'Details: ' + LogFile, mbError, MB_OK)
  else
    // Start the agent as the signed-in user (not elevated) now; it also starts at every sign-in.
    Exec(ExpandConstant('{sys}\WindowsPowerShell\v1.0\powershell.exe'),
      '-NoProfile -Command "$p = Get-AppxPackage -Name EmpVerify.Agent; if ($p) { Start-Process explorer.exe (''shell:AppsFolder\'' + $p.PackageFamilyName + ''!EmpVerifyAgent'') }"',
      '', SW_HIDE, ewNoWait, ResultCode);
end;
