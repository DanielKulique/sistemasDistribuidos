package br.edu.utfpr.sd.ep1;

import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import java.io.IOException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;

/** Autenticacao, whitelist e usuarios. Salas e reservas compartilham a mesma transacao. */
public final class UsuarioService {
    private static final long VALIDADE_MS = Duration.ofMinutes(30).toMillis();
    private static final DateTimeFormatter DATA = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.of("America/Sao_Paulo"));
    private final SecureRandom random = new SecureRandom();
    private final RepositorioUsuarios repositorio;
    private final Clock clock;
    private final SalaService salas;
    private final ReservaService reservas;

    public UsuarioService(RepositorioUsuarios repositorio, Clock clock) throws IOException {
        this.repositorio = repositorio;
        this.clock = clock;
        this.salas = new SalaService(clock);
        this.reservas = new ReservaService(clock);
        if (repositorio.carregar().usuarios.stream().noneMatch(u -> u.role.equals("admin"))) repositorio.transacionar(banco -> {
            if (banco.usuarios.stream().noneMatch(u -> u.role.equals("admin"))) {
                if (banco.usuarios.stream().anyMatch(u -> u.user.equals("admin") || u.email.equals("admin@admin.com")))
                    throw new IllegalStateException("Conta inicial em conflito; preserve os dados e revise o cadastro admin existente");
                Banco.Usuario admin = new Banco.Usuario();
                admin.id = UUID.randomUUID().toString(); admin.user = "admin";
                admin.email = "admin@admin.com"; admin.role = "admin";
                admin.createdAt = DATA.format(clock.instant());
                Senhas.definir(admin, "admin"); banco.usuarios.add(admin);
            }
            return new JsonObject();
        });
    }

    public JsonObject executar(JsonObject req) throws IOException {
        String op = Protocolo.string(req, "op");
        if (op == null) return Protocolo.erro("Requisicao invalida");
        if (!Contrato.conhecida(op)) return Protocolo.erro("Operacao desconhecida");
        JsonObject invalida = validar(req, op);
        if (invalida != null) return invalida;

        return repositorio.transacionar(novo -> switch (op) {
            case "register" -> cadastrar(novo, req);
            case "login" -> login(novo, req);
            default -> autenticada(novo, req, op);
        });
    }

    private JsonObject validar(JsonObject req, String op) {
        if (Contrato.mensagem(op, req)) return null;
        boolean admin = op.equals("admin_update_user");
        if ((admin || op.equals("update_user")) && Contrato.campo("token", req.get("token"))
                && !req.has("email") && "".equals(Protocolo.string(req, "user"))
                && "".equals(Protocolo.string(req, "password"))
                && (!admin || (Contrato.campo("target_user", req.get("target_user")) && "".equals(Protocolo.string(req, "role")))))
            return resposta(op, "400", "Nenhum dado para atualizar");
        return resposta(op, "400", Contrato.erroFormato(op));
    }

    private JsonObject cadastrar(Banco novo, JsonObject req) {
        String user = Protocolo.string(req, "user");
        String email = Protocolo.string(req, "email");
        if (novo.usuarios.stream().anyMatch(u -> u.user.equals(user) || u.email.equals(email))) {
            return resposta("register", "409", "Usuario ou email ja cadastrado");
        }
        Banco.Usuario usuario = new Banco.Usuario();
        usuario.id = UUID.randomUUID().toString();
        usuario.user = user;
        usuario.email = email;
        usuario.role = "user";
        usuario.createdAt = DATA.format(clock.instant());
        Senhas.definir(usuario, Protocolo.string(req, "password"));
        novo.usuarios.add(usuario);
        return resposta("register", "201", "Usuario cadastrado com sucesso");
    }

    private JsonObject login(Banco novo, JsonObject req) {
        Banco.Usuario usuario = novo.usuarios.stream()
                .filter(u -> u.email.equals(Protocolo.string(req, "email"))).findFirst().orElse(null);
        // Credenciais antes de sessao: uma senha errada nunca revela sessao ativa.
        if (usuario == null || !Senhas.conferir(usuario, Protocolo.string(req, "password"))) {
            return resposta("login", "401", "Email ou senha incorretos");
        }
        novo.sessoes.removeIf(this::expirada);
        if (novo.sessoes.stream().anyMatch(s -> s.usuarioId.equals(usuario.id))) {
            return resposta("login", "409", "Usuario ja possui sessao ativa");
        }
        Banco.Sessao sessao = new Banco.Sessao();
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        sessao.token = HexFormat.of().formatHex(bytes);
        sessao.usuarioId = usuario.id;
        sessao.ultimoUso = clock.millis();
        novo.sessoes.add(sessao);
        JsonObject resposta = resposta("login", "200", "Login realizado com sucesso");
        resposta.addProperty("token", sessao.token);
        resposta.addProperty("role", usuario.role);
        return resposta;
    }

    private boolean expirada(Banco.Sessao sessao) {
        return clock.millis() - sessao.ultimoUso >= VALIDADE_MS;
    }

    private JsonObject autenticada(Banco novo, JsonObject req, String op) {
        Banco.Sessao sessao = novo.sessoes.stream()
                .filter(s -> s.token.equals(Protocolo.string(req, "token"))).findFirst().orElse(null);
        if (sessao == null || expirada(sessao)) {
            if (sessao != null) novo.sessoes.remove(sessao);
            return resposta(op, "401", "Token invalido ou expirado");
        }
        Banco.Usuario usuario = novo.usuarios.stream().filter(u -> u.id.equals(sessao.usuarioId)).findFirst().orElseThrow();
        // Whitelist efemera, reconstruida do estado atual protegido pelo repositorio.
        Set<String> whitelist = whitelist(novo);
        boolean admin = whitelist.contains(sessao.token);
        sessao.ultimoUso = clock.millis();
        if ((op.startsWith("admin_") || Set.of("create_room", "update_room", "delete_room").contains(op)) && !admin)
            return resposta(op, "403", "Permissao insuficiente");
        if (op.startsWith("admin_")) return administrativa(novo, req, op);
        if (op.endsWith("_room") || op.equals("list_rooms") || op.equals("check_availability"))
            return salas.executar(novo, req, usuario, admin);
        if (op.endsWith("_reservation") || op.equals("list_reservations"))
            return reservas.executar(novo, req, usuario, admin);
        return switch (op) {
            case "logout" -> {
                novo.sessoes.remove(sessao);
                yield resposta(op, "200", "Logout realizado com sucesso");
            }
            case "read_user" -> {
                JsonObject resposta = resposta(op, "200", "Consulta realizada com sucesso");
                resposta.addProperty("user", usuario.user);
                resposta.addProperty("email", usuario.email);
                resposta.addProperty("role", usuario.role);
                resposta.addProperty("created_at", usuario.createdAt);
                yield resposta;
            }
            case "update_user" -> atualizar(novo, usuario, req);
            case "delete_user" -> remover(novo, usuario, req);
            default -> throw new IllegalStateException("Operacao nao tratada");
        };
    }

    private JsonObject atualizar(Banco novo, Banco.Usuario usuario, JsonObject req) {
        String nome = Protocolo.string(req, "user");
        String senha = Protocolo.string(req, "password");
        if (!nome.isEmpty() && novo.usuarios.stream().anyMatch(u -> !u.id.equals(usuario.id) && u.user.equals(nome))) {
            return resposta("update_user", "409", "Usuario ja esta em uso");
        }
        if (!nome.isEmpty()) usuario.user = nome;
        if (!senha.isEmpty()) Senhas.definir(usuario, senha);
        return resposta("update_user", "200", "Dados atualizados com sucesso");
    }

    private JsonObject remover(Banco novo, Banco.Usuario usuario, JsonObject req) {
        if (!Senhas.conferir(usuario, Protocolo.string(req, "password"))) {
            return resposta("delete_user", "403", "Senha incorreta");
        }
        if (usuario.role.equals("admin") && novo.usuarios.stream().filter(u -> u.role.equals("admin")).count() <= 1) {
            return resposta("delete_user", "403", "Nao e possivel remover o ultimo administrador");
        }
        novo.sessoes.removeIf(s -> s.usuarioId.equals(usuario.id));
        novo.usuarios.remove(usuario);
        novo.reservas.removeIf(r -> r.usuarioId.equals(usuario.id));
        return resposta("delete_user", "200", "Usuario removido com sucesso");
    }

    private Set<String> whitelist(Banco banco) {
        Set<String> administradores = new java.util.HashSet<>();
        banco.usuarios.stream().filter(u -> u.role.equals("admin")).forEach(u -> administradores.add(u.id));
        Set<String> tokens = new java.util.HashSet<>();
        banco.sessoes.stream().filter(s -> !expirada(s) && administradores.contains(s.usuarioId))
                .forEach(s -> tokens.add(s.token));
        return tokens;
    }

    private static JsonObject dados(Banco.Usuario u, boolean data) {
        JsonObject obj = new JsonObject();
        obj.addProperty("user", u.user); obj.addProperty("email", u.email); obj.addProperty("role", u.role);
        if (data) obj.addProperty("created_at", u.createdAt);
        return obj;
    }

    private JsonObject administrativa(Banco b, JsonObject req, String op) {
        if (op.equals("admin_list_users")) {
            JsonObject res = resposta(op, "200", "Consulta realizada com sucesso");
            JsonArray usuarios = new JsonArray();
            b.usuarios.forEach(u -> usuarios.add(dados(u, false)));
            res.addProperty("count", Integer.toString(usuarios.size())); res.add("users", usuarios);
            return res;
        }
        Banco.Usuario alvo = b.usuarios.stream().filter(u -> u.user.equals(Protocolo.string(req, "target_user")))
                .findFirst().orElse(null);
        if (alvo == null) return resposta(op, "404", "Usuario nao encontrado");
        if (op.equals("admin_read_user")) {
            JsonObject res = resposta(op, "200", "Consulta realizada com sucesso");
            dados(alvo, true).entrySet().forEach(e -> res.add(e.getKey(), e.getValue()));
            return res;
        }
        if (alvo.role.equals("admin") && (op.equals("admin_delete_user") || "user".equals(Protocolo.string(req, "role")))
                && b.usuarios.stream().filter(u -> u.role.equals("admin")).count() == 1)
            return resposta(op, "403", "Nao e possivel remover o ultimo administrador");
        if (op.equals("admin_delete_user")) {
            b.reservas.removeIf(r -> r.usuarioId.equals(alvo.id));
            b.sessoes.removeIf(s -> s.usuarioId.equals(alvo.id)); b.usuarios.remove(alvo);
            return resposta(op, "200", "Usuario removido com sucesso");
        }
        String nome = Protocolo.string(req, "user"), senha = Protocolo.string(req, "password");
        if (!nome.isEmpty() && b.usuarios.stream().anyMatch(u -> !u.id.equals(alvo.id) && u.user.equals(nome)))
            return resposta(op, "409", "Usuario ja esta em uso");
        if (!nome.isEmpty()) alvo.user = nome;
        if (!senha.isEmpty()) Senhas.definir(alvo, senha);
        String perfil = Protocolo.string(req, "role");
        if (!perfil.isEmpty()) alvo.role = perfil;
        // Na proxima autorizacao, a whitelist reflete o perfil atual sem novo login.
        return resposta(op, "200", "Dados atualizados com sucesso");
    }

    private static JsonObject resposta(String op, String status, String message) {
        return Protocolo.resposta(op + "_response", status, message);
    }
}
