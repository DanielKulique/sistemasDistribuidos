package br.edu.utfpr.sd.ep1;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Uma secao critica no InnoDB protege inclusive processos/servidores diferentes. */
public final class RepositorioMySql implements RepositorioUsuarios {
    private static final DateTimeFormatter DATA = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm");
    private final ConfiguracaoMySql config;

    public RepositorioMySql(ConfiguracaoMySql config) { this.config = config; }

    private static void iniciar(Connection c) throws SQLException {
        c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        c.setAutoCommit(false);
        try (var s = c.prepareStatement("SELECT id FROM controle_transacoes WHERE id=1 FOR UPDATE"); var r = s.executeQuery()) {
            if (!r.next()) throw new SQLException("Execute sql/schema.sql antes de iniciar");
        }
    }

    @Override public Banco carregar() throws IOException {
        try (Connection c = config.abrir()) {
            try {
                iniciar(c);
                Banco b = ler(c);
                b.validarIntegridade();
                c.commit();
                return b;
            } catch (SQLException | IOException | RuntimeException e) { rollback(c, e); throw e; }
        } catch (SQLException e) { throw new IOException("Falha de leitura MySQL (SQLState " + e.getSQLState() + ")", e); }
    }

    @Override public JsonObject transacionar(Function<Banco, JsonObject> operacao) throws IOException {
        try (Connection c = config.abrir()) {
            try {
                iniciar(c);
                Banco anterior = ler(c), novo = anterior.copiar();
                JsonObject resposta = operacao.apply(novo);
                novo.validarIntegridade();
                salvar(c, anterior, novo);
                c.commit();
                return resposta;
            } catch (SQLException | IOException | RuntimeException e) { rollback(c, e); throw e; }
        } catch (SQLException e) { throw new IOException("Falha de transacao MySQL (SQLState " + e.getSQLState() + ")", e); }
    }

    private static void rollback(Connection c, Exception original) {
        try { c.rollback(); } catch (SQLException e) { original.addSuppressed(e); }
    }

    private static Banco ler(Connection c) throws SQLException {
        Banco b = new Banco();
        try (var s = c.prepareStatement("SELECT proxima_sala,proxima_reserva FROM controle_transacoes WHERE id=1"); var r = s.executeQuery()) {
            if (!r.next()) throw new SQLException("Controle ausente");
            b.proximaSalaId = r.getLong(1); b.proximaReservaId = r.getLong(2);
        }
        try (var s = c.prepareStatement("SELECT id,nome,email,senha_hash,senha_salt,perfil,criado_em FROM usuarios ORDER BY id"); var r = s.executeQuery()) {
            while (r.next()) {
                Banco.Usuario u = new Banco.Usuario();
                u.id = r.getString(1); u.user = r.getString(2); u.email = r.getString(3);
                u.senhaHash = r.getString(4); u.senhaSalt = r.getString(5); u.role = r.getString(6);
                u.createdAt = DATA.format(r.getObject(7, LocalDateTime.class)); b.usuarios.add(u);
            }
        }
        try (var s = c.prepareStatement("SELECT token,usuario_id,ultimo_uso FROM sessoes ORDER BY token"); var r = s.executeQuery()) {
            while (r.next()) {
                Banco.Sessao v = new Banco.Sessao();
                v.token = r.getString(1); v.usuarioId = r.getString(2); v.ultimoUso = r.getLong(3); b.sessoes.add(v);
            }
        }
        Map<String, Banco.Sala> salas = new HashMap<>();
        try (var s = c.prepareStatement("SELECT id,nome,capacidade,localizacao,situacao FROM salas ORDER BY id"); var r = s.executeQuery()) {
            while (r.next()) {
                Banco.Sala v = new Banco.Sala();
                v.id = r.getString(1); v.nome = r.getString(2); v.capacidade = r.getInt(3);
                v.local = r.getString(4); v.status = r.getString(5); b.salas.add(v); salas.put(v.id, v);
            }
        }
        try (var s = c.prepareStatement("SELECT sala_id,recurso FROM sala_recursos ORDER BY sala_id,ordem"); var r = s.executeQuery()) {
            while (r.next()) salas.get(r.getString(1)).recursos.add(r.getString(2));
        }
        try (var s = c.prepareStatement("SELECT sala_id,slot FROM sala_slots ORDER BY sala_id,slot"); var r = s.executeQuery()) {
            while (r.next()) salas.get(r.getString(1)).slots.add(r.getString(2));
        }
        try (var s = c.prepareStatement("SELECT id,sala_id,usuario_id,data,inicio,fim,assunto,participantes,criado_em FROM reservas ORDER BY id"); var r = s.executeQuery()) {
            while (r.next()) {
                Banco.Reserva v = new Banco.Reserva();
                v.id = r.getString(1); v.salaId = r.getString(2); v.usuarioId = r.getString(3);
                v.data = r.getObject(4, LocalDate.class).toString();
                v.inicio = HORA.format(r.getObject(5, LocalTime.class)); v.fim = HORA.format(r.getObject(6, LocalTime.class));
                v.assunto = r.getString(7); v.participantes = r.getInt(8);
                v.createdAt = DATA.format(r.getObject(9, LocalDateTime.class)); b.reservas.add(v);
            }
        }
        return b;
    }

    private static <T> Map<String, T> indice(List<T> lista, Function<T, String> chave) {
        Map<String, T> mapa = new HashMap<>();
        lista.forEach(v -> mapa.put(chave.apply(v), v));
        return mapa;
    }

    private static boolean igual(Object a, Object b) {
        return a != null && Protocolo.GSON.toJson(a).equals(Protocolo.GSON.toJson(b));
    }

    private static void executar(Connection c, String sql, Object... valores) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(sql)) {
            for (int i = 0; i < valores.length; i++) s.setObject(i + 1, valores[i]);
            s.executeUpdate();
        }
    }

    private static void salvar(Connection c, Banco antes, Banco b) throws SQLException {
        var usuarios = indice(antes.usuarios, u -> u.id); var sessoes = indice(antes.sessoes, s -> s.token);
        var salas = indice(antes.salas, s -> s.id); var reservas = indice(antes.reservas, r -> r.id);
        var usuariosDepois = indice(b.usuarios, u -> u.id); var sessoesDepois = indice(b.sessoes, s -> s.token);
        var salasDepois = indice(b.salas, s -> s.id); var reservasDepois = indice(b.reservas, r -> r.id);
        for (var r : antes.reservas) if (!reservasDepois.containsKey(r.id)) executar(c, "DELETE FROM reservas WHERE id=?", r.id);
        for (var s : antes.sessoes) if (!sessoesDepois.containsKey(s.token)) executar(c, "DELETE FROM sessoes WHERE token=?", s.token);
        for (var s : antes.salas) if (!salasDepois.containsKey(s.id)) executar(c, "DELETE FROM salas WHERE id=?", s.id);
        for (var u : antes.usuarios) if (!usuariosDepois.containsKey(u.id)) executar(c, "DELETE FROM usuarios WHERE id=?", u.id);
        for (var u : b.usuarios) if (!igual(usuarios.get(u.id), u)) {
            String sql = usuarios.containsKey(u.id)
                    ? "UPDATE usuarios SET nome=?,email=?,senha_hash=?,senha_salt=?,perfil=?,criado_em=? WHERE id=?"
                    : "INSERT INTO usuarios(nome,email,senha_hash,senha_salt,perfil,criado_em,id) VALUES(?,?,?,?,?,?,?)";
            executar(c, sql, u.user, u.email, u.senhaHash, u.senhaSalt, u.role, u.createdAt, u.id);
        }
        for (var s : b.salas) if (!igual(salas.get(s.id), s)) {
            String sql = salas.containsKey(s.id)
                    ? "UPDATE salas SET nome=?,capacidade=?,localizacao=?,situacao=? WHERE id=?"
                    : "INSERT INTO salas(nome,capacidade,localizacao,situacao,id) VALUES(?,?,?,?,?)";
            executar(c, sql, s.nome, s.capacidade, s.local, s.status, s.id);
            executar(c, "DELETE FROM sala_recursos WHERE sala_id=?", s.id);
            for (int i = 0; i < s.recursos.size(); i++) executar(c, "INSERT INTO sala_recursos(sala_id,ordem,recurso) VALUES(?,?,?)", s.id, i, s.recursos.get(i));
            executar(c, "DELETE FROM sala_slots WHERE sala_id=?", s.id);
            for (String slot : s.slots) executar(c, "INSERT INTO sala_slots(sala_id,slot) VALUES(?,?)", s.id, slot);
        }
        for (var s : b.sessoes) if (!igual(sessoes.get(s.token), s)) {
            String sql = sessoes.containsKey(s.token)
                    ? "UPDATE sessoes SET usuario_id=?,ultimo_uso=? WHERE token=?"
                    : "INSERT INTO sessoes(usuario_id,ultimo_uso,token) VALUES(?,?,?)";
            executar(c, sql, s.usuarioId, s.ultimoUso, s.token);
        }
        for (var r : b.reservas) if (!igual(reservas.get(r.id), r)) {
            String sql = reservas.containsKey(r.id)
                    ? "UPDATE reservas SET sala_id=?,usuario_id=?,data=?,inicio=?,fim=?,assunto=?,participantes=?,criado_em=? WHERE id=?"
                    : "INSERT INTO reservas(sala_id,usuario_id,data,inicio,fim,assunto,participantes,criado_em,id) VALUES(?,?,?,?,?,?,?,?,?)";
            executar(c, sql, r.salaId, r.usuarioId, r.data, r.inicio, r.fim, r.assunto, r.participantes, r.createdAt, r.id);
        }
        executar(c, "UPDATE controle_transacoes SET proxima_sala=?,proxima_reserva=? WHERE id=1", b.proximaSalaId, b.proximaReservaId);
    }
}
