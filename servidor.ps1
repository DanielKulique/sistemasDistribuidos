$ErrorActionPreference = 'Stop'
$jdkBin = Split-Path (Get-Command javac -ErrorAction Stop).Source
$javaExe = Join-Path $jdkBin 'java.exe'
$jarExe = Join-Path $jdkBin 'jar.exe'
$argumentos = $args
& "$PSScriptRoot/build.ps1" -SomenteClasses
Push-Location $PSScriptRoot
try {
    & $javaExe -cp 'build/classes;lib/*' br.edu.utfpr.sd.ep1.Servidor @argumentos
    if ($LASTEXITCODE -ne 0) { throw 'Execucao encerrada com erro' }
} finally { Pop-Location }
