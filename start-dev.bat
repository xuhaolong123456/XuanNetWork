@echo off
setlocal

set "ROOT=%~dp0"

if not exist "%ROOT%backend\pom.xml" (
    echo Backend project not found: %ROOT%backend
    pause
    exit /b 1
)
if not exist "%ROOT%frontend\package.json" (
    echo Frontend project not found: %ROOT%frontend
    pause
    exit /b 1
)
where mvn >nul 2>&1
if errorlevel 1 (
    echo Maven was not found. Please install Maven and add mvn to PATH.
    pause
    exit /b 1
)
where npm >nul 2>&1
if errorlevel 1 (
    echo npm was not found. Please install Node.js and add npm to PATH.
    pause
    exit /b 1
)
if not exist "%ROOT%frontend\node_modules" (
    echo Frontend dependencies are missing.
    echo Run: cd /d "%ROOT%frontend" ^&^& npm install
    pause
    exit /b 1
)

start "NetworkDisk Backend" /D "%ROOT%backend" cmd /k mvn spring-boot:run
start "NetworkDisk Frontend" /D "%ROOT%frontend" cmd /k npm run dev

echo Backend:  http://localhost:9090
echo Frontend: http://localhost:5173
echo Two terminal windows were opened. Close either window to stop that service.
exit /b 0
