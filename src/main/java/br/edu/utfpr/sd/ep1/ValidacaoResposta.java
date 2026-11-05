package br.edu.utfpr.sd.ep1;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Respostas verificadas contra os schemas derivados do AsyncAPI oficial 2.8.0. */
final class ValidacaoResposta {
    private ValidacaoResposta() { }

    static void conferir(JsonObject req, JsonObject res) throws ErroProtocoloException {
        if (contemSegredo(res)) throw new ErroProtocoloException("Servidor devolveu senha na resposta");
        String operacao = Protocolo.string(req, "op"), op = Protocolo.string(res, "op");
        // Excecao acordada: regra de capacidade exige 409, mas o YAML 2.8 nao define sua mensagem.
        if ("update_room".equals(operacao) && "update_room_response".equals(op)
                && "409".equals(Protocolo.string(res, "status"))
                && "Sala possui reservas ativas".equals(Protocolo.string(res, "message"))) return;
        if ("error".equals(op)) {
            if (!Contrato.mensagem("error", res)) throw new ErroProtocoloException("Resposta error fora do contrato");
            return;
        }
        if (operacao == null || !(operacao + "_response").equals(op) || !Contrato.mensagem(op, res))
            throw new ErroProtocoloException("Resposta fora do contrato da operacao");
        if (!"200".equals(Protocolo.string(res, "status"))) return;
        for (String lista : new String[] {"users", "rooms", "reservations"}) {
            if (res.has(lista) && !Integer.toString(res.getAsJsonArray(lista).size()).equals(Protocolo.string(res, "count")))
                throw new ErroProtocoloException("Quantidade inconsistente na listagem");
        }
        if (operacao.equals("read_room") && req.has("date")) {
            if (!res.has("agenda") || !res.get("agenda").isJsonArray()) throw new ErroProtocoloException("Resposta sem agenda");
        }
        if (operacao.equals("list_rooms")) for (JsonElement item : res.getAsJsonArray("rooms")) {
            if (req.has("date") != item.getAsJsonObject().has("available"))
                throw new ErroProtocoloException("Disponibilidade fora do contexto da consulta");
        }
    }

    private static boolean contemSegredo(JsonElement elemento) {
        if (elemento.isJsonObject()) {
            for (var e : elemento.getAsJsonObject().entrySet()) {
                if (e.getKey().equals("password") || e.getKey().equals("senhaHash") || e.getKey().equals("senhaSalt")
                        || e.getKey().equals("senha_hash") || e.getKey().equals("senha_salt") || contemSegredo(e.getValue())) return true;
            }
        } else if (elemento.isJsonArray()) {
            for (JsonElement e : elemento.getAsJsonArray()) if (contemSegredo(e)) return true;
        }
        return false;
    }
}
