@echo off
setlocal
if "%~1"=="" goto preparar
if /I "%~1"=="--diagnostico" if "%~2"=="" goto diagnostico
echo Uso: preparar-ep2.cmd [--diagnostico]
exit /b 2
:preparar
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0preparar-ep2.ps1"
exit /b %errorlevel%
:diagnostico
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0preparar-ep2.ps1" -Diagnostico
exit /b %errorlevel%
