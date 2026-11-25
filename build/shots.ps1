# 用 Edge headless 给静态前端拍照（无需启动 Wails）。
# 用法： pwsh -File build\shots.ps1 -Prefix v3
param([string]$Prefix = "shots")

$edge = 'C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe'
$dist = (Resolve-Path (Join-Path $PSScriptRoot '..\frontend\dist\index.html')).Path
$url  = 'file:///' + ($dist -replace '\\', '/' -replace ' ', '%20')
$out  = $PSScriptRoot

$jobs = @(
  @{ name = 'overview'; frag = 'overview' },
  @{ name = 'courses';  frag = 'courses' },
  @{ name = 'records';  frag = 'records' },
  @{ name = 'settings'; frag = 'settings' },
  @{ name = 'ink';      frag = 'overview?theme=ink' }
)

foreach ($j in $jobs) {
  $tmp = Join-Path $env:TEMP ('edgeprof-' + [guid]::NewGuid().ToString('N').Substring(0, 8))
  $png = Join-Path $out ("$Prefix-" + $j.name + '.png')
  if (Test-Path $png) { Remove-Item $png -Force }
  # frag 形如 "records" 或 "overview?theme=ink"；query 必须在 # 之前
  $bits = $j.frag -split '\?', 2
  $view = $bits[0]
  $qs   = if ($bits.Count -gt 1) { '?' + $bits[1] } else { '' }
  $target = "$url$qs#$view"

  & $edge --headless=new --disable-gpu --hide-scrollbars --window-size=1320,880 `
    --virtual-time-budget=4000 --user-data-dir=$tmp --no-first-run `
    --screenshot=$png $target 2>$null | Out-Null

  Write-Output ("URL " + $target)

  # Edge 退出后才会把 png 落盘，等一小会儿再判断
  for ($i = 0; $i -lt 20 -and -not (Test-Path $png); $i++) { Start-Sleep -Milliseconds 100 }

  if (Test-Path $png) {
    $kb = [math]::Round((Get-Item $png).Length / 1kb)
    Write-Output ("OK   {0,-10} {1}KB  {2}" -f $j.name, $kb, $png)
  } else {
    Write-Output ("FAIL {0}  {1}" -f $j.name, $target)
  }
  if (Test-Path $tmp) { Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue }
}
