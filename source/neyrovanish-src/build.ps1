$ErrorActionPreference = 'Stop'
$output = Join-Path $PSScriptRoot 'build'
$classes = Join-Path $output ([guid]::NewGuid().ToString())
New-Item -ItemType Directory -Force $classes | Out-Null
$cp = (Get-ChildItem (Join-Path $PSScriptRoot '..\neyrotab-src\buildlib') -Recurse -Filter *.jar | ForEach-Object FullName) -join ';'
$sources = Get-ChildItem (Join-Path $PSScriptRoot 'src') -Recurse -Filter *.java | ForEach-Object FullName
& javac --release 25 -encoding UTF-8 -d $classes -cp $cp $sources
if ($LASTEXITCODE -ne 0) { throw 'NeyroVanish compilation failed' }
$jar = Join-Path $output 'NeyroVanish-1.0.jar'
& jar --create --file $jar -C $classes . -C (Join-Path $PSScriptRoot 'resources') .
if ($LASTEXITCODE -ne 0) { throw 'NeyroVanish packaging failed' }
Write-Host "Built $jar"
