package br.edu.utfpr.sd.ep1;

import com.google.gson.JsonObject;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Testes reais de servico, socket e console. Falhas encerram o processo com erro. */
public final class TesteEp1 {
    private static final PrintStream SILENCIO = new PrintStream(OutputStream.nullOutputStream());
    private static final List<JsonObject> RESPOSTAS = new ArrayList<>();
    private static int verificacoes;
    private static ConfiguracaoMySql config;

    private TesteEp1() { }

    public static void main(String[] args) throws Exception {
        config = ConfiguracaoMySql.carregar(Path.of(args.length == 0 ? "config/mysql-test.properties" : args[0]));
        // A bateria so pode limpar o banco separado de testes, nunca o da aplicacao.
        try (Connection conexao = config.abrir()) {
            if (!"ep1_reserva_salas_teste".equals(conexao.getCatalog())) {
                throw new IllegalArgumentException("Os testes exigem o banco ep1_reserva_salas_teste");
            }
        }
        try {
            limparTeste();
            regrasEP1();
            limparTeste();
            falhaPersistencia();
            limparTeste();
            transporteEConcorrencia();
            Files.write(Path.of("build/respostas-testes.jsonl"),
                    RESPOSTAS.stream().map(Protocolo.GSON::toJson).toList(), StandardCharsets.UTF_8);
            System.out.println("OK: " + verificacoes + " verificacoes; " + RESPOSTAS.size() + " respostas registradas.");
        } finally {
            limparTeste();
        }
    }

    private static RepositorioMySql repositorio() { return new RepositorioMySql(config); }

    private static void limparTeste() throws SQLException {
        try (Connection conexao = config.abrir(); var stmt = conexao.createStatement()) {
            if (!"ep1_reserva_salas_teste".equals(conexao.getCatalog())) throw new SQLException("Banco de testes incorreto");
            conexao.setAutoCommit(false);
            stmt.executeUpdate("DELETE FROM sessoes");
            stmt.executeUpdate("DELETE FROM usuarios");
            conexao.commit();
        }
    }

    private static JsonObject req(String op, String... campos) {
        JsonObject req = new JsonObject();
        req.addProperty("op", op);
        for (int i = 0; i < campos.length; i += 2) req.addProperty(campos[i], campos[i + 1]);
        return req;
    }

    private static void conferir(boolean condicao, String mensagem) {
        verificacoes++;
        if (!condicao) throw new AssertionError(mensagem);
    }

    private static synchronized JsonObject resposta(JsonObject res, String op, String status) {
        RESPOSTAS.add(res);
        conferir(op.equals(Protocolo.string(res, "op")), "op incorreta: " + res);
        conferir(status.equals(Protocolo.string(res, "status")), "status incorreto: " + res);
        conferir(Protocolo.string(res, "message") != null, "Resposta sem message: " + res);
        return res;
    }

    private static JsonObject executar(UsuarioService service, JsonObject req, String status) throws IOException {
        return resposta(service.executar(req), Protocolo.string(req, "op") + "_response", status);
    }

    private static void regrasEP1() throws Exception {
        Relogio clock = new Relogio();
        UsuarioService service = new UsuarioService(repositorio(), clock);
        JsonObject cadastro = req("register", "user", "joao", "email", "joao@alunos.utfpr.edu.br", "password", "senha123");
        executar(service, cadastro, "201");
        executar(service, cadastro, "409");
        executar(service, req("register", "user", "maria", "email", "joao@alunos.utfpr.edu.br", "password", "senha123"), "409");
        executar(service, req("register", "user", "Joao", "email", "outro@email.com", "password", "senha123"), "400");
        JsonObject errado = req("login", "email", "joao@alunos.utfpr.edu.br");
        errado.addProperty("password", 123);
        executar(service, errado, "400");
        errado.add("password", null);
        executar(service, errado, "400");
        executar(service, req("read_user"), "400");
        executar(service, req("read_user", "token", "invalido"), "400");
        executar(service, req("read_user", "token", "a".repeat(64)), "401");
        JsonObject login = req("login", "email", "joao@alunos.utfpr.edu.br", "password", "senha123");
        String token = Protocolo.string(executar(service, login, "200"), "token");
        conferir(token.matches("^[a-f0-9]{64}$"), "Formato de token");
        executar(service, login, "409");
        executar(service, req("login", "email", "joao@alunos.utfpr.edu.br", "password", "errada"), "401");
        JsonObject l = executar(service, req("login", "email", "ninguem@email.com", "password", "errada"), "401");
        conferir("Email ou senha incorretos".equals(Protocolo.string(l, "message")), "Credenciais sem revelar conta");
        JsonObject consulta = executar(service, req("read_user", "token", token, "campo_extra", "ignorado"), "200");
        conferir("joao".equals(Protocolo.string(consulta, "user")) && !consulta.has("password"), "Cadastro sem senha");
        conferir(Protocolo.string(consulta, "created_at").equals("2026-10-13 09:00:00"), "Fuso America/Sao_Paulo");
        JsonObject vazia = executar(service, req("update_user", "token", token, "user", "", "password", ""), "400");
        conferir("Nenhum dado para atualizar".equals(Protocolo.string(vazia, "message")), "Atualizacao vazia");
        executar(service, req("update_user", "token", token, "user", "jose", "password", "", "email", "novo@email.com"), "400");
        executar(service, req("register", "user", "maria", "email", "maria@email.com", "password", "senha123"), "201");
        executar(service, req("update_user", "token", token, "user", "maria", "password", ""), "409");
        executar(service, req("update_user", "token", token, "user", "jose", "password", "nova123"), "200");
        consulta = executar(service, req("read_user", "token", token), "200");
        conferir("jose".equals(Protocolo.string(consulta, "user")), "Token preservado apos renomear");
        service = new UsuarioService(repositorio(), clock);
        executar(service, req("read_user", "token", token), "200");
        executar(service, login, "401");
        executar(service, req("delete_user", "token", token, "password", "errada"), "403");
        executar(service, req("read_user", "token", token), "200");
        conferir(repositorio().carregar().usuarios.stream().noneMatch(u -> u.senhaHash.equals("nova123")), "Senha nao persistida em texto");
        clock.avancar(Duration.ofMinutes(29));
        executar(service, req("read_user", "token", token), "200");
        clock.avancar(Duration.ofMinutes(29));
        executar(service, req("read_user", "token", token), "200");
        clock.avancar(Duration.ofMinutes(30));
        executar(service, req("read_user", "token", token), "401");
        login = req("login", "email", "joao@alunos.utfpr.edu.br", "password", "nova123");
        token = Protocolo.string(executar(service, login, "200"), "token");
        executar(service, req("logout", "token", token), "200");
        executar(service, req("logout", "token", token), "401");
        executar(service, req("read_user", "token", token), "401");
        String novoToken = Protocolo.string(executar(service, login, "200"), "token");
        conferir(!novoToken.equals(token), "Token novo apos logout");
        executar(service, req("delete_user", "token", novoToken, "password", "nova123"), "200");
        executar(service, req("read_user", "token", novoToken), "401");
        executar(service, req("register", "user", "jose", "email", "joao@alunos.utfpr.edu.br", "password", "senha123"), "201");
        System.out.println("OK: regras de cadastro, sessao, atualizacao, exclusao e reinicio.");
    }

    private static void falhaPersistencia() throws Exception {
        UsuarioService service = new UsuarioService(repositorio(), Clock.systemUTC());
        executar(service, req("register", "user", "rollback", "email", "rollback@email.com", "password", "senha123"), "201");
        boolean falhou = false;
        try {
            repositorio().transacionar(banco -> {
                Banco.Usuario original = banco.usuarios.get(0);
                Banco.Usuario duplicado = Protocolo.GSON.fromJson(Protocolo.GSON.toJson(original), Banco.Usuario.class);
                original.user = "alterado";
                duplicado.id = UUID.randomUUID().toString();
                duplicado.user = "duplicado";
                // O mesmo email viola UNIQUE depois da atualizacao anterior.
                banco.usuarios.add(duplicado);
                return Protocolo.resposta("register_response", "201", "Usuario cadastrado com sucesso");
            });
        } catch (IOException e) { falhou = true; }
        Banco depois = repositorio().carregar();
        conferir(falhou && depois.usuarios.size() == 1 && depois.usuarios.get(0).user.equals("rollback"),
                "Rollback deve desfazer todas as alteracoes da transacao");
        String token = Protocolo.string(executar(service, req("login", "email", "rollback@email.com", "password", "senha123"), "200"), "token");
        try (Connection conexao = config.abrir(); var stmt = conexao.prepareStatement(
                "INSERT INTO sessoes (token, usuario_id, ultimo_uso) VALUES (?, ?, ?)")) {
            stmt.setString(1, "b".repeat(64));
            stmt.setString(2, depois.usuarios.get(0).id);
            stmt.setLong(3, System.currentTimeMillis());
            falhou = false;
            try { stmt.executeUpdate(); } catch (SQLException e) { falhou = e.getSQLState().startsWith("23"); }
            conferir(falhou, "UNIQUE deve impedir duas sessoes para o mesmo usuario");
        }
        try (Connection conexao = config.abrir(); var stmt = conexao.prepareStatement("DELETE FROM usuarios WHERE id=?")) {
            stmt.setString(1, depois.usuarios.get(0).id);
            stmt.executeUpdate();
        }
        conferir(repositorio().carregar().sessoes.isEmpty(), "ON DELETE CASCADE deve apagar a sessao");
        executar(service, req("read_user", "token", token), "401");
        executar(service, req("register", "user", "rollback", "email", "rollback@email.com", "password", "senha123"), "201");
        System.out.println("OK: rollback MySQL, restricoes UNIQUE, exclusao em cascata e leitura sem cache desatualizado.");
    }

    private static JsonObject chamar(int porta, String texto) throws IOException {
        try (Socket socket = new Socket("127.0.0.1", porta)) {
            socket.setSoTimeout(5000);
            socket.getOutputStream().write((texto + "\n").getBytes(StandardCharsets.UTF_8));
            Protocolo.Linha linha = Protocolo.ler(new BufferedInputStream(socket.getInputStream()));
            if (linha == null) throw new IOException("Conexao sem resposta");
            return Protocolo.interpretar(linha.texto());
        }
    }

    private static List<JsonObject> simultaneas(int porta, int segundaPorta, JsonObject req) throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        CountDownLatch inicio = new CountDownLatch(1);
        try {
            var a = pool.submit(() -> { inicio.await(); return chamar(porta, Protocolo.GSON.toJson(req)); });
            var b = pool.submit(() -> { inicio.await(); return chamar(segundaPorta, Protocolo.GSON.toJson(req)); });
            inicio.countDown();
            return List.of(a.get(5, TimeUnit.SECONDS), b.get(5, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    private static void transporteEConcorrencia() throws Exception {
        UsuarioService service = new UsuarioService(repositorio(), Clock.systemUTC());
        ServidorTcp servidor = new ServidorTcp(0, service, SILENCIO, 500);
        ServidorTcp segundo = new ServidorTcp(0, new UsuarioService(repositorio(), Clock.systemUTC()), SILENCIO, 500);
        Thread aceitar = new Thread(() -> {
            try { servidor.executar(); }
            catch (IOException e) { throw new IllegalStateException(e); }
        }, "servidor-teste");
        aceitar.start();
        Thread aceitarSegundo = new Thread(() -> {
            try { segundo.executar(); }
            catch (IOException e) { throw new IllegalStateException(e); }
        }, "servidor-teste-segundo");
        aceitarSegundo.start();
        int porta = servidor.porta();
        try {
            for (String invalido : List.of("{", "null", "[]", "{message:'ola'}", "{}", "{\"op\":123}")) {
                resposta(chamar(porta, invalido), "error", "400");
            }
            JsonObject desconhecida = resposta(chamar(porta, "{\"op\":\"qualquer\"}"), "error", "400");
            conferir("Operacao desconhecida".equals(Protocolo.string(desconhecida, "message")), "Erro de op desconhecida");
            try (Socket socket = new Socket("127.0.0.1", porta)) {
                socket.setSoTimeout(5000);
                BufferedInputStream entrada = new BufferedInputStream(socket.getInputStream());
                OutputStream saida = socket.getOutputStream();
                saida.write(new byte[] {(byte) 0xc3, '\n'});
                resposta(Protocolo.interpretar(Protocolo.ler(entrada).texto()), "error", "400");
                String base = "{\"op\":\"login\",\"email\":\"n@email.com\",\"password\":\"x\",\"extra\":\"\"}";
                String limite = base.replace("\"extra\":\"\"", "\"extra\":\"" + "a".repeat(8191 - base.length()) + "\"");
                conferir((limite + "\n").getBytes(StandardCharsets.UTF_8).length == 8192, "Fronteira exata de bytes");
                saida.write((limite + "\n").getBytes(StandardCharsets.UTF_8));
                resposta(Protocolo.interpretar(Protocolo.ler(entrada).texto()), "login_response", "401");
                saida.write((limite + " \n").getBytes(StandardCharsets.UTF_8));
                JsonObject excedida = resposta(Protocolo.interpretar(Protocolo.ler(entrada).texto()), "error", "400");
                conferir("Mensagem excede o tamanho maximo".equals(Protocolo.string(excedida, "message")), "Mensagem excedida");
                // UTF-8 multibyte: limite e contado em bytes, nao caracteres.
                saida.write(("{\"extra\":\"" + "á".repeat(4100) + "\"}\n").getBytes(StandardCharsets.UTF_8));
                resposta(Protocolo.interpretar(Protocolo.ler(entrada).texto()), "error", "400");
                saida.write("{\"op\":\"log".getBytes(StandardCharsets.UTF_8));
                saida.write("in\",\"email\":\"n@email.com\",\"password\":\"x\"}\n{\"op\":\"nao_existe\"}\n".getBytes(StandardCharsets.UTF_8));
                resposta(Protocolo.interpretar(Protocolo.ler(entrada).texto()), "login_response", "401");
                resposta(Protocolo.interpretar(Protocolo.ler(entrada).texto()), "error", "400");
            }
            List<JsonObject> cadastro = simultaneas(porta, segundo.porta(), req("register", "user", "corrida", "email", "corrida@email.com", "password", "senha123"));
            conferir(cadastro.stream().map(r -> Protocolo.string(r, "status")).sorted().toList().equals(List.of("201", "409")), "Cadastro concorrente");
            for (JsonObject r : cadastro) resposta(r, "register_response", Protocolo.string(r, "status"));
            List<JsonObject> logins = simultaneas(porta, segundo.porta(), req("login", "email", "corrida@email.com", "password", "senha123"));
            conferir(logins.stream().map(r -> Protocolo.string(r, "status")).sorted().toList().equals(List.of("200", "409")), "Login concorrente");
            for (JsonObject r : logins) resposta(r, "login_response", Protocolo.string(r, "status"));
            String token = logins.stream().filter(r -> "200".equals(Protocolo.string(r, "status")))
                    .map(r -> Protocolo.string(r, "token")).findFirst().orElseThrow();
            try (ClienteTcp cliente = new ClienteTcp("127.0.0.1", porta, SILENCIO)) {
                resposta(cliente.enviar(req("read_user", "token", token)), "read_user_response", "200");
                Thread.sleep(800); // Timeout reduzido somente no servidor de teste.
                resposta(cliente.enviar(req("read_user", "token", token)), "read_user_response", "200");
                resposta(cliente.enviar(req("logout", "token", token)), "logout_response", "200");
            }
            // O contrato nao limita o comprimento do email. Uma leitura grande deve
            // receber 500 dentro do limite, em vez de encerrar sem resposta.
            JsonObject grande = req("register", "user", "gigante", "email", "", "password", "x");
            String dominio = "@email.com";
            String email = "a".repeat(8191 - Protocolo.GSON.toJson(grande).length() - dominio.length()) + dominio;
            grande.addProperty("email", email);
            resposta(chamar(porta, Protocolo.GSON.toJson(grande)), "register_response", "201");
            JsonObject acesso = resposta(chamar(porta, Protocolo.GSON.toJson(req("login", "email", email, "password", "x"))), "login_response", "200");
            String grandeToken = Protocolo.string(acesso, "token");
            resposta(chamar(porta, Protocolo.GSON.toJson(req("read_user", "token", grandeToken))), "read_user_response", "500");
            resposta(chamar(porta, Protocolo.GSON.toJson(req("delete_user", "token", grandeToken, "password", "x"))), "delete_user_response", "200");
            console(porta);
            System.out.println("OK: TCP, bytes, JSON estrito, duas instancias concorrentes, reconexao e cliente de console.");
        } finally {
            servidor.close();
            segundo.close();
            aceitar.join(2000);
            aceitarSegundo.join(2000);
            conferir(!aceitar.isAlive() && !aceitarSegundo.isAlive(), "Servidores de teste encerrados");
        }
    }

    private static void console(int porta) throws Exception {
        String entrada = "127.0.0.1\n" + porta + "\n"
                + "1\nconsole@email.com\nconsoleuser\nsenha123\n"
                + "2\nconsole@email.com\nsenha123\n"
                + "1\n2\nconsolenovo\nnova123\n4\n"
                + "2\nconsole@email.com\nnova123\n3\nSIM\nnova123\n0\n";
        Process processo = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), Cliente.class.getName()).redirectErrorStream(true).start();
        processo.getOutputStream().write(entrada.getBytes(StandardCharsets.UTF_8));
        processo.getOutputStream().close();
        if (!processo.waitFor(15, TimeUnit.SECONDS)) {
            processo.destroyForcibly();
            throw new AssertionError("Cliente de console nao encerrou");
        }
        String saida = new String(processo.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        conferir(processo.exitValue() == 0, "Cliente de console: " + saida);
        for (String op : List.of("register", "login", "read_user", "update_user", "logout", "delete_user")) {
            conferir(saida.contains("\"op\":\"" + op + "_response\""), "Operacao do console ausente: " + op + "\n" + saida);
        }
        conferir(saida.contains("Usuario removido com sucesso"), "Exclusao pelo console");
    }

    private static final class Relogio extends Clock {
        private Instant agora = Instant.parse("2026-10-13T12:00:00Z");
        void avancar(Duration duracao) { agora = agora.plus(duracao); }
        @Override public Instant instant() { return agora; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(agora, zone); }
    }
}
