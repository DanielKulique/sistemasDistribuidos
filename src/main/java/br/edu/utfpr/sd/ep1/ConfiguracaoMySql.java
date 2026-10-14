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

/** Credenciais separadas do codigo-fonte. O driver JDBC vem de lib/. */
public final class ConfiguracaoMySql {
    private final String url;
    private final Properties propriedades;

    private ConfiguracaoMySql(String url, String usuario, String senha) {
        this.url = url;
        propriedades = new Properties();
        propriedades.setProperty("user", usuario);
        propriedades.setProperty("password", senha);
    }

    public static ConfiguracaoMySql carregar(Path arquivo) throws IOException {
        Properties config = new Properties();
        if (!Files.isRegularFile(arquivo)) {
            throw new IOException("Configuracao MySQL ausente: " + arquivo + ". Execute ./mysql-iniciar.sh.");
        }
        try (Reader leitor = Files.newBufferedReader(arquivo, StandardCharsets.UTF_8)) {
            config.load(leitor);
        }
        String url = config.getProperty("jdbc.url");
        String usuario = config.getProperty("jdbc.user");
        String senha = config.getProperty("jdbc.password");
        if (url == null || !url.startsWith("jdbc:mysql://") || usuario == null || usuario.isBlank() || senha == null) {
            throw new IOException("Configure jdbc.url, jdbc.user e jdbc.password em " + arquivo);
        }
        return new ConfiguracaoMySql(url, usuario, senha);
    }

    public Connection abrir() throws SQLException {
        return DriverManager.getConnection(url, propriedades);
    }
}
