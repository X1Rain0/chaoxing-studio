param(
  [string]$Token = $env:GITHUB_TOKEN,
  [string]$Owner = 'X1Rain0',
  [string]$Repo = 'chaoxing-studio',
  [string]$Tag = 'v1.0.0',
  [string]$Name = '学习通签到工坊 v1.0.0',
  [string]$Asset = 'D:\ChaoxingStudio\ChaoxingStudio.exe',
  [string]$BodyFile = ''
)

$ErrorActionPreference = 'Stop'
$api = 'https://api.github.com'
$hdr = @('Authorization: Bearer ' + $Token, 'Accept: application/vnd.github+json', 'X-GitHub-Api-Version: 2022-11-28', 'User-Agent: chaoxing-studio-release')

if (-not (Test-Path $Asset)) { throw "找不到要上传的文件：$Asset" }
$assetName = Split-Path $Asset -Leaf
$hash = (Get-FileHash $Asset -Algorithm SHA256).Hash.ToLower()

# ---------- 发布说明 ----------
if ($BodyFile -and (Test-Path $BodyFile)) {
  $body = [IO.File]::ReadAllText($BodyFile)
} else {
  $body = @'
## 学习通签到工坊 v1.0.0

Windows 桌面版超星学习通签到助手。本地运行，无浏览器依赖，无云服务，单文件 exe。

### 下载

`ChaoxingStudio.exe` —— 免安装，双击即用。首次启动会在 `%APPDATA%\ChaoxingStudio` 建立数据目录；若该目录不可写会自动回退到程序目录或 `%TEMP%`。

### 功能

- 手机号登录学习通，密码经 Windows DPAPI 加密后保存在本机，不写日志、不上传
- 多账号并行巡检，轮询间隔 10–3600 秒可调，连续失败自动退避（最多 300 秒）
- 覆盖六类签到：普通 / 拍照 / 二维码 / 手势 / 位置 / 签到码
- 会话失效自动重登；活动列表全量扫描，不是只看第一条
- 界面是一张点名册：纸白底、发丝横线、等宽数字、朱红印章；签到成功落一枚「签」印，夜间模式同一套排版整体反相
- 运行日志实时输出；签到记录最多保留 800 条，超出丢弃最旧的

### 说明

- 使用的学习通接口属非公开 API，可能随服务端调整而失效。
- 签到提交逻辑尚未用真实账号做端到端验证；二维码签到的 `enc` 有时效，拍照签到需要云盘根目录存在 `0.jpg`。
- 仅供学习与个人研究，请遵守所在学校的规定。

### 校验
'@
}

$body = $body.TrimEnd() + "`n`n``$([math]::Round((Get-Item $Asset).Length / 1MB, 2)) MB · SHA-256 ``$hash``" + "`n"

# ---------- 建 release ----------
$payload = @{ tag_name = $Tag; target_commitish = 'main'; name = $Name; body = $body; draft = $false; prerelease = $false }
$jsonFile = Join-Path $env:TEMP ('rel-' + [guid]::NewGuid().ToString('N') + '.json')
[IO.File]::WriteAllText($jsonFile, (ConvertTo-Json $payload -Depth 5 -Compress), (New-Object Text.UTF8Encoding($false)))

$resp = Join-Path $env:TEMP ('relresp-' + [guid]::NewGuid().ToString('N') + '.json')
$code = (& curl.exe --silent --show-error --location --max-time 120 --connect-timeout 25 --retry 3 --retry-all-errors `
  --request POST --header $hdr[0] --header $hdr[1] --header $hdr[2] --header $hdr[3] `
  --header 'Content-Type: application/json' --data-binary "@$jsonFile" `
  --output $resp --write-out '%{http_code}' "$api/repos/$Owner/$Repo/releases").Trim()
$text = [IO.File]::ReadAllText($resp)

if ($code -eq '422') {
  Write-Host '· 该 tag 的 release 已存在，改为复用 …' -ForegroundColor Yellow
  $rel = & curl.exe --silent --show-error --location --header $hdr[0] --header $hdr[1] --header $hdr[3] "$api/repos/$Owner/$Repo/releases/tags/$Tag" | ConvertFrom-Json
} elseif ($code -match '^2\d\d$') {
  $rel = $text | ConvertFrom-Json
} else {
  throw "创建 release 失败（HTTP $code）：$text"
}

Write-Host "· Release: $($rel.html_url)" -ForegroundColor Cyan
$relId = $rel.id

# ---------- 上传资产 ----------
$uploadUrl = "https://uploads.github.com/repos/$Owner/$Repo/releases/$relId/assets?name=$assetName"
Write-Host "· 上传 $assetName（$([math]::Round((Get-Item $Asset).Length/1MB,2)) MB）…" -ForegroundColor Cyan

$assetResp = Join-Path $env:TEMP ('asset-' + [guid]::NewGuid().ToString('N') + '.json')
$code2 = (& curl.exe --silent --show-error --location --max-time 900 --connect-timeout 25 --retry 3 --retry-all-errors `
  --request POST --header $hdr[0] --header $hdr[1] --header $hdr[2] --header $hdr[3] `
  --header 'Content-Type: application/octet-stream' --data-binary "@$Asset" `
  --output $assetResp --write-out '%{http_code}' $uploadUrl).Trim()
$text2 = [IO.File]::ReadAllText($assetResp)

if ($code2 -notmatch '^2\d\d$') { throw "上传资产失败（HTTP $code2）：$text2" }
$a = $text2 | ConvertFrom-Json

Write-Host ''
Write-Host "已发布：$($rel.html_url)" -ForegroundColor Green
Write-Host "资产：$($a.name)  $([math]::Round($a.size/1MB,2)) MB  state=$($a.state)" -ForegroundColor Green
Write-Host "SHA-256：$hash" -ForegroundColor DarkGray

Remove-Item $jsonFile, $resp, $assetResp -Force -ErrorAction SilentlyContinue
