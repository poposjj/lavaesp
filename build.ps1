<#
  build.ps1 - compiles the standalone Lava ESP mod for Minecraft 26.2

  Expects
    <McDir>\client-lavaesp.jar   Minecraft 26.2 client, widened with tools\Widen.java + lavaesp.accesswidener
    <McDir>\libs\*.jar           Minecraft libraries
    <McDir>\fabric-api\*.jar     Fabric API 0.161.0+26.2 (nested modules unpacked)

  The widened jar is required: RenderPipelines.register / DEBUG_FILLED_SNIPPET and
  RenderType.create are private/package-private in 26.2, exactly like in the original mod,
  so the same three members are opened through the mod's access widener.
#>
param(
    [string]$McDir = "D:\nixiang\melodysky_static\mc262",
    [string]$JavaHome = ""
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path

if ($JavaHome) { $env:JAVA_HOME = $JavaHome }
$javac = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME "bin\javac.exe" } else { "javac" }
$jar   = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME "bin\jar.exe" }   else { "jar" }

$mcjar = Join-Path $McDir "client.jar"
if (-not (Test-Path $mcjar)) { throw "missing $mcjar" }

$out = Join-Path $root "build\classes"
if (Test-Path $out) { Remove-Item $out -Recurse -Force }
New-Item -ItemType Directory -Force -Path $out, (Join-Path $root "build\libs") | Out-Null

$cp = @($mcjar)
$cp += (Get-ChildItem (Join-Path $McDir "libs") -Filter *.jar | ForEach-Object FullName)
$cp += (Get-ChildItem (Join-Path $McDir "fabric-api") -Filter *.jar | ForEach-Object FullName)
$extraDir = Join-Path $McDir "extra"
if (Test-Path $extraDir) { $cp += (Get-ChildItem $extraDir -Filter *.jar | ForEach-Object FullName) }
$classpath = $cp -join ";"

Write-Host "[1/2] compiling"
$sources = Get-ChildItem (Join-Path $root "src\main\java") -Recurse -Filter *.java | ForEach-Object FullName
& $javac --release 25 -nowarn -encoding UTF-8 -cp $classpath -d $out $sources
if ($LASTEXITCODE -ne 0) { throw "compilation failed" }

Write-Host "[2/2] packaging"
$staging = Join-Path $root "build\staging"
if (Test-Path $staging) { Remove-Item $staging -Recurse -Force }
Copy-Item $out $staging -Recurse
Copy-Item (Join-Path $root "src\main\resources\*") $staging -Recurse -Force

$fabricJson = Get-Content (Join-Path $staging "fabric.mod.json") -Raw
[IO.File]::WriteAllText((Join-Path $staging "fabric.mod.json"), $fabricJson,
    (New-Object System.Text.UTF8Encoding($false)))

$jarFile = Join-Path $root "build\libs\lavaesp-1.0.0.jar"
if (Test-Path $jarFile) { Remove-Item $jarFile -Force }
& $jar --create --file $jarFile -C $staging .
if ($LASTEXITCODE -ne 0) { throw "jar failed" }

Write-Host ""
Write-Host "built $jarFile ($([math]::Round((Get-Item $jarFile).Length/1KB,1)) KB)"


