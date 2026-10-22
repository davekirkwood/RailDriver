# Run on native Windows with a full JDK 17+ and Maven on PATH.
[CmdletBinding()]
param([string]$Destination = (Join-Path $PSScriptRoot 'dist'))
$ErrorActionPreference = 'Stop'
if ($env:OS -ne 'Windows_NT') { throw 'jpackage must run on native Windows.' }
if (-not $env:JAVA_HOME) { throw 'Set JAVA_HOME to a full Windows JDK 17+.' }
$jpackage = Join-Path $env:JAVA_HOME 'bin/jpackage.exe'
if (-not (Test-Path $jpackage)) { throw "Missing $jpackage" }
& mvn -f (Join-Path $PSScriptRoot 'pom.xml') clean verify
if ($LASTEXITCODE -ne 0) { throw 'Maven verification failed.' }
# Stage only the shaded executable JAR, not the original JAR or test artifacts.
$inputDir = Join-Path $PSScriptRoot 'target/jpackage-input'
New-Item -ItemType Directory -Force -Path $inputDir | Out-Null
Copy-Item (Join-Path $PSScriptRoot 'target/raildriver-gateway.jar') $inputDir
# A second native launcher opens Swing without a console window.
$guiLauncher = Join-Path $PSScriptRoot 'target/workbench-launcher.properties'
@('arguments=--gui', 'win-console=false') | Set-Content -Path $guiLauncher -Encoding ASCII
& $jpackage --type app-image --name RailDriverGateway --app-version 0.1.0 `
    --input $inputDir --main-jar raildriver-gateway.jar `
    --main-class org.raildriver.gateway.Main --dest $Destination --win-console `
    --add-modules java.base,java.desktop,java.logging `
    --add-launcher "RailDriverWorkbench=$guiLauncher" `
    --java-options '--enable-native-access=ALL-UNNAMED'
if ($LASTEXITCODE -ne 0) { throw 'jpackage failed; remove or choose a different existing destination app image.' }
Write-Host "Launch RailDriverWorkbench.exe for Swing or RailDriverGateway.exe for console. App image: $Destination/RailDriverGateway. Copy the whole folder, including runtime."
