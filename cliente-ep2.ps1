param([string]$Servidor = '127.0.0.1', [string]$Porta = '26000')
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'scripts/ep2-comum.ps1')
Push-Location $PSScriptRoot
try {
    if ($args.Count -or [string]::IsNullOrWhiteSpace($Servidor)) { throw 'Uso: cliente-ep2.cmd [endereco porta]' }
    $numeroPorta = ConvertTo-Ep2Port $Porta
    $script:JavaEp2 = Get-Ep2Java
    Invoke-Ep2Build
    Write-Host "Abrindo cliente Swing para ${Servidor}:$numeroPorta. Os JSONs aparecem neste terminal."
    # O cliente de outra maquina nao precisa de MySQL nem de mysql.properties local.
    & $script:JavaEp2 -cp $script:ClasspathEp2 br.edu.utfpr.sd.ep1.Cliente $Servidor $numeroPorta
    if ($LASTEXITCODE -ne 0) { throw "Cliente encerrado com erro (codigo $LASTEXITCODE)." }
} catch {
    Write-Host "[ERRO] $($_.Exception.Message)"
    exit 1
} finally { Pop-Location }
