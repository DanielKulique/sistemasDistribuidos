CREATE DATABASE IF NOT EXISTS ep1_reserva_salas
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

USE ep1_reserva_salas;

-- Todas as instancias JDBC bloqueiam esta linha antes de ler e alterar o estado.
CREATE TABLE IF NOT EXISTS controle_transacoes (
    id TINYINT UNSIGNED NOT NULL PRIMARY KEY,
    proxima_sala BIGINT UNSIGNED NOT NULL DEFAULT 1,
    proxima_reserva BIGINT UNSIGNED NOT NULL DEFAULT 1,
    CONSTRAINT ck_controle_unico CHECK (id = 1)
) ENGINE=InnoDB;
INSERT IGNORE INTO controle_transacoes (id) VALUES (1);

CREATE TABLE IF NOT EXISTS usuarios (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    nome VARCHAR(30) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    -- O Service nao limita o comprimento do email e verifica sua unicidade.
    -- Preservar essa verificacao na transacao do futuro repositorio JDBC.
    email TEXT CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    senha_hash VARCHAR(44) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    senha_salt VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    perfil ENUM('user', 'admin') CHARACTER SET ascii COLLATE ascii_bin
        NOT NULL DEFAULT 'user',
    criado_em DATETIME NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_usuarios_nome UNIQUE (nome)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS salas (
    id BIGINT UNSIGNED NOT NULL PRIMARY KEY,
    nome VARCHAR(30) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    capacidade SMALLINT UNSIGNED NOT NULL,
    localizacao VARCHAR(60) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    situacao ENUM('active', 'inactive') NOT NULL DEFAULT 'active',
    CONSTRAINT uq_salas_nome UNIQUE (nome),
    CONSTRAINT ck_salas_capacidade CHECK (capacidade BETWEEN 1 AND 9999)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS sala_recursos (
    sala_id BIGINT UNSIGNED NOT NULL,
    ordem INT UNSIGNED NOT NULL,
    recurso TEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    PRIMARY KEY (sala_id, ordem),
    CONSTRAINT fk_recursos_sala FOREIGN KEY (sala_id) REFERENCES salas(id) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS sala_slots (
    sala_id BIGINT UNSIGNED NOT NULL,
    slot CHAR(3) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (sala_id, slot),
    CONSTRAINT fk_slots_sala FOREIGN KEY (sala_id) REFERENCES salas(id) ON DELETE CASCADE,
    CONSTRAINT ck_slot_utfpr CHECK (slot REGEXP '^[2-7](M[1-6]|T[1-6]|N[1-5])$')
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS reservas (
    id BIGINT UNSIGNED NOT NULL PRIMARY KEY,
    sala_id BIGINT UNSIGNED NOT NULL,
    usuario_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    data DATE NOT NULL,
    inicio TIME NOT NULL,
    fim TIME NOT NULL,
    assunto VARCHAR(60) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    participantes SMALLINT UNSIGNED NOT NULL,
    criado_em DATETIME NOT NULL,
    CONSTRAINT fk_reservas_sala FOREIGN KEY (sala_id) REFERENCES salas(id) ON DELETE CASCADE,
    CONSTRAINT fk_reservas_usuario FOREIGN KEY (usuario_id) REFERENCES usuarios(id) ON DELETE CASCADE,
    CONSTRAINT ck_reservas_periodo CHECK (fim > inicio),
    CONSTRAINT ck_reservas_participantes CHECK (participantes BETWEEN 1 AND 9999),
    INDEX idx_reservas_periodo (sala_id, data, inicio, fim),
    INDEX idx_reservas_usuario (usuario_id, data)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS sessoes (
    token CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    usuario_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ultimo_uso BIGINT NOT NULL COMMENT 'Epoch em milissegundos',
    PRIMARY KEY (token),
    CONSTRAINT uq_sessoes_usuario UNIQUE (usuario_id),
    CONSTRAINT fk_sessoes_usuario FOREIGN KEY (usuario_id)
        REFERENCES usuarios (id) ON DELETE CASCADE
) ENGINE=InnoDB;
