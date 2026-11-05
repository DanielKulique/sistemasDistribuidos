param([switch]$SomenteClasses)
$ErrorActionPreference = 'Stop'
$jdkBin = Split-Path (Get-Command javac -CommandType Application -ErrorAction Stop | Select-Object -First 1).Source
$jarExe = Join-Path $jdkBin $(if ($env:OS -eq 'Windows_NT') { 'jar.exe' } else { 'jar' })
Push-Location $PSScriptRoot
try {
    foreach ($dependency in @('gson-2.14.0', 'mariadb-java-client-3.5.11')) {
        if (!(Test-Path "lib/$dependency.jar")) { throw "Dependencia ausente: $dependency" }
    }
    New-Item -ItemType Directory -Force build/classes | Out-Null
    $sources = @(Get-ChildItem src/main/java -Recurse -Filter *.java | ForEach-Object FullName)
    & javac --release 17 -Xlint:all,-classfile -encoding UTF-8 -cp 'lib/*' -d build/classes @sources
    if ($LASTEXITCODE -ne 0) { throw 'Falha na compilacao' }
    Copy-Item src/main/resources/* build/classes -Recurse -Force
    if ($SomenteClasses) {
        Write-Host 'Compilacao concluida: build/classes'
        return
    }
    & $jarExe --create --file build/ep1.jar -C build/classes .
    if ($LASTEXITCODE -ne 0) { throw 'Falha ao gerar JAR' }
    Write-Host 'Compilacao concluida: build/ep1.jar'
} finally { Pop-Location }
