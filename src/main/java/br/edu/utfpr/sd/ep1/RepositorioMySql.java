package br.edu.utfpr.sd.ep1;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/** JDBC com InnoDB, prepared statements e commit/rollback por requisicao. */
public final class RepositorioMySql implements RepositorioUsuarios {
    private static final DateTimeFormatter DATA = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final ConfiguracaoMySql config;

    public RepositorioMySql(ConfiguracaoMySql config) {
        this.config = config;
    }

    private static void iniciar(Connection conexao) throws SQLException {
        conexao.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        conexao.setAutoCommit(false);
        // Lock no MySQL, compartilhado por todas as instancias do servidor da EP-1.
        try (PreparedStatement stmt = conexao.prepareStatement(
                "SELECT id FROM controle_transacoes WHERE id = 1 FOR UPDATE"); ResultSet rs = stmt.executeQuery()) {
            if (!rs.next()) throw new SQLException("Esquema incompleto: execute sql/schema.sql");
        }
    }

    @Override
    public Banco carregar() throws IOException {
        try (Connection conexao = config.abrir()) {
            try {
                iniciar(conexao);
                Banco banco = ler(conexao);
                conexao.commit();
                return banco;
            } catch (SQLException | RuntimeException e) {
                rollback(conexao, e);
                throw e;
            }
        } catch (SQLException e) {
            throw new IOException("Nao foi possivel consultar MySQL; verifique conexao e esquema (SQLState " + e.getSQLState() + ")", e);
        }
    }

    @Override
    public JsonObject transacionar(Function<Banco, JsonObject> operacao) throws IOException {
        try (Connection conexao = config.abrir()) {
            try {
                iniciar(conexao);
                Banco anterior = ler(conexao);
                Banco novo = anterior.copiar();
                JsonObject resposta = operacao.apply(novo);
                salvarAlteracoes(conexao, anterior, novo);
                conexao.commit();
                return resposta;
            } catch (SQLException | RuntimeException e) {
                rollback(conexao, e);
                throw e;
            }
        } catch (SQLException e) {
            throw new IOException("Transacao MySQL falhou (SQLState " + e.getSQLState() + ")", e);
        }
    }

    private static void rollback(Connection conexao, Exception original) {
        try { conexao.rollback(); } catch (SQLException e) { original.addSuppressed(e); }
    }

    private static Banco ler(Connection conexao) throws SQLException {
        Banco banco = new Banco();
        try (PreparedStatement stmt = conexao.prepareStatement(
                "SELECT id, nome, email, senha_hash, senha_salt, perfil, criado_em FROM usuarios ORDER BY id");
                ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                Banco.Usuario u = new Banco.Usuario();
                u.id = rs.getString("id");
                u.user = rs.getString("nome");
                u.email = rs.getString("email");
                u.senhaHash = rs.getString("senha_hash");
                u.senhaSalt = rs.getString("senha_salt");
                u.role = rs.getString("perfil");
                u.createdAt = DATA.format(rs.getObject("criado_em", LocalDateTime.class));
                banco.usuarios.add(u);
            }
        }
        try (PreparedStatement stmt = conexao.prepareStatement(
                "SELECT token, usuario_id, ultimo_uso FROM sessoes ORDER BY token"); ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                Banco.Sessao s = new Banco.Sessao();
                s.token = rs.getString("token");
                s.usuarioId = rs.getString("usuario_id");
                s.ultimoUso = rs.getLong("ultimo_uso");
                banco.sessoes.add(s);
            }
        }
        return banco;
    }

    private static void salvarAlteracoes(Connection conexao, Banco anterior, Banco novo) throws SQLException {
        Map<String, Banco.Usuario> usuariosAntes = new HashMap<>();
        Map<String, Banco.Usuario> usuariosDepois = new HashMap<>();
        anterior.usuarios.forEach(u -> usuariosAntes.put(u.id, u));
        novo.usuarios.forEach(u -> usuariosDepois.put(u.id, u));
        Map<String, Banco.Sessao> sessoesAntes = new HashMap<>();
        Map<String, Banco.Sessao> sessoesDepois = new HashMap<>();
        anterior.sessoes.forEach(s -> sessoesAntes.put(s.token, s));
        novo.sessoes.forEach(s -> sessoesDepois.put(s.token, s));

        // Remove sessoes antigas antes de criar a nova sessao unica do mesmo usuario.
        try (PreparedStatement stmt = conexao.prepareStatement("DELETE FROM sessoes WHERE token = ?")) {
            for (Banco.Sessao s : anterior.sessoes) {
                if (!sessoesDepois.containsKey(s.token)) {
                    stmt.setString(1, s.token);
                    stmt.executeUpdate();
                }
            }
        }
        try (PreparedStatement stmt = conexao.prepareStatement("DELETE FROM usuarios WHERE id = ?")) {
            for (Banco.Usuario u : anterior.usuarios) {
                if (!usuariosDepois.containsKey(u.id)) {
                    stmt.setString(1, u.id);
                    stmt.executeUpdate();
                }
            }
        }
        for (Banco.Usuario u : novo.usuarios) {
            Banco.Usuario antes = usuariosAntes.get(u.id);
            if (antes == null || !iguais(antes, u)) gravarUsuario(conexao, u, antes != null);
        }
        for (Banco.Sessao s : novo.sessoes) {
            Banco.Sessao antes = sessoesAntes.get(s.token);
            if (antes == null || !iguais(antes, s)) gravarSessao(conexao, s, antes != null);
        }
    }

    private static boolean iguais(Object a, Object b) {
        return Protocolo.GSON.toJson(a).equals(Protocolo.GSON.toJson(b));
    }

    private static void gravarUsuario(Connection conexao, Banco.Usuario u, boolean existe) throws SQLException {
        String sql = existe
                ? "UPDATE usuarios SET nome=?, email=?, senha_hash=?, senha_salt=?, perfil=?, criado_em=? WHERE id=?"
                : "INSERT INTO usuarios (nome, email, senha_hash, senha_salt, perfil, criado_em, id) VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement stmt = conexao.prepareStatement(sql)) {
            stmt.setString(1, u.user);
            stmt.setString(2, u.email);
            stmt.setString(3, u.senhaHash);
            stmt.setString(4, u.senhaSalt);
            stmt.setString(5, u.role);
            stmt.setObject(6, LocalDateTime.parse(u.createdAt, DATA));
            stmt.setString(7, u.id);
            stmt.executeUpdate();
        }
    }

    private static void gravarSessao(Connection conexao, Banco.Sessao s, boolean existe) throws SQLException {
        String sql = existe ? "UPDATE sessoes SET usuario_id=?, ultimo_uso=? WHERE token=?"
                : "INSERT INTO sessoes (usuario_id, ultimo_uso, token) VALUES (?, ?, ?)";
        try (PreparedStatement stmt = conexao.prepareStatement(sql)) {
            stmt.setString(1, s.usuarioId);
            stmt.setLong(2, s.ultimoUso);
            stmt.setString(3, s.token);
            stmt.executeUpdate();
        }
    }
}
