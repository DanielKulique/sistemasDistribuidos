package br.edu.utfpr.sd.ep1;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;

/** Regras da EP-1. O repositorio executa a leitura e a gravacao na mesma transacao. */
public final class UsuarioService {
    private static final Set<String> OPERACOES = Set.of(
            "register", "login", "logout", "read_user", "update_user", "delete_user");
    private static final String USER = "^[a-z]{1,30}$";
    private static final String EMAIL = "^[a-z0-9._-]+@[a-z0-9-]+(\\.[a-z0-9-]+)*\\.[a-z]{2,}$";
    private static final String PASSWORD = "^[A-Za-z0-9]{1,20}$";
    private static final String TOKEN = "^[a-f0-9]{64}$";
    private static final long VALIDADE_MS = Duration.ofMinutes(30).toMillis();
    private static final DateTimeFormatter DATA = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.of("America/Sao_Paulo"));
    private final SecureRandom random = new SecureRandom();
    private final RepositorioUsuarios repositorio;
    private final Clock clock;

    public UsuarioService(RepositorioUsuarios repositorio, Clock clock) throws IOException {
        this.repositorio = repositorio;
        this.clock = clock;
        repositorio.carregar(); // Confere conexao e estado antes de aceitar clientes.
    }

    public JsonObject executar(JsonObject req) throws IOException {
        String op = Protocolo.string(req, "op");
        if (op == null) return Protocolo.erro("Requisicao invalida");
        if (!OPERACOES.contains(op)) return Protocolo.erro("Operacao desconhecida");
        JsonObject invalida = validar(req, op);
        if (invalida != null) return invalida;

        return repositorio.transacionar(novo -> switch (op) {
            case "register" -> cadastrar(novo, req);
            case "login" -> login(novo, req);
            default -> autenticada(novo, req, op);
        });
    }

    private JsonObject validar(JsonObject req, String op) {
        if (op.equals("register")) {
            if (!combina(req, "user", USER) || !combina(req, "email", EMAIL) || !combina(req, "password", PASSWORD)) {
                return resposta(op, "400", "Dados de cadastro em formato invalido");
            }
        } else if (op.equals("login")) {
            if (!combina(req, "email", EMAIL) || !combina(req, "password", PASSWORD)) {
                return resposta(op, "400", "Email ou senha em formato invalido");
            }
        } else {
            String erro = switch (op) {
                case "update_user" -> "Dados em formato invalido";
                case "delete_user" -> "Senha em formato invalido";
                default -> "Token em formato invalido";
            };
            if (!combina(req, "token", TOKEN)) return resposta(op, "400", erro);
            if (op.equals("delete_user") && !combina(req, "password", PASSWORD)) {
                return resposta(op, "400", erro);
            }
            if (op.equals("update_user")) {
                if (req.has("email") || !alteracao(req, "user", USER) || !alteracao(req, "password", PASSWORD)) {
                    return resposta(op, "400", erro);
                }
                if (Protocolo.string(req, "user").isEmpty() && Protocolo.string(req, "password").isEmpty()) {
                    return resposta(op, "400", "Nenhum dado para atualizar");
                }
            }
        }
        return null;
    }

    private static boolean combina(JsonObject req, String campo, String regex) {
        String valor = Protocolo.string(req, campo);
        return valor != null && valor.matches(regex);
    }

    private static boolean alteracao(JsonObject req, String campo, String regex) {
        String valor = Protocolo.string(req, campo);
        return valor != null && (valor.isEmpty() || valor.matches(regex));
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
        sessao.ultimoUso = clock.millis();
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
        // Salas/reservas nao integram a EP-1. Ao adiciona-las, apagar aqui todas as reservas deste id.
        return resposta("delete_user", "200", "Usuario removido com sucesso");
    }

    private static JsonObject resposta(String op, String status, String message) {
        return Protocolo.resposta(op + "_response", status, message);
    }
}
