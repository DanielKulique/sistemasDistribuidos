param([string]$Porta = '26000')
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'scripts/ep2-comum.ps1')
Push-Location $PSScriptRoot
try {
    if ($args.Count) { throw 'Uso: servidor-ep2.cmd [porta]' }
    $numeroPorta = ConvertTo-Ep2Port $Porta
    $ocupada = [System.Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners() |
        Where-Object { $_.Port -eq $numeroPorta }
    if ($ocupada) { throw "Porta TCP $numeroPorta ocupada. Escolha outra porta; nenhum processo sera encerrado." }
    Initialize-Ep2
    Write-Host "Iniciando servidor TCP na porta $numeroPorta. Ctrl+C encerra somente este servidor."
    & $script:JavaEp2 -cp $script:ClasspathEp2 br.edu.utfpr.sd.ep1.Servidor $numeroPorta 'config/mysql.properties'
    if ($LASTEXITCODE -ne 0) { throw "Servidor encerrado com erro (codigo $LASTEXITCODE)." }
} catch {
    Write-Host "[ERRO] $($_.Exception.Message)"
    exit 1
} finally { Pop-Location }
