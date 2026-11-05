# Compativel com Windows PowerShell 5.1. Sem mudancas de politica, servicos ou privilegios.
$script:RaizEp2 = Split-Path $PSScriptRoot -Parent
$script:ClasspathEp2 = "build/classes$([IO.Path]::PathSeparator)lib/*"

function ConvertTo-Ep2Port([string]$Texto) {
    $numero = 0
    if ($Texto -notmatch '^\d+$' -or ![int]::TryParse($Texto, [ref]$numero) -or $numero -lt 1 -or $numero -gt 65535) {
        throw 'A porta deve ser um numero entre 1 e 65535.'
    }
    return $numero
}

function Get-Ep2Version([string]$Executavel) {
    $info = New-Object System.Diagnostics.ProcessStartInfo
    $info.FileName = $Executavel
    $info.Arguments = '-version'
    $info.UseShellExecute = $false
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $processo = New-Object System.Diagnostics.Process
    $processo.StartInfo = $info
    try {
        [void]$processo.Start()
        $saida = $processo.StandardOutput.ReadToEnd() + $processo.StandardError.ReadToEnd()
        $processo.WaitForExit()
        if ($processo.ExitCode -ne 0 -or $saida -notmatch '(?:version\s+"|javac\s+)(\d+)(?:\.(\d+))?') {
            throw 'Versao Java nao identificada.'
        }
        $principal = [int]$Matches[1]
        if ($principal -eq 1) { $principal = [int]$Matches[2] }
        if ($principal -lt 17) { throw 'Java antigo.' }
        return ($saida -split "`r?`n")[0]
    } finally { $processo.Dispose() }
}

function Get-Ep2Java([switch]$Diagnostico) {
    try {
        $java = (Get-Command java -CommandType Application -ErrorAction Stop | Select-Object -First 1).Source
        $versao = Get-Ep2Version $java
    } catch { throw 'Java 17+ nao encontrado. Disponibilize um JDK 17+ no PATH da sessao; nada sera instalado automaticamente.' }
    if ($Diagnostico) { Write-Host "Java: $versao"; Write-Host "Executavel Java: $java" }
    return $java
}

function Get-Ep2Settings([string]$Arquivo) {
    $valores = @{}
    # Somente campos nao secretos. O JDBC faz a leitura definitiva de Java Properties.
    foreach ($linha in [IO.File]::ReadAllLines($Arquivo, [Text.Encoding]::UTF8)) {
        if ($linha -match '^\s*(host|port|database|user)\s*[=:]\s*(.*?)\s*$') {
            $valores[$Matches[1]] = $Matches[2]
        }
    }
    foreach ($chave in @('host', 'port', 'database', 'user')) {
        if ([string]::IsNullOrWhiteSpace($valores[$chave])) { throw "Configuracao invalida: falta $chave. Use o formato chave=valor do exemplo." }
    }
    if ($valores.host -notmatch '^[A-Za-z0-9.-]+$' -or $valores.database -notmatch '^[A-Za-z0-9_]+$') {
        throw 'Configuracao invalida: host/database devem usar o formato simples do exemplo, sem escapes.'
    }
    $valores.port = ConvertTo-Ep2Port $valores.port
    if ($valores.database -cne 'ep1_reserva_salas') { throw 'Os launchers usam somente database=ep1_reserva_salas. Testes sao executados separadamente.' }
    return $valores
}

function Test-Ep2MySql([string]$Endereco, [int]$Porta) {
    $tcp = New-Object Net.Sockets.TcpClient
    $conectado = $false
    try {
        $conectar = $tcp.ConnectAsync($Endereco, $Porta)
        if (!$conectar.Wait(3000)) { throw 'Timeout TCP.' }
        $conectado = $true
        $fluxo = $tcp.GetStream()
        $fluxo.ReadTimeout = 3000
        # Handshake MySQL: cabecalho de 4 bytes, protocolo 10 e versao terminada por NUL.
        $cabecalho = New-Object byte[] 4
        $lidos = 0
        while ($lidos -lt 4) {
            $n = $fluxo.Read($cabecalho, $lidos, 4 - $lidos)
            if (!$n) { throw 'Handshake incompleto.' }
            $lidos += $n
        }
        $tamanho = [int]$cabecalho[0] + 256 * [int]$cabecalho[1] + 65536 * [int]$cabecalho[2]
        if ($tamanho -lt 2 -or $tamanho -gt 1048576 -or $cabecalho[3] -ne 0 -or $fluxo.ReadByte() -ne 10) {
            throw 'A porta nao respondeu com o protocolo MySQL/MariaDB.'
        }
        $versao = New-Object Text.StringBuilder
        $terminou = $false
        for ($i = 1; $i -lt [Math]::Min($tamanho, 128); $i++) {
            $b = $fluxo.ReadByte()
            if ($b -eq 0) { $terminou = $true; break }
            if ($b -lt 32 -or $b -gt 126) { throw 'Handshake invalido.' }
            [void]$versao.Append([char]$b)
        }
        if (!$terminou -or $versao.ToString() -notmatch '^\d+\.\d+') { throw 'Handshake invalido.' }
    } catch {
        if ($conectado) {
            throw "A porta ${Endereco}:$Porta aceita TCP, mas nao respondeu com um handshake MySQL/MariaDB valido dentro de 3 segundos. Confira o processo/porta; nenhum processo foi encerrado ou reiniciado."
        }
        throw "MySQL/MariaDB inacessivel em ${Endereco}:$Porta (conexao recusada, timeout ou host invalido). Confira host/porta e solicite o banco ja preparado a equipe responsavel. Nenhum servico foi iniciado ou reiniciado."
    } finally { $tcp.Dispose() }
}

function Show-Ep2Diagnostic($Config) {
    Write-Host "Raiz do projeto: $script:RaizEp2"
    Write-Host "MySQL: $($Config.host):$($Config.port); banco: $($Config.database); usuario: $($Config.user)"
    Write-Host "mysql.properties existe: $(Test-Path -LiteralPath (Join-Path $script:RaizEp2 'config/mysql.properties') -PathType Leaf)"
    Write-Host "MariaDB Connector/J existe: $(Test-Path -LiteralPath (Join-Path $script:RaizEp2 'lib/mariadb-java-client-3.5.11.jar') -PathType Leaf)"
    Write-Host "build/classes existe: $(Test-Path -LiteralPath (Join-Path $script:RaizEp2 'build/classes') -PathType Container)"
    if ($Config.host -in @('127.0.0.1', 'localhost') -and (Get-Command Get-NetTCPConnection -ErrorAction SilentlyContinue)) {
        try {
            $escutas = @(Get-NetTCPConnection -LocalPort $Config.port -State Listen -ErrorAction Stop)
            foreach ($escuta in $escutas) {
                $processoBanco = Get-Process -Id $escuta.OwningProcess -ErrorAction Stop
                Write-Host "Processo na porta $($Config.port): $($processoBanco.ProcessName) (PID $($escuta.OwningProcess))"
            }
        } catch { Write-Host 'Processo da porta: consulta indisponivel para este usuario; o teste TCP/JDBC continua.' }
    } else { Write-Host 'Processo da porta: consulta local indisponivel; o teste TCP/JDBC continua.' }
}

function ConvertTo-Ep2Property([string]$Valor) {
    # Properties.load interpreta barras e whitespace; preserve literalmente a senha informada.
    return $Valor.Replace('\', '\\').Replace("`r", '\r').Replace("`n", '\n').Replace("`t", '\t').Replace(' ', '\ ')
}

function Initialize-Ep2Config([string]$Arquivo, [string]$Exemplo) {
    if (Test-Path -LiteralPath $Arquivo -PathType Leaf) {
        Write-Host '[OK] config/mysql.properties encontrado e preservado.'
        return
    }
    if (Test-Path -LiteralPath $Arquivo) { throw 'config/mysql.properties deve ser um arquivo.' }
    Copy-Item -LiteralPath $Exemplo -Destination $Arquivo -ErrorAction Stop
    $senha = $null
    try {
        if (Test-Path Env:EP2_MYSQL_PASSWORD) {
            $senha = $env:EP2_MYSQL_PASSWORD
        } else {
            $segredo = Read-Host 'Senha local do usuario MySQL ep1 (Enter para senha vazia)' -AsSecureString
            $ponteiro = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($segredo)
            try { $senha = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($ponteiro) }
            finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ponteiro); $segredo.Dispose() }
        }
        $escapada = ConvertTo-Ep2Property $senha
        $linhas = [IO.File]::ReadAllLines($Arquivo, [Text.Encoding]::UTF8)
        $linhas = @($linhas | ForEach-Object { if ($_ -match '^password=') { 'password=' + $escapada } else { $_ } })
        [IO.File]::WriteAllLines($Arquivo, [string[]]$linhas, (New-Object Text.UTF8Encoding($false)))
        Write-Host '[OK] config/mysql.properties criado a partir do exemplo; senha somente no arquivo local ignorado pelo Git.'
    } finally { $senha = $null; $escapada = $null }
}

function Invoke-Ep2Build {
    try {
        $compilador = (Get-Command javac -CommandType Application -ErrorAction Stop | Select-Object -First 1).Source
        [void](Get-Ep2Version $compilador)
    } catch { throw 'JDK 17+ (javac) nao encontrado. Disponibilize um JDK no PATH da sessao; nenhuma instalacao sera feita.' }
    foreach ($jar in @('gson-2.14.0.jar', 'mariadb-java-client-3.5.11.jar')) {
        if (!(Test-Path -LiteralPath (Join-Path $script:RaizEp2 "lib/$jar") -PathType Leaf)) { throw "Dependencia ausente: lib/$jar. Copie a pasta lib completa do repositorio." }
    }
    # No Windows, sempre um novo processo com bypass restrito a ele. O ramo Unix permite validar o script sem mudar Linux/Bash.
    $powershell = if ($env:OS -eq 'Windows_NT') { 'powershell.exe' } else { (Get-Process -Id $PID).Path }
    & $powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $script:RaizEp2 'build.ps1') -SomenteClasses
    if ($LASTEXITCODE -ne 0) { throw "Falha na compilacao (codigo $LASTEXITCODE)." }
    foreach ($classe in @('Servidor', 'Cliente', 'TesteConexaoMySql', 'VerificacaoBancoMySql')) {
        if (!(Test-Path -LiteralPath (Join-Path $script:RaizEp2 "build/classes/br/edu/utfpr/sd/ep1/$classe.class"))) { throw "Classe nao encontrada apos compilacao: $classe." }
    }
    Write-Host '[OK] Compilacao concluida.'
}

function Initialize-Ep2([switch]$Diagnostico) {
    Write-Host '[1/5] Verificando Java...'
    $script:JavaEp2 = Get-Ep2Java -Diagnostico:$Diagnostico
    Write-Host '[OK] Java encontrado.'
    $arquivo = Join-Path $script:RaizEp2 'config/mysql.properties'
    $exemplo = Join-Path $script:RaizEp2 'config/mysql.properties.example'
    $origem = if (Test-Path -LiteralPath $arquivo -PathType Leaf) { $arquivo } else { $exemplo }
    $config = Get-Ep2Settings $origem
    if ($Diagnostico) { Show-Ep2Diagnostic $config }
    Write-Host '[2/5] Verificando MySQL/MariaDB...'
    Test-Ep2MySql $config.host $config.port
    Write-Host "[OK] Servidor MySQL/MariaDB encontrado em $($config.host):$($config.port); sera reutilizado."
    Write-Host '[3/5] Verificando configuracao local...'
    Initialize-Ep2Config $arquivo $exemplo
    # Nao propagar a variavel opcional de senha aos processos filhos.
    Remove-Item Env:EP2_MYSQL_PASSWORD -ErrorAction SilentlyContinue
    Write-Host '[4/5] Compilando projeto...'
    Invoke-Ep2Build
    Write-Host '[5/5] Testando JDBC, tabelas e permissoes...'
    & $script:JavaEp2 -cp $script:ClasspathEp2 br.edu.utfpr.sd.ep1.TesteConexaoMySql 'config/mysql.properties' '--verificar-ep2'
    if ($LASTEXITCODE -ne 0) {
        throw 'MySQL esta ativo, mas o banco EP-2 ainda nao esta preparado ou acessivel. Veja o motivo acima. Confira config/mysql.properties; schema/permissoes precisam ser preparados uma unica vez por quem ja possui permissao no banco, sem elevacao do Windows. Nada foi apagado ou provisionado automaticamente.'
    }
    Write-Host '[OK] Conexao valida com ep1_reserva_salas; tabelas e SELECT/INSERT/UPDATE/DELETE verificados sem alterar registros.'
}
