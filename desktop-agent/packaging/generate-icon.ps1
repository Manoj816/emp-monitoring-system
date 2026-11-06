# Regenerates src/EmpVerify.Agent.Windows/Assets/EmpVerify.ico — the PW mark
# (PresenSure Workforce) used by the tray, the exe and the window. The committed
# .ico is the source of truth; run this only to change the artwork.
#
# The icon carries its own frame at every size the tray asks for rather than one
# big image Windows resamples: "PW" at 16px is a few pixels of stroke, and
# anything scaled into that is grey mush. Corners go square below 24px, where
# rounding reads as a smudge instead of a tile. Frames are stored as PNGs inside
# the .ico (valid since Vista). Windows PowerShell (System.Drawing).
param(
    [string]$OutFile = (Join-Path $PSScriptRoot "..\src\EmpVerify.Agent.Windows\Assets\EmpVerify.ico"),
    [string]$Text = "PW"
)

Add-Type -AssemblyName System.Drawing

function New-FramePng([int]$size, [string]$text) {
    $bmp = New-Object System.Drawing.Bitmap $size, $size
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.TextRenderingHint = [System.Drawing.Text.TextRenderingHint]::AntiAlias
    $navy = [System.Drawing.Color]::FromArgb(27, 42, 74) # brand navy, same as the tiles
    $brush = New-Object System.Drawing.SolidBrush $navy

    if ($size -ge 24) {
        # Rounded tile: radius scales with the frame.
        $r = [Math]::Max(2, [int]($size / 5))
        $d = $r * 2
        $path = New-Object System.Drawing.Drawing2D.GraphicsPath
        $path.AddArc(0, 0, $d, $d, 180, 90)
        $path.AddArc($size - $d, 0, $d, $d, 270, 90)
        $path.AddArc($size - $d, $size - $d, $d, $d, 0, 90)
        $path.AddArc(0, $size - $d, $d, $d, 90, 90)
        $path.CloseFigure()
        $g.FillPath($brush, $path)
        $path.Dispose()
    } else {
        $g.Clear($navy)
    }

    # Fit the mark: start large and shrink until it sits inside the tile.
    $fontSize = [Math]::Max(5, [int]($size * 0.55))
    while ($fontSize -gt 4) {
        $font = New-Object System.Drawing.Font("Segoe UI", $fontSize, [System.Drawing.FontStyle]::Bold, [System.Drawing.GraphicsUnit]::Pixel)
        $measured = $g.MeasureString($text, $font)
        if ($measured.Width -le $size * 0.96) { break }
        $font.Dispose()
        $fontSize--
    }
    $sf = New-Object System.Drawing.StringFormat
    $sf.Alignment = [System.Drawing.StringAlignment]::Center
    $sf.LineAlignment = [System.Drawing.StringAlignment]::Center
    $rect = New-Object System.Drawing.RectangleF(0, 0, $size, $size)
    $g.DrawString($text, $font, [System.Drawing.Brushes]::White, $rect, $sf)
    $g.Dispose()

    $ms = New-Object System.IO.MemoryStream
    $bmp.Save($ms, [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
    $font.Dispose()
    $brush.Dispose()
    $sf.Dispose()
    return $ms.ToArray()
}

# 16/24 are the tray at 100%/150% scaling, 32 the window chrome, 48/256 Explorer.
$sizes = 16, 20, 24, 32, 48, 64, 256
$frames = @()
foreach ($s in $sizes) { $frames += , (New-FramePng $s $Text) }

# ICO container: 6-byte header, 16 bytes per directory entry, then the PNG blobs.
$ms = New-Object System.IO.MemoryStream
$w = New-Object System.IO.BinaryWriter $ms
$w.Write([UInt16]0)              # reserved
$w.Write([UInt16]1)              # type 1 = icon
$w.Write([UInt16]$sizes.Count)
$offset = 6 + 16 * $sizes.Count
for ($i = 0; $i -lt $sizes.Count; $i++) {
    $s = $sizes[$i]
    $w.Write([Byte]($(if ($s -ge 256) { 0 } else { $s })))  # width (0 means 256)
    $w.Write([Byte]($(if ($s -ge 256) { 0 } else { $s })))  # height
    $w.Write([Byte]0)            # palette colors
    $w.Write([Byte]0)            # reserved
    $w.Write([UInt16]1)          # planes
    $w.Write([UInt16]32)         # bits per pixel
    $w.Write([UInt32]$frames[$i].Length)
    $w.Write([UInt32]$offset)
    $offset += $frames[$i].Length
}
foreach ($f in $frames) { $w.Write($f, 0, $f.Length) }
$w.Flush()
[System.IO.File]::WriteAllBytes((Resolve-Path (Split-Path $OutFile)).Path + "\" + (Split-Path $OutFile -Leaf), $ms.ToArray())
$w.Dispose()
Write-Output "wrote $OutFile ($($sizes -join ', ') px)"
