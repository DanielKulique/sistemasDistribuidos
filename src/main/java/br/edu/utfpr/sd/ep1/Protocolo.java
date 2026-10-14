package br.edu.utfpr.sd.ep1;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.Strictness;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** JSON estrito em UTF-8, delimitado por LF. O limite inclui o LF. */
public final class Protocolo {
    public static final int LIMITE_BYTES = 8192;
    public static final Gson GSON = new GsonBuilder().setStrictness(Strictness.STRICT).create();

    private Protocolo() { }

    public record Linha(byte[] bytes, boolean excedida) {
        public String texto() throws CharacterCodingException {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        }
    }

    /** Descarta o restante de uma linha grande até LF, sem crescer o buffer. */
    public static Linha ler(InputStream entrada) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        boolean excedida = false;
        boolean recebeu = false;
        int tamanho = 0;
        while (true) {
            int valor = entrada.read();
            if (valor == -1) {
                if (!recebeu) return null;
                throw new EOFException("Conexao encerrada antes do LF");
            }
            recebeu = true;
            if (!excedida && ++tamanho > LIMITE_BYTES) excedida = true;
            if (valor == '\n') return new Linha(buffer.toByteArray(), excedida);
            if (!excedida) buffer.write(valor);
        }
    }

    public static JsonObject interpretar(String texto) {
        if (texto.indexOf('\r') >= 0 || texto.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("JSON deve ocupar uma linha com LF");
        }
        JsonElement valor = GSON.fromJson(texto, JsonElement.class);
        if (valor == null || !valor.isJsonObject()) {
            throw new IllegalArgumentException("Mensagem deve ser um objeto JSON");
        }
        return valor.getAsJsonObject();
    }

    public static void enviar(OutputStream saida, JsonObject objeto) throws IOException {
        byte[] bytes = (GSON.toJson(objeto) + "\n").getBytes(StandardCharsets.UTF_8);
        if (bytes.length > LIMITE_BYTES) throw new IOException("Mensagem excede o tamanho maximo");
        saida.write(bytes);
        saida.flush();
    }

    /** Retorna null para campo ausente, null, numero, booleano, array ou objeto. */
    public static String string(JsonObject objeto, String chave) {
        JsonElement valor = objeto.get(chave);
        return valor != null && valor.isJsonPrimitive() && valor.getAsJsonPrimitive().isString()
                ? valor.getAsString() : null;
    }

    public static JsonObject resposta(String op, String status, String message) {
        JsonObject resposta = new JsonObject();
        resposta.addProperty("op", op);
        resposta.addProperty("status", status);
        resposta.addProperty("message", message);
        return resposta;
    }

    public static JsonObject erro(String message) {
        return resposta("error", "400", message);
    }
}
