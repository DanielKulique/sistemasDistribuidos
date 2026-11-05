package br.edu.utfpr.sd.ep1;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** Reservas persistidas alimentam disponibilidade, agenda e dependencias da EP-2. */
final class ReservaService {
    private final Clock clock;
    ReservaService(Clock clock) { this.clock = clock; }

    JsonObject executar(Banco b, JsonObject req, Banco.Usuario autor, boolean admin) {
        String op = Protocolo.string(req, "op"), data = Protocolo.string(req, "date");
        if (data != null && !SalaService.dataValida(data)) return resposta(op, "400", Contrato.erroFormato(op));
        if (op.equals("list_reservations")) {
            boolean todas = "all".equals(Protocolo.string(req, "scope"));
            if (todas && !admin) return resposta(op, "403", "Permissao insuficiente");
            String salaId = Protocolo.string(req, "room_id");
            JsonArray lista = new JsonArray();
            for (var r : b.reservas) if ((todas || r.usuarioId.equals(autor.id)) && (data == null || data.equals(r.data))
                    && (salaId == null || Long.parseLong(salaId) == Long.parseLong(r.salaId))) lista.add(dados(b, r, false));
            JsonObject res = resposta(op, "200", "Consulta realizada com sucesso");
            res.addProperty("count", Integer.toString(lista.size())); res.add("reservations", lista); return res;
        }
        Banco.Reserva r;
        boolean criar = op.equals("create_reservation");
        if (criar) {
            r = new Banco.Reserva(); r.usuarioId = autor.id;
            r.data = data; r.salaId = Protocolo.string(req, "room_id");
        } else {
            long id = Long.parseLong(Protocolo.string(req, "reservation_id"));
            r = b.reservas.stream().filter(v -> Long.parseLong(v.id) == id).findFirst().orElse(null);
            if (r == null) return resposta(op, "404", "Reserva nao encontrada");
            if (!r.usuarioId.equals(autor.id) && (!admin || op.equals("update_reservation")))
                return resposta(op, "403", "Permissao insuficiente");
            if (op.equals("read_reservation")) {
                JsonObject res = resposta(op, "200", "Consulta realizada com sucesso");
                dados(b, r, true).entrySet().forEach(e -> res.add(e.getKey(), e.getValue())); return res;
            }
            if (!LocalDateTime.parse(r.data + "T" + r.fim).isAfter(LocalDateTime.now(clock.withZone(SalaService.FUSO))))
                return resposta(op, "409", "Reserva ja encerrada");
            if (op.equals("delete_reservation")) {
                b.reservas.remove(r); return resposta(op, "200", "Reserva cancelada com sucesso");
            }
        }
        String inicio = valor(req, "start_time", r.inicio), fim = valor(req, "end_time", r.fim);
        String participantes = valor(req, "participants", criar ? null : Integer.toString(r.participantes));
        if (!SalaService.periodoValido(inicio, fim) || LocalDate.parse(r.data).isBefore(LocalDate.now(clock.withZone(SalaService.FUSO))))
            return resposta(op, "400", "Dados da reserva em formato invalido");
        Banco.Sala sala = SalaService.encontrar(b, r.salaId);
        if (sala == null) return resposta(op, "404", "Sala nao encontrada");
        int quantidade = Integer.parseInt(participantes);
        if (quantidade > sala.capacidade) return resposta(op, "400", "Dados da reserva em formato invalido");
        if (criar && !sala.status.equals("active")) return resposta(op, "409", "Sala indisponivel para reserva");
        if (!SalaService.funcionamento(sala, r.data, inicio, fim)) return resposta(op, "409", "Horario fora do funcionamento da sala");
        if (SalaService.conflito(b, sala.id, r.data, inicio, fim, criar ? "" : r.id))
            return resposta(op, "409", "Sala ja reservada no periodo solicitado");
        if (criar) {
            if (b.proximaReservaId > 9999999999L) throw new IllegalStateException("IDs esgotados");
            r.id = Long.toString(b.proximaReservaId++); r.salaId = sala.id;
            r.createdAt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").format(LocalDateTime.now(clock.withZone(SalaService.FUSO)));
        }
        r.inicio = inicio; r.fim = fim; r.participantes = quantidade;
        r.assunto = valor(req, "topic", r.assunto);
        if (criar) b.reservas.add(r);
        JsonObject res = resposta(op, criar ? "201" : "200", criar ? "Reserva realizada com sucesso" : "Reserva atualizada com sucesso");
        if (criar) res.addProperty("reservation_id", r.id);
        return res;
    }

    private static String valor(JsonObject req, String chave, String anterior) {
        String valor = Protocolo.string(req, chave);
        return valor.isEmpty() ? anterior : valor;
    }

    private static JsonObject resposta(String op, String status, String mensagem) {
        return SalaService.resposta(op, status, mensagem);
    }

    private static JsonObject dados(Banco b, Banco.Reserva r, boolean data) {
        JsonObject res = new JsonObject();
        res.addProperty("reservation_id", r.id); res.addProperty("room_id", r.salaId);
        res.addProperty("name", SalaService.encontrar(b, r.salaId).nome);
        res.addProperty("user", b.usuarios.stream().filter(u -> u.id.equals(r.usuarioId)).findFirst().orElseThrow().user);
        res.addProperty("date", r.data); res.addProperty("start_time", r.inicio); res.addProperty("end_time", r.fim);
        res.addProperty("topic", r.assunto); res.addProperty("participants", Integer.toString(r.participantes));
        if (data) res.addProperty("created_at", r.createdAt);
        return res;
    }
}
