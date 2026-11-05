package br.edu.utfpr.sd.ep1;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;

/** Subconjunto de JSON Schema usado pelo contrato oficial, sem dependencia de YAML em execucao. */
final class Contrato {
    static final JsonObject DOCUMENTO;
    static {
        var recurso = Contrato.class.getResourceAsStream("/contrato-2.8.0.json");
        if (recurso == null) throw new IllegalStateException("Recurso do protocolo ausente no classpath");
        try (var leitor = new InputStreamReader(recurso, StandardCharsets.UTF_8)) {
            DOCUMENTO = Protocolo.GSON.fromJson(leitor, JsonObject.class);
        } catch (java.io.IOException e) { throw new ExceptionInInitializerError(e); }
    }

    private Contrato() { }

    static boolean conhecida(String op) {
        return op != null && DOCUMENTO.getAsJsonObject("access").has(op) && !op.equals("linha_invalida");
    }

    static boolean mensagem(String nome, JsonObject valor) {
        JsonObject esquema = DOCUMENTO.getAsJsonObject("messages").getAsJsonObject(nome);
        return esquema != null && validar(valor, esquema);
    }

    static boolean campo(String nome, JsonElement valor) {
        return valor != null && validar(valor, DOCUMENTO.getAsJsonObject("schemas").getAsJsonObject(nome));
    }

    static String erroFormato(String op) {
        JsonObject resposta = DOCUMENTO.getAsJsonObject("messages").getAsJsonObject(op + "_response");
        for (JsonElement alternativa : resposta.getAsJsonArray("oneOf")) {
            JsonObject props = resolver(alternativa.getAsJsonObject()).getAsJsonObject("properties");
            if (props.has("status") && props.getAsJsonObject("status").get("const").getAsString().equals("400"))
                return props.getAsJsonObject("message").get("const").getAsString();
        }
        throw new IllegalStateException("Contrato sem erro de formato");
    }

    private static JsonObject resolver(JsonObject esquema) {
        if (!esquema.has("$ref")) return esquema;
        String[] partes = esquema.get("$ref").getAsString().split("/");
        return DOCUMENTO.getAsJsonObject("schemas").getAsJsonObject(partes[partes.length - 1]);
    }

    static boolean validar(JsonElement valor, JsonObject original) {
        JsonObject s = resolver(original);
        if (s.has("const") && !s.get("const").equals(valor)) return false;
        if (s.has("enum")) {
            boolean encontrou = false;
            for (JsonElement e : s.getAsJsonArray("enum")) if (e.equals(valor)) encontrou = true;
            if (!encontrou) return false;
        }
        if (s.has("type")) {
            String tipo = s.get("type").getAsString();
            if (tipo.equals("string") && (!valor.isJsonPrimitive() || !valor.getAsJsonPrimitive().isString())) return false;
            if (tipo.equals("object") && !valor.isJsonObject()) return false;
            if (tipo.equals("array") && !valor.isJsonArray()) return false;
        }
        if (s.has("pattern") && (!valor.isJsonPrimitive() || !valor.getAsJsonPrimitive().isString()
                || !valor.getAsString().matches(s.get("pattern").getAsString()))) return false;
        if (valor.isJsonObject()) {
            JsonObject objeto = valor.getAsJsonObject();
            if (s.has("required")) for (JsonElement campo : s.getAsJsonArray("required"))
                if (!objeto.has(campo.getAsString())) return false;
            if (s.has("properties")) for (var campo : s.getAsJsonObject("properties").entrySet())
                if (objeto.has(campo.getKey()) && !validar(objeto.get(campo.getKey()), campo.getValue().getAsJsonObject())) return false;
            if (s.has("dependencies")) for (var campo : s.getAsJsonObject("dependencies").entrySet())
                if (objeto.has(campo.getKey())) for (JsonElement dependencia : campo.getValue().getAsJsonArray())
                    if (!objeto.has(dependencia.getAsString())) return false;
        }
        if (valor.isJsonArray()) {
            JsonArray array = valor.getAsJsonArray();
            if (s.has("minItems") && array.size() < s.get("minItems").getAsInt()) return false;
            if (s.has("maxItems") && array.size() > s.get("maxItems").getAsInt()) return false;
            if (s.has("uniqueItems") && s.get("uniqueItems").getAsBoolean()) {
                var vistos = new HashSet<JsonElement>();
                for (JsonElement e : array) if (!vistos.add(e)) return false;
            }
            if (s.has("items")) for (JsonElement e : array) if (!validar(e, s.getAsJsonObject("items"))) return false;
        }
        for (String operador : new String[] {"allOf", "anyOf", "oneOf"}) {
            if (!s.has(operador)) continue;
            int validos = 0;
            JsonArray alternativas = s.getAsJsonArray(operador);
            for (JsonElement a : alternativas) if (validar(valor, a.getAsJsonObject())) validos++;
            if (operador.equals("allOf") && validos != alternativas.size()) return false;
            if (operador.equals("anyOf") && validos == 0) return false;
            if (operador.equals("oneOf") && validos != 1) return false;
        }
        return !s.has("not") || !validar(valor, s.getAsJsonObject("not"));
    }
}
