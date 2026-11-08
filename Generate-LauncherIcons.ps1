$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$path = [Drawing.Drawing2D.GraphicsPath]::new()
try {
    $path.AddLine(26, 8, 26, 31)
    $path.AddBezier(26, 31, 26, 37, 12, 41, 12, 34)
    $path.AddBezier(12, 34, 12, 29, 19, 27, 23, 29)
    $path.AddLines([Drawing.PointF[]]@(
        [Drawing.PointF]::new(23, 29), [Drawing.PointF]::new(23, 13),
        [Drawing.PointF]::new(37, 8), [Drawing.PointF]::new(37, 16),
        [Drawing.PointF]::new(26, 20), [Drawing.PointF]::new(26, 8)))
    $path.CloseFigure()
    foreach ($density in @{ mdpi = 48; hdpi = 72; xhdpi = 96; xxhdpi = 144; xxxhdpi = 192 }.GetEnumerator()) {
        $size = $density.Value
        $bitmap = [Drawing.Bitmap]::new($size, $size, [Drawing.Imaging.PixelFormat]::Format32bppArgb)
        $graphics = [Drawing.Graphics]::FromImage($bitmap)
        try {
            $graphics.Clear([Drawing.Color]::Transparent)
            $graphics.SmoothingMode = [Drawing.Drawing2D.SmoothingMode]::AntiAlias
            $graphics.ScaleTransform(($size / 48.0), ($size / 48.0))
            $graphics.FillPath([Drawing.Brushes]::Black, $path)
            $directory = Join-Path $PSScriptRoot "app\src\main\res\mipmap-$($density.Key)"
            New-Item -ItemType Directory -Path $directory -Force | Out-Null
            $bitmap.Save((Join-Path $directory 'ic_launcher.png'), [Drawing.Imaging.ImageFormat]::Png)
        } finally {
            $graphics.Dispose()
            $bitmap.Dispose()
        }
    }
} finally {
    $path.Dispose()
}
