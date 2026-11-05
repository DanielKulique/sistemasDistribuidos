@echo off
setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0cliente-ep2.ps1" %*
exit /b %errorlevel%
