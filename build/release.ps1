param(
  [string]$Token = $env:GITHUB_TOKEN,
  [string]$Owner = 'X1Rain0',
  [string]$Repo = 'chaoxing-studio',
  [string]$Tag = 'v1.0.0',
  [string]$Name = '学习通签到工坊 v1.0.0',
  [string]$Asset = 'D:\ChaoxingStudio\ChaoxingStudio.exe',
  [string]$BodyFile = '',
  [string]$Notes = ''
)

$ErrorActionPreference = 'Stop'
$api = 'https://api.github.com'

if (-not (Test-Path $Asset)) { throw "找不到要上传的文件：$Asset" }
$assetName = Split-Path $Asset -Leaf
$assetSize = (Get-Item $Asset).Length
$hash = (Get-FileHash $Asset -Algorithm SHA256).Hash.ToLower()

function Invoke-Curl {
  param([string]$Method, [string]$Url, $Body, [string]$ContentType = 'application/json', [int]$Timeout = 300)
  $curlArgs = @('--silent', '--show-error', '--location', '--http1.1',
    '--max-time', "$Timeout", '--connect-timeout', '25',
    '--retry', '3', '--retry-all-errors', '--retry-delay', '1',
    '--request', $Method,
    '--header', "Authorization: Bearer $Token",
    '--header', 'Accept: application/vnd.github+json',
    '--header', 'X-GitHub-Api-Version: 2022-11-28',
    '--header', 'User-Agent: chaoxing-studio-release')
  $tmp = $null
  if ($Body -is [string] -and $Body -like '@*') {
    $curlArgs += @('--header', "Content-Type: $ContentType", '--data-binary', $Body)
  } elseif ($null -ne $Body) {
    $tmp = Join-Path $env:TEMP ('rel-' + [guid]::NewGuid().ToString('N') + '.json')
    [IO.File]::WriteAllText($tmp, (ConvertTo-Json $Body -Depth 8 -Compress), (New-Object Text.UTF8Encoding($false)))
    $curlArgs += @('--header', "Content-Type: $ContentType", '--data-binary', "@$tmp")
  }
  $resp = Join-Path $env:TEMP ('relresp-' + [guid]::NewGuid().ToString('N') + '.json')
  $curlArgs += @('--output', $resp, '--write-out', '%{http_code}', $Url)
  try {
    $code = (& curl.exe @curlArgs).Trim()
    $text = if (Test-Path $resp) { [IO.File]::ReadAllText($resp) } else { '' }
    if ($code -match '^2\d\d$') { if ($text.Trim()) { return ($text | ConvertFrom-Json) } else { return $null } }
    return [pscustomobject]@{ __error = $code; __body = $text }
  } finally {
    foreach ($p in @($tmp, $resp)) { if ($p -and (Test-Path $p)) { Remove-Item $p -Force -ErrorAction SilentlyContinue } }
  }
}

# ---------- 发布说明 ----------
if ($BodyFile -and (Test-Path $BodyFile)) {
  $body = [IO.File]::ReadAllText($BodyFile)
} elseif ($Notes) {
  $body = $Notes
} else {
  $body = @'
## 学习通签到工坊 v1.0.0

Windows 桌面版超星学习通签到助手。本地运行，无浏览器依赖，无云服务，单文件 exe。

### 下载

`ChaoxingStudio.exe` —— 免安装，双击即用。首次启动会在 `%APPDATA%\ChaoxingStudio` 建立数据目录；若该目录不可写，会自动回退到程序目录或 `%TEMP%`。

### 功能

- 手机号登录学习通，密码经 Windows DPAPI 加密后保存在本机，不写日志、不上传
- 多账号并行巡检，轮询间隔 10–3600 秒可调，连续失败自动退避（最多 300 秒）
- 覆盖六类签到：普通 / 拍照 / 二维码 / 手势 / 位置 / 签到码
- 会话失效自动重登；活动列表全量扫描，不是只看第一条
- 界面是一张点名册：纸白底、发丝横线、等宽数字、朱红印章；签到成功落一枚「签」印，夜间模式同一套排版整体反相
- 运行日志实时输出；签到记录最多保留 800 条，超出丢弃最旧的

### 说明

- 使用的学习通接口属非公开 API，可能随服务端调整而失效
- 签到提交逻辑尚未用真实账号做端到端验证；二维码签到的 `enc` 有时效，拍照签到需要云盘根目录存在 `0.jpg`
- 仅供学习与个人研究，请遵守所在学校的规定
'@
}

$body = $body.TrimEnd() + "`n`n### 文件校验`n`n$([math]::Round($assetSize / 1MB, 2)) MB · SHA-256 ``$hash``" + "`n"

# ---------- 建 release ----------
Write-Host "· 创建 release $Tag …" -ForegroundColor Cyan
$rel = Invoke-Curl POST "$api/repos/$Owner/$Repo/releases" @{
  tag_name         = $Tag
  target_commitish = 'main'
  name             = $Name
  body             = $body
  draft            = $false
  prerelease       = $false
}

if ($rel -and $rel.__error) {
  if ($rel.__error -eq '422') {
    Write-Host '  → 该 tag 的 release 已存在，复用' -ForegroundColor Yellow
    $rel = Invoke-Curl GET "$api/repos/$Owner/$Repo/releases/tags/$Tag"
    if ($rel.__error) { throw "获取已存在的 release 失败（HTTP $($rel.__error)）：$($rel.__body)" }
  } else {
    throw "创建 release 失败（HTTP $($rel.__error)）：$($rel.__body)"
  }
}

Write-Host "  → $($rel.html_url)" -ForegroundColor DarkGray
$relId = $rel.id

# ---------- 上传资产 ----------
Write-Host "· 上传 $assetName（$([math]::Round($assetSize / 1MB, 2)) MB）…" -ForegroundColor Cyan
$upload = "https://uploads.github.com/repos/$Owner/$Repo/releases/$relId/assets?name=$assetName"
$asset2 = Invoke-Curl POST $upload "@$Asset" 'application/octet-stream' 1800

if ($asset2 -and $asset2.__error) { throw "上传资产失败（HTTP $($asset2.__error)）：$($asset2.__body)" }

Write-Host ''
Write-Host "已发布：$($rel.html_url)" -ForegroundColor Green
Write-Host "资产：$($asset2.name)  $([math]::Round($asset2.size / 1MB, 2)) MB  state=$($asset2.state)" -ForegroundColor Green
Write-Host "下载：$($asset2.browser_download_url)" -ForegroundColor Green
Write-Host "SHA-256：$hash" -ForegroundColor DarkGray
