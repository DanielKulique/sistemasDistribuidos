#!/usr/bin/env bash
set -euo pipefail
umask 077
PROJECT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
cd -- "$PROJECT_DIR"
for programa in docker openssl; do
    command -v "$programa" >/dev/null || { echo "Programa necessario: $programa" >&2; exit 1; }
done
docker compose version >/dev/null
mkdir -p config
if [[ ! -f config/mysql.env ]]; then
    MYSQL_PORT="${EP1_MYSQL_PORT:-3306}"
    [[ "$MYSQL_PORT" =~ ^[0-9]+$ ]] && (( MYSQL_PORT >= 1 && MYSQL_PORT <= 65535 )) || {
        echo 'EP1_MYSQL_PORT deve estar entre 1 e 65535.' >&2; exit 1;
    }
    ROOT_PASSWORD="$(openssl rand -hex 24)"
    APP_PASSWORD="$(openssl rand -hex 24)"
    TEST_PASSWORD="$(openssl rand -hex 24)"
    cat > config/mysql.env <<CFG
MYSQL_PORT=$MYSQL_PORT
MYSQL_ROOT_PASSWORD=$ROOT_PASSWORD
MYSQL_PASSWORD=$APP_PASSWORD
MYSQL_TEST_PASSWORD=$TEST_PASSWORD
CFG
    for destino in mysql mysql-test; do
        if [[ "$destino" == mysql ]]; then
            banco=ep1_reserva_salas; usuario=ep1; senha="$APP_PASSWORD"
        else
            banco=ep1_reserva_salas_teste; usuario=ep1_teste; senha="$TEST_PASSWORD"
        fi
        cat > "config/$destino.properties" <<CFG
jdbc.url=jdbc:mysql://127.0.0.1:$MYSQL_PORT/$banco?connectionTimeZone=America/Sao_Paulo&sslMode=DISABLED&allowPublicKeyRetrieval=true&connectTimeout=5000&socketTimeout=10000
jdbc.user=$usuario
jdbc.password=$senha
CFG
    done
fi
echo 'Iniciando MySQL do projeto...'
docker compose --env-file config/mysql.env up -d --wait --wait-timeout 60
echo 'MySQL pronto. Banco: ep1_reserva_salas. Testes: ep1_reserva_salas_teste.'
echo 'Conexao Java configurada em config/mysql.properties.'
