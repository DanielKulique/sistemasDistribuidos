$ErrorActionPreference = 'Stop'
$jdkBin = Split-Path (Get-Command javac -ErrorAction Stop).Source
$javaExe = Join-Path $jdkBin 'java.exe'
$jarExe = Join-Path $jdkBin 'jar.exe'
Push-Location $PSScriptRoot
try {
    foreach ($dependency in @('gson-2.14.0', 'mongodb-driver-sync-5.2.1', 'mongodb-driver-core-5.2.1', 'bson-5.2.1')) {
        if (!(Test-Path "lib/$dependency.jar")) { throw "Dependencia ausente: $dependency" }
    }
    New-Item -ItemType Directory -Force build/classes | Out-Null
    $sources = @(Get-ChildItem src/main/java -Recurse -Filter *.java | ForEach-Object FullName)
    & javac --release 17 -Xlint:all,-classfile -encoding UTF-8 -cp 'lib/*' -d build/classes @sources
    if ($LASTEXITCODE -ne 0) { throw 'Falha na compilacao' }
    & $jarExe --create --file build/ep1.jar -C build/classes .
    if ($LASTEXITCODE -ne 0) { throw 'Falha ao gerar JAR' }
    Write-Host 'Compilacao concluida: build/ep1.jar'
} finally { Pop-Location }
