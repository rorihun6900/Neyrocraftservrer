param([switch]$Install)
$ErrorActionPreference = 'Stop'
$here = $PSScriptRoot
$classes = Join-Path $here 'build\classes'
$jar = Join-Path $here 'build\NeyroRTP-1.0.jar'
if (Test-Path $classes) { Remove-Item -Recurse -Force -LiteralPath $classes }
New-Item -ItemType Directory -Force $classes | Out-Null
$cp = (Get-ChildItem (Join-Path $here '..\neyrotab-src\buildlib') -Recurse -Filter *.jar | ForEach-Object FullName) -join ';'
$sources = Get-ChildItem (Join-Path $here 'src') -Recurse -Filter *.java | ForEach-Object FullName
& javac --release 25 -encoding UTF-8 -nowarn -d $classes -cp $cp $sources
if ($LASTEXITCODE -ne 0) { throw 'javac failed' }
Copy-Item -Recurse -Force (Join-Path $here 'resources\*') $classes
& jar --create --file $jar -C $classes .
if ($LASTEXITCODE -ne 0) { throw 'jar failed' }
if ($Install) {
    $update = Join-Path $here '..\..\minecraft-server\plugins\update'
    New-Item -ItemType Directory -Force $update | Out-Null
    Copy-Item -Force $jar (Join-Path $update 'NeyroRTP-1.0.jar')
}
Write-Host "Built $jar"
