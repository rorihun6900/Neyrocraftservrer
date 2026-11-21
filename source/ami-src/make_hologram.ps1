Add-Type -AssemblyName System.Drawing
$base='C:\Games\NeyroCraft\source\ami-src'
$target='C:\Games\NeyroCraft\source\neyrohub-src\resources\ami'
$src=[Drawing.Bitmap]::new((Join-Path $base 'ami_render.png'))
$minX=$src.Width;$minY=$src.Height;$maxX=0;$maxY=0
for($y=0;$y -lt $src.Height;$y++){for($x=0;$x -lt $src.Width;$x++){if($src.GetPixel($x,$y).A -gt 15){$minX=[Math]::Min($minX,$x);$maxX=[Math]::Max($maxX,$x);$minY=[Math]::Min($minY,$y);$maxY=[Math]::Max($maxY,$y)}}}
$dst=[Drawing.Bitmap]::new(96,192,[Drawing.Imaging.PixelFormat]::Format32bppArgb)
$g=[Drawing.Graphics]::FromImage($dst);$g.Clear([Drawing.Color]::Transparent);$g.InterpolationMode=[Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
$crop=[Drawing.Rectangle]::new($minX,$minY,$maxX-$minX+1,$maxY-$minY+1)
$rect=[Drawing.Rectangle]::new(5,2,86,188)
$g.DrawImage($src,$rect,$crop,[Drawing.GraphicsUnit]::Pixel);$g.Dispose();$src.Dispose()
for($y=0;$y -lt 192;$y++){for($x=0;$x -lt 96;$x++){ $c=$dst.GetPixel($x,$y); if($c.A -lt 10){continue}; $a=[int]($c.A * $(if($y % 4 -eq 0){0.67}else{0.92}));$r=[Math]::Min(255,[int]($c.R*.70+$c.G*.12));$green=[Math]::Min(255,[int]($c.G*.92+20));$b=[Math]::Min(255,[int]($c.B*.95+38));$dst.SetPixel($x,$y,[Drawing.Color]::FromArgb($a,$r,$green,$b)) }}
$dst.Save((Join-Path $target 'ami_holo.png'),[Drawing.Imaging.ImageFormat]::Png)
$lines=@();for($y=0;$y -lt 40;$y++){ $tokens=@();for($x=0;$x -lt 24;$x++){ $c=$dst.GetPixel([int](($x+.5)*4),[int](($y+.5)*4.8)); if($c.A -lt 45){$tokens+='-'}else{$tokens+=('{0:X2}{1:X2}{2:X2}' -f $c.R,$c.G,$c.B)} }; $lines+=($tokens -join ' ') }; [IO.File]::WriteAllLines((Join-Path $target 'ami_pixels.txt'),$lines,([Text.UTF8Encoding]::new($false)))
$dst.Dispose()

