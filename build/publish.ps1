<#
  把当前项目直接推到 GitHub —— 只用 REST API + 系统自带 curl.exe，不需要安装 git。

  用法：
    $env:GITHUB_TOKEN = 'ghp_xxx'
    .\build\publish.ps1 -Repo chaoxing-studio            # 默认私有
    .\build\publish.ps1 -Repo chaoxing-studio -Public    # 公开
    .\build\publish.ps1 -DryRun                          # 只列要上传的文件

  Token 权限：
    · classic PAT  → public_repo（公开库）或 repo（私有库）
    · fine-grained → Contents: Read and write；新建仓库还需 Administration: Read and write

  【备用脚本】仓库已由 git 接管（真实历史 + Windows 凭据管理器里的 https 凭据），
  日常改动用 `git add -A && git commit && git push` 就够，不需要 PAT。只有在本机没有
  git、或要让脚本代建仓库时才用本脚本；它只按工作区现有文件上传，不携带历史。
#>
[CmdletBinding()]
param(
  [string]$Token = $env:GITHUB_TOKEN,
  [string]$Repo = 'chaoxing-studio',
  [switch]$Public,
  [string]$Branch = 'main',
  [string]$Description = '学习通签到工坊 — Windows 桌面版超星学习通签到助手（Go + Wails v2，纸感点名册界面，零前端依赖）',
  [string]$Message = 'feat: 学习通签到工坊 v1.0.0',
  [string[]]$Topics = @('wails', 'golang', 'desktop-app', 'windows', 'chaoxing'),
  [switch]$DryRun
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$api  = 'https://api.github.com'

# ---------- 收集文件 ----------
$skipDir  = @('.git', 'build\bin', 'build\windows\installer', 'node_modules', 'data', 'webview-data', '.hallmark')
$skipFile = @('*.exe', '*.dll', '*.syso', '*.log', '*.tmp')
$files = @(Get-ChildItem -Path $root -Recurse -File | Where-Object {
  $rel = $_.FullName.Substring($root.Length + 1)
  if ($skipFile | Where-Object { $rel -like $_ }) { return $false }
  foreach ($d in $skipDir) { if ($rel -like ($d + '\*')) { return $false } }
  if ($_.Length -gt 40MB) { return $false }
  return $true
})

if ($DryRun) {
  Write-Host '· DryRun：只列文件，不联网' -ForegroundColor Cyan
  $files | ForEach-Object { "{0,9:N0}  {1}" -f $_.Length, ($_.FullName.Substring($root.Length + 1) -replace '\\', '/') }
  return
}

if (-not $Token) { throw "缺少 token。先设置 `$env:GITHUB_TOKEN，或用 -Token 传入。" }
if (-not (Get-Command curl.exe -ErrorAction SilentlyContinue)) { throw '找不到 curl.exe（Windows 10 1803+ 自带）。' }

# ---------- HTTP 传输层：curl 比 Invoke-RestMethod 更能扛住大 body ----------
$script:tmpDir = Join-Path $env:TEMP ('cx-publish-' + [guid]::NewGuid().ToString('N').Substring(0, 8))
New-Item -ItemType Directory -Force $script:tmpDir | Out-Null

function Invoke-Api {
  param([string]$Method, [string]$Path, $Body, [int]$Retry = 6)

  $url = if ($Path -like 'http*') { $Path } else { "$api$Path" }
  $respFile = Join-Path $script:tmpDir ((New-Guid).ToString('N') + '.json')
  $bodyFile = $null
  $curlArgs = @(
    '--silent', '--show-error', '--location',
    '--http1.1',
    '--max-time', '180',
    '--connect-timeout', '25',
    '--retry', '2', '--retry-all-errors', '--retry-delay', '1',
    '--request', $Method,
    '--header', "Authorization: Bearer $Token",
    '--header', 'Accept: application/vnd.github+json',
    '--header', 'X-GitHub-Api-Version: 2022-11-28',
    '--header', 'User-Agent: chaoxing-studio-publisher',
    '--output', $respFile,
    '--write-out', '%{http_code}'
  )
  if ($null -ne $Body) {
    $bodyFile = Join-Path $script:tmpDir ((New-Guid).ToString('N') + '.req.json')
    $json = ConvertTo-Json $Body -Depth 12 -Compress
    if ($Path -like '*/git/trees') { [IO.File]::WriteAllText((Join-Path $root 'build\tree-debug.json'), $json, (New-Object Text.UTF8Encoding($false))) }
    [IO.File]::WriteAllText($bodyFile, $json, (New-Object Text.UTF8Encoding($false)))
    $curlArgs += @('--header', 'Content-Type: application/json', '--data-binary', "@$bodyFile")
  }
  $curlArgs += $url

  $attempt = 0
  while ($true) {
    $attempt++
    $code = (& curl.exe @curlArgs).Trim()
    $text = ''
    if (Test-Path $respFile) { $text = [IO.File]::ReadAllText($respFile) }

    $ok = ($code -match '^\d{3}$') -and ([int]$code -ge 200) -and ([int]$code -lt 300)
    if ($ok) {
      if (-not $text.Trim()) { throw "GitHub API $Method $Path 返回空响应体" }
      return ($text | ConvertFrom-Json)
    }

    # 这台机器的网络对 api.github.com 很不稳（偶发 TLS 握手失败、连不上、以及 404 误响应），
    # 所以 404 也纳入重试，只有 4xx 里的语义错误（400/401/403/409/422）才是真的失败。
    $transient = ($code -eq '000') -or ($code -eq '404') -or ($code -match '^5') -or ($code -eq '429')
    if ($transient -and $attempt -lt $Retry) {
      Start-Sleep -Milliseconds ([Math]::Min(4000, 500 * $attempt))
      continue
    }

    $hint = ''
    if ($code -eq '401') { $hint = '（token 无效或已过期）' }
    if ($code -eq '403') { $hint = '（权限不足：classic PAT 需要 public_repo / repo）' }
    if ($code -eq '000') { $hint = '（连不上 api.github.com，检查网络或代理）' }
    $blen = if ($bodyFile) { $json.Length } else { 0 }
    throw "GitHub API $Method $Path 失败（HTTP $code，URL=$url，body=$blen 字节）$hint`n$($text.Substring(0, [Math]::Min(400, $text.Length)))"
  }
}

function Put-ContentFile {
  param([string]$Owner, [string]$RepoName, [string]$RelPath, [string]$FullPath, [string]$CommitMessage)
  $enc = ($RelPath -split '/' | ForEach-Object { [uri]::EscapeDataString($_) }) -join '/'
  $b64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes($FullPath))
  return Invoke-Api PUT "/repos/$Owner/$RepoName/contents/$enc" @{
    message = $CommitMessage
    content = $b64
    branch  = $Branch
  }
}

try {
  # ---------- 1. 身份 ----------
  Write-Host '· 校验 token …' -ForegroundColor Cyan
  $me = Invoke-Api GET '/user'
  $owner = $me.login
  Write-Host "  → $owner" -ForegroundColor DarkGray

  # ---------- 2. 建库 ----------
  $repoBody = @{
    name            = $Repo
    private         = (-not $Public)
    description     = $Description
    has_issues      = $true
    has_wiki        = $false
    has_projects    = $false
    has_discussions = $false
    auto_init       = $false
  }
  $repoPath = if ($me.type -eq 'Organization') { "/orgs/$owner/repos" } else { '/user/repos' }
  Write-Host ("· 建库 {0}/{1}（{2}）…" -f $owner, $Repo, $(if ($Public) { 'public' } else { 'private' })) -ForegroundColor Cyan
  try {
    $repoInfo = Invoke-Api POST $repoPath $repoBody
  } catch {
    if ($_.Exception.Message -match 'HTTP 422') {
      Write-Host '  → 仓库已存在，复用' -ForegroundColor Yellow
      $repoInfo = Invoke-Api GET "/repos/$owner/$Repo"
    } else { throw }
  }
  Write-Host "  → $($repoInfo.full_name)" -ForegroundColor DarkGray

  # ---------- 3. 空仓库引导（Git Data API 在仓库没有任何提交前会返回 409）----------
  $empty = $false
  try { $null = Invoke-Api GET "/repos/$owner/$Repo/git/ref/heads/$Branch" } catch { $empty = $true }
  if ($empty) {
    $seed = $files | Where-Object { (($_.FullName.Substring($root.Length + 1)) -replace '\\', '/') -eq 'LICENSE' } | Select-Object -First 1
    if (-not $seed) { $seed = $files | Select-Object -First 1 }
    $seedRel = $seed.FullName.Substring($root.Length + 1) -replace '\\', '/'
    Write-Host "· 空仓库：先用 Contents API 落地种子文件 $seedRel …" -ForegroundColor Cyan
    $null = Put-ContentFile -Owner $owner -RepoName $Repo -RelPath $seedRel -FullPath $seed.FullName -CommitMessage 'chore: init repository'
    Start-Sleep -Milliseconds 900
  }

  # ---------- 4. 逐文件建 blob ----------
  Write-Host ("· 上传 {0} 个文件（{1:N0} KB）…" -f $files.Count, (($files | Measure-Object Length -Sum).Sum / 1KB)) -ForegroundColor Cyan
  $tree = New-Object System.Collections.Generic.List[object]
  $i = 0
  foreach ($f in $files) {
    $i++
    $rel = $f.FullName.Substring($root.Length + 1) -replace '\\', '/'
    $b64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes($f.FullName))
    $blob = Invoke-Api POST "/repos/$owner/$Repo/git/blobs" @{ content = $b64; encoding = 'base64' }
    if (-not $blob -or -not $blob.sha) { throw "建立 blob 失败（无 sha）：$rel" }
    $tree.Add(@{ path = $rel; mode = '100644'; type = 'blob'; sha = $blob.sha })
    Write-Progress -Activity '上传' -Status "$i/$($files.Count)  $rel" -PercentComplete (100 * $i / $files.Count)
  }
  Write-Progress -Activity '上传' -Completed

  # ---------- 4. tree / commit / 分支 ----------
  $baseTree = $null
  $parents = @()
  try {
    $ref = Invoke-Api GET "/repos/$owner/$Repo/git/ref/heads/$Branch"
    $parentCommit = Invoke-Api GET "/repos/$owner/$Repo/git/commits/$($ref.object.sha)"
    $baseTree = $parentCommit.tree.sha
    $parents = @($ref.object.sha)
    Write-Host "· 追加提交到现有分支 $Branch …" -ForegroundColor Cyan
  } catch {
    Write-Host "· 分支 $Branch 不存在，创建首个提交 …" -ForegroundColor Cyan
  }

  $treeBody = @{ tree = $tree.ToArray() }
  if ($baseTree) { $treeBody.base_tree = $baseTree }
  $treeResp = Invoke-Api POST "/repos/$owner/$Repo/git/trees" $treeBody

  $commit = Invoke-Api POST "/repos/$owner/$Repo/git/commits" @{ message = $Message; tree = $treeResp.sha; parents = $parents }

  if ($baseTree) {
    $null = Invoke-Api PATCH "/repos/$owner/$Repo/git/refs/heads/$Branch" @{ sha = $commit.sha; force = $false }
  } else {
    $null = Invoke-Api POST "/repos/$owner/$Repo/git/refs" @{ ref = "refs/heads/$Branch"; sha = $commit.sha }
  }

  # ---------- 5. 元信息 ----------
  try { $null = Invoke-Api PUT "/repos/$owner/$Repo/topics" @{ names = $Topics } } catch { Write-Warning "topics 设置失败：$($_.Exception.Message)" }

  Write-Host ''
  Write-Host "已上传 $($files.Count) 个文件 → $($repoInfo.html_url)" -ForegroundColor Green
  Write-Host "提交 $($commit.sha.Substring(0, 7))  $Message" -ForegroundColor Green
} finally {
  Remove-Item $script:tmpDir -Recurse -Force -ErrorAction SilentlyContinue
}
