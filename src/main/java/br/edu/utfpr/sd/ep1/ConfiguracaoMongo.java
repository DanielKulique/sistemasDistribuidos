package br.edu.utfpr.sd.ep1;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/** Conexao compartilhada por configuracao; credenciais ficam fora do codigo. */
public final class ConfiguracaoMongo implements AutoCloseable {
    private final MongoClient cliente;
    private final String banco;

    private ConfiguracaoMongo(String uri, String banco) {
        this.banco = banco;
        cliente = MongoClients.create(MongoClientSettings.builder()
                .applyConnectionString(new ConnectionString(uri))
                .applyToClusterSettings(b -> b.serverSelectionTimeout(5, TimeUnit.SECONDS))
                .applyToSocketSettings(b -> b.connectTimeout(5, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS))
                .build());
    }

    public static ConfiguracaoMongo carregar(Path arquivo) throws IOException {
        if (!Files.isRegularFile(arquivo)) throw new IOException(
                "Configuracao MongoDB ausente: " + arquivo + ". Copie config/mongo.properties.example.");
        Properties props = new Properties();
        try (var leitor = Files.newBufferedReader(arquivo, StandardCharsets.UTF_8)) { props.load(leitor); }
        String uri = props.getProperty("mongo.uri", "").trim();
        String banco = props.getProperty("mongo.database", "").trim();
        if (!(uri.startsWith("mongodb://") || uri.startsWith("mongodb+srv://")) || banco.isEmpty())
            throw new IOException("Configure mongo.uri e mongo.database em " + arquivo);
        try { return new ConfiguracaoMongo(uri, banco); }
        catch (IllegalArgumentException e) { throw new IOException("Configuracao MongoDB invalida", e); }
    }

    public com.mongodb.client.MongoDatabase abrir() { return cliente.getDatabase(banco); }
    public String banco() { return banco; }
    @Override public void close() { cliente.close(); }
}
