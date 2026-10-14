package br.edu.utfpr.sd.ep1;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.function.Function;

/** O estado deve ser lido e alterado dentro da mesma transacao. */
public interface RepositorioUsuarios {
    Banco carregar() throws IOException;
    JsonObject transacionar(Function<Banco, JsonObject> operacao) throws IOException;
}
