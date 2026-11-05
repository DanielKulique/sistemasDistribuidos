param([switch]$Diagnostico)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'scripts/ep2-comum.ps1')
Push-Location $PSScriptRoot
try {
    if ($args.Count) { throw 'Uso: preparar-ep2.cmd [--diagnostico]' }
    Initialize-Ep2 -Diagnostico:$Diagnostico
    Write-Host ''
    Write-Host 'Ambiente EP-2 pronto.'
    Write-Host 'Servidor: servidor-ep2.cmd 26000'
    Write-Host 'Cliente:  cliente-ep2.cmd 127.0.0.1 26000'
} catch {
    Write-Host "[ERRO] $($_.Exception.Message)"
    exit 1
} finally { Pop-Location }
