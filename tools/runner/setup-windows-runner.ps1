# Sets up a Windows machine as a Gitea Actions runner for the Diptych mod build
# (jobs with runs-on: windows-msvc). Run once, in an elevated PowerShell:
#
#   Set-ExecutionPolicy -Scope Process Bypass
#   .\setup-windows-runner.ps1 -Token <registration token>
#
# Get the token in Gitea: Site Administration -> Actions -> Runners -> Create new runner.
# Installs: VS 2022 Build Tools (C++ workload), CMake, Git, PowerShell 7, Node.js LTS (for JS actions),
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

function Install-Winget([string]$Id, [string]$Override = "") {
    $have = winget list --id $Id -e --accept-source-agreements 2>$null | Select-String -SimpleMatch $Id
    if ($have) { Write-Host "ok   $Id already installed"; return }
    Write-Host "get  $Id"
    $wargs = @("install", "--id", $Id, "-e", "--silent", "--accept-package-agreements", "--accept-source-agreements")
    if ($Override) { $wargs += @("--override", $Override) }
    winget @wargs
    if ($LASTEXITCODE -ne 0) { throw "winget install $Id failed ($LASTEXITCODE)" }
}

Install-Winget "Microsoft.VisualStudio.2022.BuildTools" `
    "--quiet --wait --norestart --add Microsoft.VisualStudio.Workload.VCTools --includeRecommended"
Install-Winget "Kitware.CMake"
Install-Winget "Git.Git"
Install-Winget "Microsoft.PowerShell"
Install-Winget "OpenJS.NodeJS.LTS"

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

# Start the runner at boot as SYSTEM, restarting if it stops.
$taskName = "Gitea act_runner"
$action = New-ScheduledTaskAction -Execute $exe -Argument "daemon" -WorkingDirectory $Dir
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
