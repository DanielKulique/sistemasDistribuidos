package br.edu.utfpr.sd.ep1;

import com.google.gson.JsonObject;
import com.mongodb.MongoException;
import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoCollection;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Function;
import org.bson.Document;
import static com.mongodb.client.model.Filters.*;

/** Estado da EP-1 em um documento: commit atomico mesmo em MongoDB standalone. */
public final class RepositorioMongo implements RepositorioUsuarios {
    private final MongoCollection<Document> estados;
    private static final String ID = "ep1";

    public RepositorioMongo(ConfiguracaoMongo config) {
        estados = config.abrir().getCollection("estado");
    }

    private Document ler() {
        Document atual = estados.find(eq("_id", ID)).first();
        if (atual != null) return atual;
        try { estados.insertOne(documento(new Banco(), 0)); }
        catch (MongoWriteException e) { if (e.getError().getCode() != 11000) throw e; }
        atual = estados.find(eq("_id", ID)).first();
        if (atual == null) throw new IllegalStateException("Estado MongoDB ausente");
        return atual;
    }

    private static Document documento(Banco banco, long versao) {
        return Document.parse(Protocolo.GSON.toJson(banco)).append("_id", ID).append("versao", versao);
    }

    private static Banco banco(Document doc) {
        return Protocolo.GSON.fromJson(doc.toJson(), Banco.class);
    }

    @Override public Banco carregar() throws IOException {
        try { return banco(ler()); }
        catch (MongoException e) { throw new IOException("Nao foi possivel acessar MongoDB; confira o servico e config/mongo.properties", e); }
    }

    @Override public JsonObject transacionar(Function<Banco, JsonObject> operacao) throws IOException {
        try {
            for (int tentativa = 0; tentativa < 100; tentativa++) {
                Document atual = ler();
                long versao = ((Number) atual.get("versao")).longValue();
                Banco novo = banco(atual);
                JsonObject resposta = operacao.apply(novo);
                validar(novo);
                // O filtro impede sobrescrever um commit concorrente. Em conflito, refaz as regras.
                if (estados.replaceOne(and(eq("_id", ID), eq("versao", versao)),
                        documento(novo, versao + 1)).getMatchedCount() == 1) return resposta;
                if (Thread.currentThread().isInterrupted()) throw new IOException("Operacao interrompida");
            }
            throw new IOException("Concorrencia elevada no MongoDB; tente novamente");
        } catch (MongoException e) { throw new IOException("Falha ao persistir no MongoDB", e); }
    }

    private static void validar(Banco banco) throws IOException {
        Set<String> ids = new HashSet<>(), nomes = new HashSet<>(), emails = new HashSet<>();
        for (Banco.Usuario u : banco.usuarios) {
            if (u.id == null || u.user == null || u.email == null || !ids.add(u.id)
                    || !nomes.add(u.user) || !emails.add(u.email))
                throw new IOException("Usuario, nome ou email duplicado/invalido");
        }
        Set<String> tokens = new HashSet<>(), usuarios = new HashSet<>();
        for (Banco.Sessao s : banco.sessoes) {
            if (s.token == null || !ids.contains(s.usuarioId) || !tokens.add(s.token) || !usuarios.add(s.usuarioId))
                throw new IOException("Sessao duplicada ou sem usuario");
        }
    }
}
