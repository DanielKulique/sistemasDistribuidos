$ErrorActionPreference = 'Stop'
$jdkBin = Split-Path (Get-Command javac -ErrorAction Stop).Source
$javaExe = Join-Path $jdkBin 'java.exe'
$jarExe = Join-Path $jdkBin 'jar.exe'
$argumentos = $args
& "$PSScriptRoot/build.ps1" -SomenteClasses
Push-Location $PSScriptRoot
try {
    New-Item -ItemType Directory -Force build/test-classes | Out-Null
    $sources = @(Get-ChildItem src/test/java -Recurse -Filter *.java | ForEach-Object FullName)
    & javac --release 17 -Xlint:all,-classfile -encoding UTF-8 -cp 'build/classes;lib/*' -d build/test-classes @sources
    if ($LASTEXITCODE -ne 0) { throw 'Falha ao compilar testes' }
    foreach ($classe in @('TesteEp1', 'TesteComunicacao', 'TesteGui')) {
        & $javaExe -cp 'build/classes;build/test-classes;lib/*' "br.edu.utfpr.sd.ep1.$classe" @argumentos
        if ($LASTEXITCODE -ne 0) { throw "Falha em $classe" }
    }
} finally { Pop-Location }
