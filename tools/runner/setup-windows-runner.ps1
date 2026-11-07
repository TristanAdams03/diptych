# Sets up a Windows machine as a Gitea Actions runner for the Diptych mod build
# (jobs with runs-on: windows-msvc). Run once, in an elevated PowerShell:
#
#   Set-ExecutionPolicy -Scope Process Bypass
#   .\setup-windows-runner.ps1 -Token <registration token>
#
# Get the token in Gitea: Site Administration -> Actions -> Runners -> Create new runner.
# Installs (direct downloads): VS 2022 Build Tools (C++ workload), CMake, Git, PowerShell 7, Node.js 20 (for JS actions),
# and Gitea's act_runner as a startup task. Safe to re-run: installed tools are skipped and an
# existing registration is kept.
param(
    [Parameter(Mandatory = $true)][string]$Token,
    [string]$GiteaUrl = "http://192.168.0.186:3001",
    [string]$RunnerName = "fantastic-runner-win",
    [string]$RunnerVersion = "0.2.11",
    [string]$Dir = "C:\act_runner"
)
$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"   # Invoke-WebRequest is far faster without the progress bar
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

# Direct downloads from each project's official release URL. winget is not used: it is missing or
# unusable under SYSTEM (the guest agent's account) on a fresh Windows 10.
$dl = Join-Path $env:TEMP "runner-setup"
New-Item -ItemType Directory -Force -Path $dl | Out-Null

function Install-From([string]$Name, [string]$Check, [string]$Url, [string]$File, [string[]]$InstallArgs) {
    if (Test-Path $Check) { Write-Host "ok   $Name already installed"; return }
    $path = Join-Path $dl $File
    Write-Host "get  $Name  $Url"
    Invoke-WebRequest -Uri $Url -OutFile $path -UseBasicParsing
    Write-Host "run  $Name"
    if ($File -like "*.msi") {
        $p = Start-Process msiexec.exe -ArgumentList (@("/i", "`"$path`"", "/qn", "/norestart") + $InstallArgs) -Wait -PassThru
    } else {
        $p = Start-Process $path -ArgumentList $InstallArgs -Wait -PassThru
    }
    # 3010 = success, reboot required
    if ($p.ExitCode -ne 0 -and $p.ExitCode -ne 3010) { throw "$Name installer exited $($p.ExitCode)" }
    Write-Host "ok   $Name (exit $($p.ExitCode))"
}

Install-From "VS 2022 Build Tools (C++)" "${env:ProgramFiles(x86)}\Microsoft Visual Studio\2022\BuildTools\VC\Tools\MSVC" `
    "https://aka.ms/vs/17/release/vs_BuildTools.exe" "vs_BuildTools.exe" `
    @("--quiet", "--wait", "--norestart", "--nocache", "--add", "Microsoft.VisualStudio.Workload.VCTools", "--includeRecommended")
Install-From "CMake" "C:\Program Files\CMake\bin\cmake.exe" `
    "https://github.com/Kitware/CMake/releases/download/v3.31.6/cmake-3.31.6-windows-x86_64.msi" "cmake.msi" `
    @("ADD_CMAKE_TO_PATH=System")
Install-From "Git" "C:\Program Files\Git\cmd\git.exe" `
    "https://github.com/git-for-windows/git/releases/download/v2.47.1.windows.2/Git-2.47.1.2-64-bit.exe" "git.exe" `
    @("/VERYSILENT", "/NORESTART", "/NOCANCEL", "/SP-")
Install-From "PowerShell 7" "C:\Program Files\PowerShell\7\pwsh.exe" `
    "https://github.com/PowerShell/PowerShell/releases/download/v7.4.6/PowerShell-7.4.6-win-x64.msi" "pwsh.msi" `
    @("ADD_PATH=1")
Install-From "Node.js 20" "C:\Program Files\nodejs\node.exe" `
    "https://nodejs.org/dist/v20.18.1/node-v20.18.1-x64.msi" "node.msi" @()

# Make the new tools visible to this session and to the runner (machine PATH).
$machinePath = [Environment]::GetEnvironmentVariable("Path", "Machine")
foreach ($p in @("C:\Program Files\CMake\bin", "C:\Program Files\Git\cmd", "C:\Program Files\PowerShell\7", "C:\Program Files\nodejs")) {
    if ((Test-Path $p) -and ($machinePath -notlike "*$p*")) { $machinePath = "$machinePath;$p" }
}
[Environment]::SetEnvironmentVariable("Path", $machinePath, "Machine")
$env:Path = $machinePath

New-Item -ItemType Directory -Force -Path $Dir | Out-Null
$exe = Join-Path $Dir "act_runner.exe"
if (-not (Test-Path $exe)) {
    $url = "https://dl.gitea.com/act_runner/$RunnerVersion/act_runner-$RunnerVersion-windows-amd64.exe"
    Write-Host "get  $url"
    Invoke-WebRequest -Uri $url -OutFile $exe -UseBasicParsing
}

Push-Location $Dir
try {
    if (-not (Test-Path ".runner")) {
        & $exe register --no-interactive --instance $GiteaUrl --token $Token --name $RunnerName `
            --labels "windows-msvc:host"
        if ($LASTEXITCODE -ne 0) { throw "act_runner register failed ($LASTEXITCODE)" }
    } else {
        Write-Host "ok   already registered (.runner exists)"
    }
} finally { Pop-Location }

# Labels live in config.yaml so they can change without re-registering. windows-msvc is Diptych's;
# windows-latest / windows-2025 let other repos (RefinedEmulation) use this machine too.
Set-Content -Encoding ascii -Path (Join-Path $Dir "config.yaml") -Value @(
    "log:", "  level: info", "runner:", "  file: .runner", "  capacity: 1", "  timeout: 3h", "  labels:",
    "    - windows-msvc:host", "    - windows-latest:host", "    - windows-2025:host")

# Start the runner at boot as SYSTEM, restarting if it stops.
$taskName = "Gitea act_runner"
$action = New-ScheduledTaskAction -Execute $exe -Argument "daemon -c config.yaml" -WorkingDirectory $Dir
$trigger = New-ScheduledTaskTrigger -AtStartup
$settings = New-ScheduledTaskSettingsSet -RestartCount 999 -RestartInterval (New-TimeSpan -Minutes 1) `
    -ExecutionTimeLimit ([TimeSpan]::Zero) -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries
$principal = New-ScheduledTaskPrincipal -UserId "SYSTEM" -LogonType ServiceAccount -RunLevel Highest
Register-ScheduledTask -TaskName $taskName -Action $action -Trigger $trigger -Settings $settings `
    -Principal $principal -Force | Out-Null
Start-ScheduledTask -TaskName $taskName
Start-Sleep -Seconds 5
$state = (Get-ScheduledTask -TaskName $taskName).State
Write-Host "runner task: $state"
Write-Host "Done. In Gitea, Site Administration -> Actions -> Runners should list '$RunnerName' as online."
