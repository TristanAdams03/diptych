# Windows (pwsh) twin of gitea-attach.sh: attach one file to the Gitea pre-release for a tag,
# creating the release if it doesn't exist yet. Usage (TOKEN in the environment):
#   pwsh tools/release/gitea-attach.ps1 <repo API url> <tag> <web base for notes links> <file>
param([string]$Api, [string]$Tag, [string]$Web, [string]$File)
$ErrorActionPreference = "Stop"
$headers = @{ Authorization = "token $env:TOKEN" }
function Get-ReleaseId { (Invoke-RestMethod -Headers $headers -Uri "$Api/releases/tags/$Tag").id }
try { $id = Get-ReleaseId } catch {
    $notes = (Get-Content -Raw (Join-Path $PSScriptRoot "notes.md")).Replace("{TAG}", $Tag).Replace("{WEB}", $Web)
    $body = @{ tag_name = $Tag; name = $Tag; body = $notes; prerelease = $true; draft = $false } | ConvertTo-Json
    # The other job may create it at the same moment; a failed create is fine if the release now exists.
    try { Invoke-RestMethod -Headers $headers -Method Post -ContentType "application/json" -Uri "$Api/releases" -Body $body | Out-Null } catch { }
    $id = Get-ReleaseId
}
$name = Split-Path -Leaf $File
Invoke-RestMethod -Headers $headers -Method Post -Uri "$Api/releases/$id/assets?name=$name" -Form @{ attachment = Get-Item $File } | Out-Null
Write-Host "attached $name to release $Tag (id $id)"
