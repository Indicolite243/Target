@echo off
chcp 65001 >nul
set "NO_COLOR=1"
set "FORCE_COLOR=0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-frontend.ps1"
exit /b %ERRORLEVEL%
