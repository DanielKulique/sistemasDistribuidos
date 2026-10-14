-- MySQL 8.0+. Execute dentro do banco escolhido; nenhum dado existente e apagado.
CREATE TABLE IF NOT EXISTS controle_transacoes (
    id TINYINT UNSIGNED NOT NULL PRIMARY KEY,
    CONSTRAINT ck_controle_unico CHECK (id = 1)
) ENGINE=InnoDB;
INSERT IGNORE INTO controle_transacoes (id) VALUES (1);

CREATE TABLE IF NOT EXISTS usuarios (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    nome VARCHAR(30) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    email TEXT CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    -- O contrato nao limita email a 255 caracteres. Indexamos seu SHA-256 completo.
    email_sha256 BINARY(32) GENERATED ALWAYS AS (UNHEX(SHA2(email, 256))) STORED,
    senha_hash VARCHAR(44) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    senha_salt VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    perfil ENUM('user', 'admin') NOT NULL DEFAULT 'user',
    criado_em DATETIME NOT NULL,
    CONSTRAINT uq_usuarios_nome UNIQUE (nome),
    CONSTRAINT uq_usuarios_email UNIQUE (email_sha256)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS sessoes (
    token CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    usuario_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ultimo_uso BIGINT NOT NULL COMMENT 'Epoch em milissegundos',
    CONSTRAINT uq_sessoes_usuario UNIQUE (usuario_id),
    CONSTRAINT fk_sessoes_usuario FOREIGN KEY (usuario_id) REFERENCES usuarios (id) ON DELETE CASCADE,
    INDEX idx_sessoes_ultimo_uso (ultimo_uso)
) ENGINE=InnoDB;
