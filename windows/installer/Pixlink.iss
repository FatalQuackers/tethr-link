; Pixlink for Windows installer (Inno Setup 6).
; Installs for the current user only, so it never asks for admin rights.
; CI sets PIXLINK_VERSION and APP_DIR (the built app folder).

#define AppVersion GetEnv("PIXLINK_VERSION")
#define AppDir GetEnv("APP_DIR")

[Setup]
AppId={{8F3C2B1A-6D4E-4C7B-9A21-5E0F1C2D3B4A}
AppName=Tethr Link
AppVersion={#AppVersion}
AppVerName=Tethr Link {#AppVersion}
AppPublisher=Tethr Link (open source)
AppPublisherURL=https://fatalquackers.github.io/tethr-link
AppSupportURL=https://github.com/FatalQuackers/tethr-link/issues
AppUpdatesURL=https://github.com/FatalQuackers/tethr-link/releases
DefaultDirName={localappdata}\Programs\Pixlink
DefaultGroupName=Tethr Link
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
OutputDir=.
OutputBaseFilename=TethrLink-Setup-x64
SetupIconFile=..\Pixlink\Assets\Pixlink.ico
UninstallDisplayIcon={app}\Pixlink.exe
UninstallDisplayName=Tethr Link
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
MinVersion=10.0.17763
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
CloseApplications=force
RestartApplications=no

[Tasks]
Name: desktopicon; Description: "Create a &desktop shortcut"; GroupDescription: "Shortcuts:"
Name: startup; Description: "&Start Tethr Link when I sign in to Windows"; GroupDescription: "Startup:"

[Files]
Source: "{#AppDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{userprograms}\Tethr Link"; Filename: "{app}\Pixlink.exe"; Comment: "Your phone, on your PC"
Name: "{userdesktop}\Tethr Link"; Filename: "{app}\Pixlink.exe"; Tasks: desktopicon

[Registry]
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; ValueType: string; ValueName: "Pixlink"; ValueData: """{app}\Pixlink.exe"" --background"; Tasks: startup

[Run]
Filename: "{app}\Pixlink.exe"; Description: "Open Tethr Link"; Flags: nowait postinstall skipifsilent

[UninstallRun]
Filename: "{sys}\taskkill.exe"; Parameters: "/IM Pixlink.exe /F"; Flags: runhidden; RunOnceId: "StopPixlink"
Filename: "{sys}\reg.exe"; Parameters: "delete HKCU\Software\Microsoft\Windows\CurrentVersion\Run /v Pixlink /f"; Flags: runhidden; RunOnceId: "RemoveStartup"

[UninstallDelete]
Type: filesandordirs; Name: "{app}"

[Code]
// Settings and pairing live in %LOCALAPPDATA%\Pixlink. Tethr Link's own "Uninstall" button leaves a
// note saying whether to delete them; uninstalling from Windows Settings asks instead.
procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
var
  DataDir: String;
  DeleteData: Boolean;
begin
  if CurUninstallStep = usPostUninstall then
  begin
    DataDir := ExpandConstant('{localappdata}\Pixlink');
    if not DirExists(DataDir) then Exit;
    if FileExists(DataDir + '\delete-on-uninstall') then
      DeleteData := True
    else if FileExists(DataDir + '\keep-on-uninstall') then
      DeleteData := False
    else if UninstallSilent then
      DeleteData := False
    else
      DeleteData := MsgBox('Also delete your Tethr Link settings and phone pairing?' #13#10#13#10 +
        'Choose No if you plan to install Tethr Link again and want to keep them.',
        mbConfirmation, MB_YESNO or MB_DEFBUTTON2) = IDYES;
    if DeleteData then
      DelTree(DataDir, True, True, True)
    else
    begin
      DeleteFile(DataDir + '\keep-on-uninstall');
    end;
  end;
end;
