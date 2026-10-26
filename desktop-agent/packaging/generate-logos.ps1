# Regenerates the MSIX tile/logo PNGs. Committed PNGs are the source of truth for
# packaging; run this only to change the artwork. Windows PowerShell (System.Drawing).
param([string]$OutDir = (Join-Path $PSScriptRoot "Images"))

Add-Type -AssemblyName System.Drawing
New-Item -ItemType Directory -Force $OutDir | Out-Null

function New-Logo([string]$path, [int]$w, [int]$h, [string]$text) {
    $bmp = New-Object System.Drawing.Bitmap $w, $h
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.TextRenderingHint = [System.Drawing.Text.TextRenderingHint]::AntiAlias
    $g.Clear([System.Drawing.Color]::FromArgb(27, 42, 74)) # brand navy
    $size = [Math]::Max(8, [int]($h / 2.6))
    $font = New-Object System.Drawing.Font("Segoe UI", $size, [System.Drawing.FontStyle]::Bold, [System.Drawing.GraphicsUnit]::Pixel)
    $sf = New-Object System.Drawing.StringFormat
    $sf.Alignment = [System.Drawing.StringAlignment]::Center
    $sf.LineAlignment = [System.Drawing.StringAlignment]::Center
    $rect = New-Object System.Drawing.RectangleF(0, 0, $w, $h)
    $g.DrawString($text, $font, [System.Drawing.Brushes]::White, $rect, $sf)
    $g.Dispose()
    $bmp.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
    Write-Output "wrote $path ($w x $h)"
}

New-Logo (Join-Path $OutDir "Square44x44Logo.png")   44  44  "EV"
New-Logo (Join-Path $OutDir "Square150x150Logo.png") 150 150 "EV"
New-Logo (Join-Path $OutDir "Wide310x150Logo.png")   310 150 "EmpVerify"
New-Logo (Join-Path $OutDir "StoreLogo.png")         50  50  "EV"
