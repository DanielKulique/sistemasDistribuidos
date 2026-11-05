package br.edu.utfpr.sd.ep1;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

/** Configuracao externa e abertura JDBC; nao executa SQL. */
public final class ConfiguracaoMySql {
    private final String url;
    private final Properties propriedades = new Properties();

    private ConfiguracaoMySql(String host, int porta, String banco, String usuario, String senha) {
        // MariaDB Connector/J atende MySQL e MariaDB usando este mesmo esquema de URL.
        url = "jdbc:mariadb://" + host + ":" + porta + "/" + banco;
        propriedades.setProperty("user", usuario);
        propriedades.setProperty("password", senha);
        propriedades.setProperty("connectTimeout", "5000");
        propriedades.setProperty("socketTimeout", "10000");
        // MySQL 8 usa caching_sha2_password; a primeira conexao TCP local precisa da chave RSA.
        if (host.equals("127.0.0.1") || host.equalsIgnoreCase("localhost"))
            propriedades.setProperty("allowPublicKeyRetrieval", "true");
    }

    public static ConfiguracaoMySql carregar(Path arquivo) throws IOException {
        if (!Files.isRegularFile(arquivo)) {
            throw new IOException("Configuracao ausente: " + arquivo
                    + ". Copie config/mysql.properties.example e preencha os dados locais.");
        }
        Properties config = new Properties();
        try (Reader leitor = Files.newBufferedReader(arquivo, StandardCharsets.UTF_8)) {
            config.load(leitor);
        }
        String host = obrigatoria(config, "host");
        String banco = obrigatoria(config, "database");
        String usuario = obrigatoria(config, "user");
        String textoPorta = obrigatoria(config, "port");
        if (!host.matches("[A-Za-z0-9.-]+")) {
            throw new IOException("host deve ser um nome de maquina ou endereco IPv4");
        }
        if (!banco.matches("[A-Za-z0-9_]+")) {
            throw new IOException("database deve conter somente letras, numeros ou sublinhado");
        }
        int porta;
        try {
            porta = Integer.parseInt(textoPorta);
            if (porta < 1 || porta > 65535) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            throw new IOException("port deve ser um numero entre 1 e 65535", e);
        }
        String senha = config.getProperty("password");
        if (senha == null) throw new IOException("Propriedade obrigatoria ausente: password (pode ser vazia)");
        return new ConfiguracaoMySql(host, porta, banco, usuario, senha);
    }

    private static String obrigatoria(Properties config, String chave) throws IOException {
        String valor = config.getProperty(chave);
        if (valor == null || valor.isBlank()) {
            throw new IOException("Propriedade obrigatoria ausente ou vazia: " + chave);
        }
        return valor.trim();
    }

    public Connection abrir() throws SQLException {
        return DriverManager.getConnection(url, propriedades);
    }
}
