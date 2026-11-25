Add-Type -AssemblyName System.Drawing
Add-Type @"
using System;
using System.Runtime.InteropServices;
public class WP {
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
  [DllImport("user32.dll")] public static extern bool PrintWindow(IntPtr h, IntPtr hdc, uint flags);
  [DllImport("user32.dll")] public static extern bool SetWindowPos(IntPtr h, IntPtr after, int x, int y, int cx, int cy, uint flags);
  [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr h, int c);
  [StructLayout(LayoutKind.Sequential)] public struct RECT { public int Left, Top, Right, Bottom; }
}
"@

$exe    = 'D:\ChaoxingStudio\ChaoxingStudio.exe'
$outLog = Join-Path $env:TEMP 'cx-live-out.log'
$errLog = Join-Path $env:TEMP 'cx-live-err.log'
$proc = Start-Process -FilePath $exe -WorkingDirectory 'D:\ChaoxingStudio' -PassThru -RedirectStandardOutput $outLog -RedirectStandardError $errLog
Start-Sleep -Seconds 9
$proc.Refresh()
if ($proc.HasExited) { Write-Output "EXITED code=$($proc.ExitCode)"; Get-Content $outLog -Raw; exit 1 }

$h = $proc.MainWindowHandle
Write-Output "PID=$($proc.Id) HWND=$h TITLE=[$($proc.MainWindowTitle)]"
if ($h -eq 0) { Write-Output 'NO_MAIN_WINDOW'; exit 2 }

# 不抢焦点：把窗口停到屏幕左上角，用 PrintWindow 直接抓窗口内容
$SWP_NOSIZE = 0x0001; $SWP_NOZORDER = 0x0004; $SWP_SHOWWINDOW = 0x0040
[void][WP]::ShowWindow($h, 9)          # SW_RESTORE
[void][WP]::SetWindowPos($h, [IntPtr]::Zero, 0, 0, 0, 0, $SWP_NOSIZE -bor $SWP_NOZORDER -bor $SWP_SHOWWINDOW)
Start-Sleep -Milliseconds 2500

$r = New-Object WP+RECT
[void][WP]::GetWindowRect($h, [ref]$r)
$w = $r.Right - $r.Left; $hh = $r.Bottom - $r.Top
Write-Output "SIZE=${w}x${hh}"

$bmp = New-Object System.Drawing.Bitmap $w, $hh
$g = [System.Drawing.Graphics]::FromImage($bmp)
$hdc = $g.GetHdc()
$ok = [WP]::PrintWindow($h, $hdc, 2)   # PW_RENDERFULLCONTENT
$g.ReleaseHdc($hdc)
$g.Dispose()
$out = 'D:\dsh workspace\chaoxing-studio\build\shot-live.png'
$bmp.Save($out, [System.Drawing.Imaging.ImageFormat]::Png)
$bmp.Dispose()
Write-Output "PRINTWINDOW=$ok SAVED $out"
Stop-Process -Id $proc.Id -Force
Write-Output 'STOPPED'
