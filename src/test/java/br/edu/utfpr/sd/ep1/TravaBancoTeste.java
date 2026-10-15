package br.edu.utfpr.sd.ep1;

import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoCollection;
import java.io.IOException;
import org.bson.Document;
import static com.mongodb.client.model.Filters.eq;

/** Trava entre processos; apenas o banco dedicado pode ser limpo. */
final class TravaBancoTeste implements AutoCloseable {
    private final ConfiguracaoMongo config;
    private final MongoCollection<Document> travas;

    TravaBancoTeste(ConfiguracaoMongo config) throws IOException {
        conferir(config);
        this.config = config;
        travas = config.abrir().getCollection("travas_teste");
        try { travas.insertOne(new Document("_id", "bateria")); }
        catch (MongoWriteException e) {
            if (e.getError().getCode() == 11000) throw new IOException("Outra bateria ja esta usando o banco de testes", e);
            throw e;
        }
    }
    static void conferir(ConfiguracaoMongo config) throws IOException {
        if (!"ep1_reserva_salas_teste".equals(config.banco())) throw new IOException("Banco de testes incorreto");
    }
    void conferirBanco() throws IOException { conferir(config); }
    @Override public void close() { travas.deleteOne(eq("_id", "bateria")); }
}
