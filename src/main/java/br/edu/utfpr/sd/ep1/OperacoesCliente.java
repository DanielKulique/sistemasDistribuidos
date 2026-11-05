package br.edu.utfpr.sd.ep1;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Formularios usam os campos do contrato; permissoes continuam sendo verificadas no servidor. */
final class OperacoesCliente {
    static final Map<String, String> TITULOS = new LinkedHashMap<>();
    static {
        TITULOS.put("admin_list_users", "Listar usuários"); TITULOS.put("admin_read_user", "Consultar usuário");
        TITULOS.put("admin_update_user", "Atualizar usuário"); TITULOS.put("admin_delete_user", "Excluir usuário");
        TITULOS.put("create_room", "Cadastrar sala"); TITULOS.put("list_rooms", "Listar salas");
        TITULOS.put("read_room", "Consultar sala e agenda"); TITULOS.put("update_room", "Atualizar sala");
        TITULOS.put("delete_room", "Excluir sala"); TITULOS.put("check_availability", "Consultar disponibilidade");
        TITULOS.put("create_reservation", "Reservar sala"); TITULOS.put("list_reservations", "Listar reservas");
        TITULOS.put("read_reservation", "Consultar reserva"); TITULOS.put("update_reservation", "Atualizar reserva");
        TITULOS.put("delete_reservation", "Cancelar reserva");
    }
    private OperacoesCliente() { }

    static List<String> grupo(String grupo) {
        return TITULOS.keySet().stream().filter(op -> switch (grupo) {
            case "usuarios" -> op.startsWith("admin_");
            case "salas" -> op.contains("room") || op.equals("check_availability");
            default -> op.contains("reservation");
        }).toList();
    }

    static JsonObject esquema(String op) { return Contrato.DOCUMENTO.getAsJsonObject("messages").getAsJsonObject(op); }
    static List<String> campos(String op) {
        return esquema(op).getAsJsonObject("properties").keySet().stream().filter(k -> !k.equals("op") && !k.equals("token")).toList();
    }
    static boolean obrigatorio(String op, String campo) {
        for (var k : esquema(op).getAsJsonArray("required")) if (k.getAsString().equals(campo)) return true;
        return false;
    }
    static String rotulo(String campo) {
        return switch (campo) {
            case "target_user" -> "Usuário alvo"; case "user" -> "Novo usuário";
            case "password" -> "Nova senha"; case "role" -> "Perfil (user/admin)";
            case "name" -> "Nome da sala"; case "capacity" -> "Capacidade";
            case "room_id" -> "Número da sala"; case "location" -> "Localização";
            case "resources" -> "Recursos, separados por vírgula"; case "slots" -> "Horários UTFPR";
            case "room_status" -> "Situação (active/inactive)"; case "date" -> "Data (AAAA-MM-DD)";
            case "start_time" -> "Início da aula (HH:mm)"; case "end_time" -> "Fim da aula (HH:mm)";
            case "min_capacity" -> "Capacidade mínima"; case "scope" -> "Abrangência (mine/all)";
            case "reservation_id" -> "Número da reserva"; case "topic" -> "Assunto";
            case "participants" -> "Participantes"; case "email" -> "E-mail";
            case "created_at" -> "Criado em"; case "available" -> "Disponível";
            case "slot" -> "Horário"; case "slot_status" -> "Situação do horário";
            case "count" -> "Quantidade";
            default -> campo;
        };
    }

    static void preencher(JsonObject req, String campo, String texto) {
        String op = Protocolo.string(req, "op");
        if (texto.isEmpty() && !obrigatorio(op, campo)) return;
        if (campo.equals("resources") || campo.equals("slots")) {
            JsonArray valores = new JsonArray();
            if (!texto.isBlank()) for (String v : texto.split(campo.equals("slots") ? "[,\\s]+" : ",")) valores.add(v.trim());
            req.add(campo, valores);
        } else req.addProperty(campo, texto);
    }
}
