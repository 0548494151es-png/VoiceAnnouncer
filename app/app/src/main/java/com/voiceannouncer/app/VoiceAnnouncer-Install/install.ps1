# VoiceAnnouncer - uploads the fixed files to GitHub in ONE commit, waits for the build, opens the release page.
$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$branch = 'main'

$repo = (Get-Content (Join-Path $here 'repo.txt') -Raw).Trim()
Write-Host "Repository: $repo"

# ---- token ----
$tokenFile = Join-Path $here 'token.txt'
if (Test-Path $tokenFile) {
    $token = (Get-Content $tokenFile -Raw).Trim()
} else {
    Write-Host ''
    Write-Host 'A GitHub token is needed (first time only).'
    Write-Host 'Create: GitHub > Settings > Developer settings > Personal access tokens > Fine-grained tokens'
    Write-Host 'Repository access: only VoiceAnnouncer. Permissions: Contents = Read and write, Actions = Read-only.'
    $token = (Read-Host 'Paste the token here').Trim()
    $save = Read-Host 'Save the token next to this file for next time? (Y/N)'
    if ($save -match '^[Yy]') { Set-Content -Path $tokenFile -Value $token -Encoding ASCII }
}
if (-not $token) { throw 'No token given.' }

$headers = @{
    Authorization          = "Bearer $token"
    Accept                 = 'application/vnd.github+json'
    'User-Agent'           = 'VoiceAnnouncer-Installer'
    'X-GitHub-Api-Version' = '2022-11-28'
}

function Gh([string]$method, [string]$path, $body = $null) {
    $uri = "https://api.github.com/repos/$repo$path"
    try {
        if ($null -ne $body) {
            $json = $body | ConvertTo-Json -Depth 10 -Compress
            return Invoke-RestMethod -Method $method -Uri $uri -Headers $headers -Body ([Text.Encoding]::UTF8.GetBytes($json)) -ContentType 'application/json'
        }
        return Invoke-RestMethod -Method $method -Uri $uri -Headers $headers
    } catch {
        $detail = ''
        try { $detail = (New-Object IO.StreamReader($_.Exception.Response.GetResponseStream())).ReadToEnd() } catch {}
        throw "GitHub request failed ($method $path): $($_.Exception.Message) $detail"
    }
}

# ---- files to upload (everything under .\app) ----
$appDir = Join-Path $here 'app'
$files = Get-ChildItem -Path $appDir -Recurse -File
if ($files.Count -eq 0) { throw 'No files found in the app folder.' }

Write-Host 'Reading current state of the repository...'
$ref = Gh 'GET' "/git/ref/heads/$branch"
$baseCommit = $ref.object.sha
$baseTree = (Gh 'GET' "/git/commits/$baseCommit").tree.sha

$entries = @()
foreach ($f in $files) {
    $rel = $f.FullName.Substring($here.Length).TrimStart('\', '/').Replace('\', '/')
    Write-Host "Uploading $rel"
    $b64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes($f.FullName))
    $blob = Gh 'POST' '/git/blobs' @{ content = $b64; encoding = 'base64' }
    $entries += @{ path = $rel; mode = '100644'; type = 'blob'; sha = $blob.sha }
}

$tree = Gh 'POST' '/git/trees' @{ base_tree = $baseTree; tree = $entries }
$commit = Gh 'POST' '/git/commits' @{
    message = 'Fix ring + SMS, choose extra apps, Bluetooth option, diagnostics log'
    tree    = $tree.sha
    parents = @($baseCommit)
}
Gh 'PATCH' "/git/refs/heads/$branch" @{ sha = $commit.sha } | Out-Null
Write-Host ''
Write-Host "Done: files committed ($($commit.sha.Substring(0,7))). The build starts automatically."

# ---- wait for the build ----
Write-Host 'Waiting for the build (usually 3-5 minutes)...'
$deadline = (Get-Date).AddMinutes(20)
$result = $null
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Seconds 15
    $runs = Gh 'GET' "/actions/runs?head_sha=$($commit.sha)"
    $run = $runs.workflow_runs | Where-Object { $_.name -eq 'Build APK' } | Select-Object -First 1
    if ($null -eq $run) { Write-Host '  build not started yet...'; continue }
    Write-Host "  status: $($run.status)"
    if ($run.status -eq 'completed') { $result = $run.conclusion; break }
}

if ($result -eq 'success') {
    Write-Host ''
    Write-Host 'BUILD SUCCEEDED.'
    Write-Host 'On the phone: open the app > "Check for update" > "Download and install".'
    Start-Process "https://github.com/$repo/releases/latest"
} elseif ($null -eq $result) {
    Write-Host 'The build is taking longer than expected. Check the Actions tab on GitHub.'
    Start-Process "https://github.com/$repo/actions"
} else {
    Write-Host "BUILD FAILED ($result). Opening the Actions page - send me a screenshot of the error."
    Start-Process "https://github.com/$repo/actions"
}
