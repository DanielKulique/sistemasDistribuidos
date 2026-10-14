# Carregado (source) pelo entrypoint oficial da imagem MySQL.
# A senha gerada pelo inicializador e hexadecimal para uso seguro no SQL.
[[ "$MYSQL_TEST_PASSWORD" =~ ^[a-f0-9]{48}$ ]] || { echo 'Senha de testes invalida.' >&2; exit 1; }
docker_process_sql <<SQL
REVOKE ALL PRIVILEGES, GRANT OPTION FROM 'ep1'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON ep1_reserva_salas.* TO 'ep1'@'%';
CREATE DATABASE IF NOT EXISTS ep1_reserva_salas_teste CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER IF NOT EXISTS 'ep1_teste'@'%' IDENTIFIED BY '$MYSQL_TEST_PASSWORD';
GRANT SELECT, INSERT, UPDATE, DELETE ON ep1_reserva_salas_teste.* TO 'ep1_teste'@'%';
USE ep1_reserva_salas_teste;
SOURCE /docker-entrypoint-initdb.d/01-schema.sql;
SQL
