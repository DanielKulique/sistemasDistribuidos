$ErrorActionPreference = 'Stop'
$jdkBin = Split-Path (Get-Command javac -ErrorAction Stop).Source
$javaExe = Join-Path $jdkBin 'java.exe'
$jarExe = Join-Path $jdkBin 'jar.exe'
$argumentos = $args
& "$PSScriptRoot/build.ps1"
Push-Location $PSScriptRoot
try {
    & $javaExe -cp 'build/ep1.jar;lib/*' br.edu.utfpr.sd.ep1.Servidor @argumentos
    if ($LASTEXITCODE -ne 0) { throw 'Execucao encerrada com erro' }
} finally { Pop-Location }
