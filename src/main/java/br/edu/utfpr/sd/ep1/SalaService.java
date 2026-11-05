package br.edu.utfpr.sd.ep1;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/** Cadastro, funcionamento UTFPR e agenda; executado dentro da transacao do usuario. */
final class SalaService {
    private final Clock clock;
    static final ZoneId FUSO = ZoneId.of("America/Sao_Paulo");
    private static final JsonObject GRADE = Contrato.DOCUMENTO.getAsJsonObject("schemas")
            .getAsJsonObject("slot").getAsJsonObject("x-horarios");

    SalaService(Clock clock) { this.clock = clock; }

    static JsonObject resposta(String op, String status, String mensagem) {
        return Protocolo.resposta(op + "_response", status, mensagem);
    }

    static List<String> lista(JsonArray array) {
        List<String> valores = new ArrayList<>();
        array.forEach(v -> valores.add(v.getAsString()));
        return valores;
    }

    static boolean dataValida(String data) {
        try { LocalDate.parse(data); return true; } catch (RuntimeException e) { return false; }
    }

    static boolean periodoValido(String inicio, String fim) {
        return inicio != null && fim != null && inicio.compareTo(fim) < 0;
    }

    static boolean sobrepoe(String inicio, String fim, String outroInicio, String outroFim) {
        return inicio.compareTo(outroFim) < 0 && fim.compareTo(outroInicio) > 0;
    }

    static Banco.Sala encontrar(Banco b, String id) {
        // IDs sao numeros representados como strings; zeros a esquerda identificam o mesmo recurso.
        long numero = Long.parseLong(id);
        return b.salas.stream().filter(s -> Long.parseLong(s.id) == numero).findFirst().orElse(null);
    }

    static boolean funcionamento(Banco.Sala sala, String data, String inicio, String fim) {
        String dia = Integer.toString(LocalDate.parse(data).getDayOfWeek().getValue() % 7 + 1);
        boolean ocupou = false;
        for (var aula : GRADE.entrySet()) {
            JsonArray horas = aula.getValue().getAsJsonArray();
            if (sobrepoe(inicio, fim, horas.get(0).getAsString(), horas.get(1).getAsString())) {
                ocupou = true;
                if (!sala.slots.contains(dia + aula.getKey())) return false;
            }
        }
        return ocupou;
    }

    static boolean conflito(Banco b, String sala, String data, String inicio, String fim, String ignorar) {
        return b.reservas.stream().anyMatch(r -> r.salaId.equals(sala) && r.data.equals(data)
                && !r.id.equals(ignorar) && sobrepoe(inicio, fim, r.inicio, r.fim));
    }

    static boolean disponivel(Banco b, Banco.Sala sala, String data, String inicio, String fim) {
        return sala.status.equals("active") && funcionamento(sala, data, inicio, fim)
                && !conflito(b, sala.id, data, inicio, fim, "");
    }

    static JsonObject dados(Banco.Sala s) {
        JsonObject obj = new JsonObject();
        obj.addProperty("room_id", s.id); obj.addProperty("name", s.nome);
        obj.addProperty("capacity", Integer.toString(s.capacidade)); obj.addProperty("location", s.local);
        obj.add("resources", Protocolo.GSON.toJsonTree(s.recursos)); obj.addProperty("room_status", s.status);
        obj.add("slots", Protocolo.GSON.toJsonTree(s.slots));
        return obj;
    }

    JsonObject executar(Banco b, JsonObject req, Banco.Usuario autor, boolean admin) {
        String op = Protocolo.string(req, "op"), data = Protocolo.string(req, "date");
        String inicio = Protocolo.string(req, "start_time"), fim = Protocolo.string(req, "end_time");
        if ((req.has("date") && !dataValida(data)) || (req.has("start_time") && !periodoValido(inicio, fim)))
            return resposta(op, "400", Contrato.erroFormato(op));
        if (op.equals("create_room")) {
            String nome = Protocolo.string(req, "name");
            if (b.salas.stream().anyMatch(s -> s.nome.equals(nome))) return resposta(op, "409", "Sala ja cadastrada");
            if (b.proximaSalaId > 9999999999L) throw new IllegalStateException("IDs esgotados");
            Banco.Sala s = new Banco.Sala();
            s.id = Long.toString(b.proximaSalaId++); s.nome = nome;
            s.capacidade = Integer.parseInt(Protocolo.string(req, "capacity"));
            s.local = Protocolo.string(req, "location"); s.status = "active";
            s.recursos = lista(req.getAsJsonArray("resources")); s.slots = lista(req.getAsJsonArray("slots"));
            b.salas.add(s);
            JsonObject res = resposta(op, "201", "Sala cadastrada com sucesso"); res.addProperty("room_id", s.id); return res;
        }
        if (op.equals("list_rooms")) {
            JsonArray salas = new JsonArray();
            int minimo = req.has("min_capacity") ? Integer.parseInt(Protocolo.string(req, "min_capacity")) : 0;
            for (Banco.Sala s : b.salas) if (s.capacidade >= minimo) {
                JsonObject item = dados(s);
                if (data != null) item.addProperty("available", Boolean.toString(disponivel(b, s, data, inicio, fim)));
                salas.add(item);
            }
            JsonObject res = resposta(op, "200", "Consulta realizada com sucesso");
            res.addProperty("count", Integer.toString(salas.size())); res.add("rooms", salas); return res;
        }
        Banco.Sala s = encontrar(b, Protocolo.string(req, "room_id"));
        if (s == null) return resposta(op, "404", "Sala nao encontrada");
        if (op.equals("check_availability")) {
            JsonObject res = resposta(op, "200", "Consulta realizada com sucesso");
            res.addProperty("room_id", s.id); res.addProperty("date", data);
            res.addProperty("start_time", inicio); res.addProperty("end_time", fim);
            res.addProperty("available", Boolean.toString(disponivel(b, s, data, inicio, fim))); return res;
        }
        if (op.equals("read_room")) {
            JsonObject res = resposta(op, "200", "Consulta realizada com sucesso");
            dados(s).entrySet().forEach(e -> res.add(e.getKey(), e.getValue()));
            if (data != null) res.add("agenda", agenda(b, s, data, autor, admin));
            return res;
        }
        LocalDateTime agora = LocalDateTime.now(clock.withZone(FUSO));
        if (op.equals("delete_room")) {
            if (b.reservas.stream().anyMatch(r -> r.salaId.equals(s.id)
                    && LocalDateTime.parse(r.data + "T" + r.fim).isAfter(agora)))
                return resposta(op, "409", "Sala possui reservas ativas");
            b.reservas.removeIf(r -> r.salaId.equals(s.id)); b.salas.remove(s);
            return resposta(op, "200", "Sala removida com sucesso");
        }
        String nome = Protocolo.string(req, "name"), capacidade = Protocolo.string(req, "capacity");
        if (!nome.isEmpty() && b.salas.stream().anyMatch(v -> !v.id.equals(s.id) && v.nome.equals(nome)))
            return resposta(op, "409", "Sala ja cadastrada");
        if (!capacidade.isEmpty() && b.reservas.stream().anyMatch(r -> r.salaId.equals(s.id)
                && r.participantes > Integer.parseInt(capacidade)
                && LocalDateTime.parse(r.data + "T" + r.fim).isAfter(agora))) {
            // Pendencia explicita do protocolo: 409 exigido, mensagem ainda nao definida.
            return resposta(op, "409", "Sala possui reservas ativas");
        }
        if (!nome.isEmpty()) s.nome = nome;
        if (!capacidade.isEmpty()) s.capacidade = Integer.parseInt(capacidade);
        String local = Protocolo.string(req, "location"), status = Protocolo.string(req, "room_status");
        if (!local.isEmpty()) s.local = local;
        if (!status.isEmpty()) s.status = status;
        s.recursos = lista(req.getAsJsonArray("resources"));
        if (req.has("slots") && !req.getAsJsonArray("slots").isEmpty()) s.slots = lista(req.getAsJsonArray("slots"));
        return resposta(op, "200", "Sala atualizada com sucesso");
    }

    private JsonArray agenda(Banco b, Banco.Sala s, String data, Banco.Usuario autor, boolean admin) {
        JsonArray agenda = new JsonArray();
        String dia = Integer.toString(LocalDate.parse(data).getDayOfWeek().getValue() % 7 + 1);
        for (var aula : GRADE.entrySet()) {
            String codigo = dia + aula.getKey();
            if (!s.slots.contains(codigo)) continue;
            JsonArray horas = aula.getValue().getAsJsonArray();
            String inicio = horas.get(0).getAsString(), fim = horas.get(1).getAsString();
            Banco.Reserva reserva = b.reservas.stream().filter(r -> r.salaId.equals(s.id) && r.data.equals(data)
                    && sobrepoe(inicio, fim, r.inicio, r.fim)).findFirst().orElse(null);
            JsonObject item = new JsonObject();
            item.addProperty("slot", codigo); item.addProperty("start_time", inicio); item.addProperty("end_time", fim);
            item.addProperty("slot_status", reserva != null || s.status.equals("inactive") ? "indisponivel" : "livre");
            if (reserva != null) {
                Banco.Usuario dono = b.usuarios.stream().filter(u -> u.id.equals(reserva.usuarioId)).findFirst().orElseThrow();
                item.addProperty("user", dono.user);
                if (admin || dono.id.equals(autor.id)) item.addProperty("reservation_id", reserva.id);
            }
            agenda.add(item);
        }
        return agenda;
    }
}
