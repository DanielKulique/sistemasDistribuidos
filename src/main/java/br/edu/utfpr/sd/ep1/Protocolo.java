package br.edu.utfpr.sd.ep1;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

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
        // Gson aceita nomes repetidos por padrao. Verifica-os antes de materializar
        // o objeto para que op/token nao mudem conforme a biblioteca do receptor.
        try (JsonReader leitor = new JsonReader(new StringReader(texto))) {
            leitor.setStrictness(Strictness.STRICT);
            leitor.setNestingLimit(256);
            verificarChaves(leitor);
            if (leitor.peek() != JsonToken.END_DOCUMENT) throw new IllegalArgumentException("Mais de um JSON na linha");
        } catch (IOException e) {
            throw new IllegalArgumentException("JSON invalido", e);
        }
        JsonElement valor = GSON.fromJson(texto, JsonElement.class);
        if (valor == null || !valor.isJsonObject()) {
            throw new IllegalArgumentException("Mensagem deve ser um objeto JSON");
        }
        return valor.getAsJsonObject();
    }

    private static void verificarChaves(JsonReader leitor) throws IOException {
        switch (leitor.peek()) {
            case BEGIN_OBJECT -> {
                leitor.beginObject();
                Set<String> nomes = new HashSet<>();
                while (leitor.hasNext()) {
                    if (!nomes.add(leitor.nextName())) throw new IllegalArgumentException("Chave JSON repetida");
                    verificarChaves(leitor);
                }
                leitor.endObject();
            }
            case BEGIN_ARRAY -> {
                leitor.beginArray();
                while (leitor.hasNext()) verificarChaves(leitor);
                leitor.endArray();
            }
            default -> leitor.skipValue();
        }
    }

    public static void enviar(OutputStream saida, JsonObject objeto) throws IOException {
        saida.write(codificar(objeto));
        saida.flush();
    }

    public static byte[] codificar(JsonObject objeto) throws IOException {
        byte[] bytes = (GSON.toJson(objeto) + "\n").getBytes(StandardCharsets.UTF_8);
        if (bytes.length > LIMITE_BYTES) throw new IOException("Mensagem excede o tamanho maximo");
        return bytes;
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
