# Builds NeyroHub-1.0.jar from src\ and resources\ (Java 25, Paper API from ..\neyrotab-src\buildlib).
#   powershell -ExecutionPolicy Bypass -File build.ps1            -> build\NeyroHub-1.0.jar
#   powershell -ExecutionPolicy Bypass -File build.ps1 -Install   -> also copies it to
#       ..\..\minecraft-server\plugins\update\ : Paper swaps the plugin on the next server start.
param([switch]$Install)
$ErrorActionPreference = 'Stop'
$here = $PSScriptRoot
$classes = Join-Path $here 'build\classes'
$jar = Join-Path $here 'build\NeyroHub-1.0.jar'
if (Test-Path $classes) { Remove-Item -Recurse -Force $classes }
New-Item -ItemType Directory -Force $classes | Out-Null

$cp = (Get-ChildItem (Join-Path $here '..\neyrotab-src\buildlib') -Recurse -Filter *.jar | ForEach-Object FullName) -join ';'
$sources = Get-ChildItem (Join-Path $here 'src') -Recurse -Filter *.java | ForEach-Object FullName
& javac --release 25 -encoding UTF-8 -nowarn -d $classes -cp $cp $sources
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

Copy-Item -Recurse -Force (Join-Path $here 'resources\*') $classes
if (Test-Path $jar) { Remove-Item -Force $jar }
& jar --create --file $jar -C $classes .
if ($LASTEXITCODE -ne 0) { throw "jar failed" }
Write-Host "Built $jar ($([math]::Round((Get-Item $jar).Length / 1MB, 2)) MB)"

if ($Install) {
    $update = Join-Path $here '..\..\minecraft-server\plugins\update'
    New-Item -ItemType Directory -Force $update | Out-Null
    Copy-Item -Force $jar (Join-Path $update 'NeyroHub-1.0.jar')
    Write-Host "Copied to plugins\update: the new NeyroHub is installed on the next server restart"
}
