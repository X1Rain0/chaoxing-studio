param(
  [string]$Token = $env:GITHUB_TOKEN,
  [string]$Owner = 'X1Rain0',
  [string]$Repo = 'chaoxing-studio',
  [string]$Branch = 'main',
  [string]$Message = '',
  [switch]$DryRun,
  [switch]$IncludeWorkflows
)

# 增量同步：把本地有变化的文件用 Contents API 推上去（不依赖 Git Data API）
#
# 【备用脚本】仓库已由 git 接管：origin/main 有真实历史，凭据来自 Windows 凭据管理器，
# 日常改动用 `git add -A && git commit && git push` 即可。本脚本只适用于没有 git 的环境。
# 注意：它按文件逐个上传、只读工作区，曾被仓库根 .gitignore 的裸 `data/` 规则连累——
# 那条规则把 android/.../chaoxingstudio/data/ 整包忽略掉，REST 同步于是把 Kotlin data 包
# 漏在公共仓库之外，克隆下来无法编译。该规则已改为 `/data/`，同步前先确认要传的文件在列。
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$api = 'https://api.github.com'

$skipDir  = @('.git', 'build\bin', 'build\windows\installer', 'node_modules', 'data', 'webview-data', '.hallmark')
$skipFile = @('*.exe', '*.dll', '*.syso', '*.log', '*.tmp', 'tree-debug.json', 'build\shot-*.png', 'build\picker*.png', 'build\v[0-9]*-*.png')

# classic PAT 没有 workflow scope 时，GitHub 会对着 .github/workflows/ 下的写入返回 404
# （Git Data API 的 tree 提交同理）。所以默认跳过，需要时用 -IncludeWorkflows 显式打开。
$skipWorkflows = -not $IncludeWorkflows

function Get-GitBlobSha([byte[]]$bytes) {
  $header = [Text.Encoding]::UTF8.GetBytes("blob $($bytes.Length)`0")
  $buf = New-Object byte[] ($header.Length + $bytes.Length)
  [Array]::Copy($header, 0, $buf, 0, $header.Length)
  [Array]::Copy($bytes, 0, $buf, $header.Length, $bytes.Length)
  $h = [Security.Cryptography.SHA1]::Create()
  (-join ($h.ComputeHash($buf) | ForEach-Object { $_.ToString('x2') }))
}

function Invoke-Curl {
  param([string]$Method, [string]$Url, $Body, [string]$ContentType = 'application/json')
  $curlArgs = @('--silent', '--show-error', '--location', '--http1.1', '--max-time', '300', '--connect-timeout', '25',
    '--retry', '3', '--retry-all-errors', '--retry-delay', '1',
    '--request', $Method,
    '--header', "Authorization: Bearer $Token",
    '--header', 'Accept: application/vnd.github+json',
    '--header', 'X-GitHub-Api-Version: 2022-11-28',
    '--header', 'User-Agent: chaoxing-studio-sync')
  $tmp = $null
  if ($null -ne $Body) {
    $tmp = Join-Path $env:TEMP ('sync-' + [guid]::NewGuid().ToString('N') + '.json')
    [IO.File]::WriteAllText($tmp, (ConvertTo-Json $Body -Depth 8 -Compress), (New-Object Text.UTF8Encoding($false)))
    $curlArgs += @('--header', "Content-Type: $ContentType", '--data-binary', "@$tmp")
  }
  $resp = Join-Path $env:TEMP ('syncresp-' + [guid]::NewGuid().ToString('N') + '.json')
  $curlArgs += @('--output', $resp, '--write-out', '%{http_code}', $Url)
  try {
    $code = (& curl.exe @curlArgs).Trim()
    $text = if (Test-Path $resp) { [IO.File]::ReadAllText($resp) } else { '' }
    if ($code -match '^2\d\d$') { if ($text.Trim()) { return ($text | ConvertFrom-Json) } else { return $null } }
    throw "HTTP $code $Method $Url`n$text"
  } finally {
    foreach ($p in @($tmp, $resp)) { if ($p -and (Test-Path $p)) { Remove-Item $p -Force -ErrorAction SilentlyContinue } }
  }
}

# ---------- 远端现状 ----------
Write-Host '· 读取远端文件列表 …' -ForegroundColor Cyan
$tree = Invoke-Curl GET "$api/repos/$Owner/$Repo/git/trees/$Branch`?recursive=1"
$remote = @{}
foreach ($n in $tree.tree) { if ($n.type -eq 'blob') { $remote[$n.path] = $n.sha } }
Write-Host "  → 远端 $($remote.Count) 个文件" -ForegroundColor DarkGray

# ---------- 本地文件 ----------
$files = @(Get-ChildItem -Path $root -Recurse -File | Where-Object {
    $rel = $_.FullName.Substring($root.Length + 1)
    if ($skipFile | Where-Object { $rel -like $_ }) { return $false }
    foreach ($d in $skipDir) { if ($rel -like ($d + '\*')) { return $false } }
    if ($skipWorkflows -and (($rel -replace '\\', '/') -like '.github/workflows/*')) { return $false }
    if ($_.Length -gt 40MB) { return $false }
    return $true
  })

$changed = @()
foreach ($f in $files) {
  $rel = $f.FullName.Substring($root.Length + 1) -replace '\\', '/'
  $bytes = [IO.File]::ReadAllBytes($f.FullName)
  $sha = Get-GitBlobSha $bytes
  if ($remote.ContainsKey($rel) -and $remote[$rel] -eq $sha) { continue }
  $changed += [pscustomobject]@{ Rel = $rel; Bytes = $bytes; Sha = $sha; Exists = $remote.ContainsKey($rel) }
}

if (-not $changed) { Write-Host '已是最新，无需上传。' -ForegroundColor Green; return }
Write-Host "· 需要上传 $($changed.Count) 个文件：" -ForegroundColor Cyan
$changed | ForEach-Object { "  " + $(if ($_.Exists) { 'M' } else { 'A' }) + " $($_.Rel)" }

if ($DryRun) { return }

# ---------- 上传 ----------
$i = 0
foreach ($c in $changed) {
  $i++
  $enc = ($c.Rel -split '/' | ForEach-Object { [uri]::EscapeDataString($_) }) -join '/'
  $body = @{
    message = if ($Message) { $Message } else { "$(if ($c.Exists) { 'update' } else { 'add' }): $($c.Rel)" }
    content = [Convert]::ToBase64String($c.Bytes)
    branch  = $Branch
  }
  if ($c.Exists) { $body.sha = $remote[$c.Rel] }
  $null = Invoke-Curl PUT "$api/repos/$Owner/$Repo/contents/$enc" $body
  Write-Host "  ✓ $i/$($changed.Count)  $($c.Rel)" -ForegroundColor DarkGray
}

Write-Host ''
Write-Host "已同步 $($changed.Count) 个文件 → https://github.com/$Owner/$Repo" -ForegroundColor Green

