$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$backendPath = Join-Path $projectRoot 'backend'
$frontendPath = Join-Path $projectRoot 'frontend'

if (-not (Test-Path (Join-Path $backendPath 'pom.xml'))) {
    throw "Backend project not found: $backendPath"
}
if (-not (Test-Path (Join-Path $frontendPath 'package.json'))) {
    throw "Frontend project not found: $frontendPath"
}
if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) {
    throw 'Maven was not found. Install Maven and add mvn to PATH.'
}
if (-not (Get-Command npm -ErrorAction SilentlyContinue)) {
    throw 'npm was not found. Install Node.js and add npm to PATH.'
}
if (-not (Test-Path (Join-Path $frontendPath 'node_modules'))) {
    throw "Frontend dependencies are missing. Run: cd `"$frontendPath`"; npm install"
}

$backendProcess = Start-Process -FilePath 'powershell.exe' `
    -ArgumentList @('-NoExit', '-NoProfile', '-Command', 'mvn spring-boot:run') `
    -WorkingDirectory $backendPath -PassThru

$frontendProcess = Start-Process -FilePath 'powershell.exe' `
    -ArgumentList @('-NoExit', '-NoProfile', '-Command', 'npm run dev') `
    -WorkingDirectory $frontendPath -PassThru

Write-Host "Backend started, PID: $($backendProcess.Id), URL: http://localhost:9090" -ForegroundColor Green
Write-Host "Frontend started, PID: $($frontendProcess.Id), URL: http://localhost:5173" -ForegroundColor Green
Write-Host 'Two terminal windows were opened. Close either window to stop that service.' -ForegroundColor Yellow
