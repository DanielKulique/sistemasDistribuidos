package br.edu.utfpr.sd.ep1;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Casos adversos de protocolo, servidores simulados e concorrencia real no arquivo JSON. */
public final class TesteComunicacao {
    private static final PrintStream SILENCIO = new PrintStream(OutputStream.nullOutputStream());
    private static final List<JsonObject> RESULTADOS = new ArrayList<>();
    private static final List<JsonObject> RESPOSTAS = Collections.synchronizedList(new ArrayList<>());
    private static Path config;
    private static Path caminhoConfig;
    private static int verificacoes;
    private static int falhas;
    private static int casos;
    private static int porta;

    @FunctionalInterface private interface Teste { void executar() throws Exception; }
    @FunctionalInterface private interface Tratamento { void atender(Socket socket, int numero) throws Exception; }

    private TesteComunicacao() { }

    public static void main(String[] args) throws Exception {
        caminhoConfig = Path.of(args.length == 0 ? "build/ep1-teste.json" : args[0]);
        config = caminhoConfig;
        try (TravaBancoTeste trava = new TravaBancoTeste(config)) {
            trava.conferirBanco();
            executarBateria();
        }
    }

    private static void executarBateria() throws Exception {
        limpar();
        ServidorTcp servidor = new ServidorTcp(0, new UsuarioService(new RepositorioArquivo(config), Clock.systemUTC()), SILENCIO, 3000);
        Thread thread = new Thread(() -> {
            try { servidor.executar(); } catch (IOException e) { throw new IllegalStateException(e); }
        }, "bateria-comunicacao");
        thread.start();
        porta = servidor.porta();
        try {
            transporte();
            validacoes();
            clientesSimulados();
            concorrencia();
            falhasEReenvio();
            consoleESessao();
        } finally {
            servidor.close();
            thread.join(3000);
            limpar();
            Files.write(Path.of("build/respostas-comunicacao.jsonl"), RESPOSTAS.stream().map(Protocolo.GSON::toJson).toList(), StandardCharsets.UTF_8);
            JsonObject relatorio = new JsonObject();
            relatorio.addProperty("casos", casos);
            relatorio.addProperty("verificacoes", verificacoes);
            relatorio.addProperty("falhas", falhas);
            relatorio.add("resultados", Protocolo.GSON.toJsonTree(RESULTADOS));
            Files.writeString(Path.of("build/relatorio-comunicacao.json"), Protocolo.GSON.toJson(relatorio), StandardCharsets.UTF_8);
        }
        System.out.println("Comunicacao: " + casos + " casos, " + verificacoes + " verificacoes, " + falhas + " falhas.");
        if (falhas > 0) throw new AssertionError("Ha falhas na bateria; consulte build/relatorio-comunicacao.json");
    }

    private static void caso(String nome, Teste teste) {
        casos++;
        JsonObject resultado = new JsonObject();
        resultado.addProperty("caso", nome);
        try {
            teste.executar();
            resultado.addProperty("resultado", "passou");
            System.out.println("OK: " + nome);
        } catch (Exception | AssertionError e) {
            falhas++;
            resultado.addProperty("resultado", "falhou");
            resultado.addProperty("erro", e.getClass().getSimpleName() + ": " + e.getMessage());
            System.out.println("FALHOU: " + nome + " -> " + e.getMessage());
        }
        RESULTADOS.add(resultado);
    }

    private static void conferir(boolean condicao, String descricao) {
        verificacoes++;
        if (!condicao) throw new AssertionError(descricao);
    }

    private static void limpar() throws Exception {
        TravaBancoTeste.conferir(config);
        new RepositorioArquivo(config).transacionar(banco -> {
            banco.sessoes.clear(); banco.usuarios.clear(); return new JsonObject();
        });
    }

    private static JsonObject req(String op, String... pares) {
        JsonObject req = new JsonObject();
        req.addProperty("op", op);
        for (int i = 0; i < pares.length; i += 2) req.addProperty(pares[i], pares[i + 1]);
        return req;
    }

    private static JsonObject ler(Socket socket) throws IOException {
        Protocolo.Linha linha = Protocolo.ler(socket.getInputStream());
        if (linha == null || linha.excedida()) throw new IOException("Resposta ausente ou acima do limite");
        JsonObject res = Protocolo.interpretar(linha.texto());
        RESPOSTAS.add(res);
        return res;
    }

    private static JsonObject chamar(JsonObject req) throws IOException {
        try (Socket socket = new Socket("127.0.0.1", porta)) {
            socket.setSoTimeout(10_000);
            Protocolo.enviar(socket.getOutputStream(), req);
            return ler(socket);
        }
    }

    private static void status(JsonObject res, String esperado) {
        conferir(esperado.equals(Protocolo.string(res, "status")), "Status esperado " + esperado + "; recebido " + res);
    }

    private static String cadastrarLogar(String nome) throws IOException {
        status(chamar(req("register", "user", nome, "email", nome + "@teste.com", "password", "senha123")), "201");
        JsonObject res = chamar(req("login", "email", nome + "@teste.com", "password", "senha123"));
        status(res, "200");
        return Protocolo.string(res, "token");
    }

    private static void transporte() {
        caso("Mensagem incompleta nao e executada antes do LF", () -> {
            JsonObject cadastro = req("register", "user", "incompleto", "email", "incompleto@teste.com", "password", "senha123");
            try (Socket socket = new Socket("127.0.0.1", porta)) {
                socket.setSoTimeout(100);
                socket.getOutputStream().write(Protocolo.GSON.toJson(cadastro).getBytes(StandardCharsets.UTF_8));
                boolean aguardou = false;
                try { socket.getInputStream().read(); } catch (SocketTimeoutException e) { aguardou = true; }
                conferir(aguardou, "Servidor respondeu antes do delimitador");
                socket.shutdownOutput();
                socket.setSoTimeout(3000);
                conferir(socket.getInputStream().read() == -1, "Linha incompleta deve ser descartada ao fechar escrita");
            }
            status(chamar(cadastro), "201");
        });
        caso("50 mensagens agrupadas geram exatamente 50 respostas em ordem", () -> {
            try (Socket socket = new Socket("127.0.0.1", porta)) {
                socket.setSoTimeout(5000);
                StringBuilder pacote = new StringBuilder();
                for (int i = 0; i < 50; i++) pacote.append(i % 2 == 0 ? "{\"op\":\"desconhecida\"}\n" : "{\"op\":\"read_user\"}\n");
                socket.getOutputStream().write(pacote.toString().getBytes(StandardCharsets.UTF_8));
                for (int i = 0; i < 50; i++) {
                    JsonObject res = ler(socket);
                    status(res, "400");
                    conferir((i % 2 == 0 ? "error" : "read_user_response").equals(Protocolo.string(res, "op")), "Resposta fora da ordem");
                }
                socket.setSoTimeout(100);
                boolean semExtra = false;
                try { socket.getInputStream().read(); } catch (SocketTimeoutException e) { semExtra = true; }
                conferir(semExtra, "Servidor enviou resposta extra");
            }
        });
        caso("Mensagem de 1 MB e descartada ate LF; proxima mensagem funciona", () -> {
            try (Socket socket = new Socket("127.0.0.1", porta)) {
                socket.setSoTimeout(5000);
                socket.getOutputStream().write(("x".repeat(1_000_000) + "\n{\"op\":\"read_user\"}\n").getBytes(StandardCharsets.UTF_8));
                JsonObject res = ler(socket);
                status(res, "400");
                conferir("Mensagem excede o tamanho maximo".equals(Protocolo.string(res, "message")), "Erro incorreto para mensagem grande");
                conferir("read_user_response".equals(Protocolo.string(ler(socket), "op")), "Framing perdido depois do descarte");
            }
        });
        caso("JSON invalido, CRLF e bytes UTF-8 invalidos nao derrubam a conexao", () -> {
            try (Socket socket = new Socket("127.0.0.1", porta)) {
                socket.setSoTimeout(5000);
                byte[][] invalidos = {"\n".getBytes(StandardCharsets.UTF_8), "{}\r\n".getBytes(StandardCharsets.UTF_8),
                        new byte[] {(byte) 0xc0, (byte) 0xaf, '\n'}, "{\"op\":\"login\",}\n".getBytes(StandardCharsets.UTF_8)};
                for (byte[] bytes : invalidos) {
                    socket.getOutputStream().write(bytes);
                    JsonObject res = ler(socket);
                    status(res, "400");
                    conferir("error".equals(Protocolo.string(res, "op")), "Erro de formato deve usar error");
                }
                Protocolo.enviar(socket.getOutputStream(), req("read_user"));
                conferir("read_user_response".equals(Protocolo.string(ler(socket), "op")), "Conexao deve continuar");
            }
        });
        caso("Chaves repetidas sao rejeitadas para evitar operacao ambigua", () -> {
            try (Socket socket = new Socket("127.0.0.1", porta)) {
                socket.setSoTimeout(5000);
                socket.getOutputStream().write("{\"op\":\"login\",\"op\":\"read_user\"}\n".getBytes(StandardCharsets.UTF_8));
                JsonObject res = ler(socket);
                conferir("error".equals(Protocolo.string(res, "op")), "JSON com op duplicada foi interpretado como outra operacao");
                status(res, "400");
            }
        });
        caso("Chaves repetidas aninhadas, comentarios e JSONs concatenados sao rejeitados", () -> {
            for (String texto : List.of("{\"op\":\"read_user\",\"extra\":{\"a\":1,\"a\":2}}", "{}{}", "{/*comentario*/\"op\":\"read_user\"}",
                    "{\"op\":\"read_user\",\"extra\":" + "[".repeat(300) + "0" + "]".repeat(300) + "}")) {
                try (Socket socket = new Socket("127.0.0.1", porta)) {
                    socket.setSoTimeout(5000);
                    socket.getOutputStream().write((texto + "\n").getBytes(StandardCharsets.UTF_8));
                    JsonObject res = ler(socket);
                    status(res, "400");
                    conferir("error".equals(Protocolo.string(res, "op")), "JSON ambiguo/fora de formato foi aceito");
                }
            }
        });
        caso("Ordem das chaves e campos desconhecidos nao mudam a operacao", () -> {
            try (Socket socket = new Socket("127.0.0.1", porta)) {
                socket.setSoTimeout(5000);
                socket.getOutputStream().write("{\"password\":\"x\",\"extra\":[1,true,null],\"email\":\"ausente@teste.com\",\"op\":\"login\"}\n".getBytes(StandardCharsets.UTF_8));
                JsonObject res = ler(socket);
                status(res, "401");
                conferir("login_response".equals(Protocolo.string(res, "op")), "Servidor depende da posicao de op");
            }
        });
    }

    private static void validacoes() {
        caso("Campos obrigatorios: ausentes, null, numeros, booleanos, objetos e arrays", () -> {
            String token = cadastrarLogar("tipos");
            List<JsonObject> bases = List.of(req("register", "user", "outro", "email", "outro@teste.com", "password", "senha123"),
                    req("login", "email", "tipos@teste.com", "password", "senha123"), req("read_user", "token", token),
                    req("logout", "token", token), req("update_user", "token", token, "user", "novo", "password", ""),
                    req("delete_user", "token", token, "password", "senha123"));
            for (JsonObject base : bases) {
                for (String campo : base.keySet()) {
                    if (campo.equals("op")) continue;
                    JsonObject mutacao = base.deepCopy();
                    mutacao.remove(campo);
                    status(chamar(mutacao), "400");
                    for (String valor : List.of("null", "123", "true", "{}", "[]")) {
                        mutacao = base.deepCopy();
                        mutacao.add(campo, Protocolo.GSON.fromJson(valor, com.google.gson.JsonElement.class));
                        status(chamar(mutacao), "400");
                    }
                }
            }
            status(chamar(req("read_user", "token", token)), "200");
        });
        caso("Campos extras nao alteram autor, perfil ou email", () -> {
            JsonObject cadastro = req("register", "user", "perfis", "email", "perfis@teste.com", "password", "senha123", "role", "admin");
            status(chamar(cadastro), "201");
            JsonObject login = chamar(req("login", "email", "perfis@teste.com", "password", "senha123"));
            status(login, "200");
            conferir("user".equals(Protocolo.string(login, "role")), "Cadastro permitiu escalada de perfil");
            String token = Protocolo.string(login, "token");
            status(chamar(req("update_user", "token", token, "user", "perfisnovo", "password", "", "role", "admin")), "200");
            JsonObject res = chamar(req("read_user", "token", token, "user", "tipos", "target_user", "tipos"));
            status(res, "200");
            conferir("perfisnovo".equals(Protocolo.string(res, "user")) && "user".equals(Protocolo.string(res, "role")), "Autor ou perfil mudou por campo extra");
            conferir(!res.has("password") && !res.has("senha_hash"), "Consulta expos senha");
        });
        caso("Ultimo administrador nao pode ser excluido", () -> {
            String token = cadastrarLogar("administrador");
            new RepositorioArquivo(config).transacionar(banco -> {
                banco.usuarios.stream().filter(u -> u.user.equals("administrador")).findFirst().orElseThrow().role = "admin";
                return Protocolo.resposta("update_user_response", "200", "Dados atualizados com sucesso");
            });
            JsonObject res = chamar(req("delete_user", "token", token, "password", "senha123"));
            status(res, "403");
            conferir("Nao e possivel remover o ultimo administrador".equals(Protocolo.string(res, "message")), "Mensagem de protecao incorreta");
            status(chamar(req("read_user", "token", token)), "200");
        });
    }

    private static void clientesSimulados() {
        caso("Cliente aceita todas as variantes de resposta extraidas do AsyncAPI", () -> {
            JsonObject fixture = Protocolo.GSON.fromJson(Files.readString(Path.of("src/test/resources/respostas-ep1.json")), JsonObject.class);
            conferir("2.4.0".equals(Protocolo.string(fixture, "versao")), "Fixture corresponde a outra versao do protocolo");
            for (var valor : fixture.getAsJsonArray("casos")) {
                JsonObject dados = valor.getAsJsonObject();
                JsonObject esperada = dados.getAsJsonObject("resposta");
                try (Simulado simulado = responder(Protocolo.GSON.toJson(esperada));
                        ClienteTcp cliente = new ClienteTcp("127.0.0.1", simulado.porta(), SILENCIO)) {
                    JsonObject res = cliente.enviar(dados.getAsJsonObject("requisicao"));
                    RESPOSTAS.add(res);
                    conferir(res.equals(esperada), "Cliente modificou a resposta recebida");
                    conferir(simulado.pedidos.get() == 1, "Resposta prevista causou reenvio");
                }
            }
        });
        caso("Cliente rejeita status fora do contrato", () -> rejeitar("read_user", "{\"op\":\"read_user_response\",\"status\":\"999\",\"message\":\"Consulta realizada com sucesso\"}"));
        caso("Cliente rejeita message incorreta para o status", () -> rejeitar("logout", "{\"op\":\"logout_response\",\"status\":\"200\",\"message\":\"qualquer texto\"}"));
        caso("Cliente rejeita login 200 sem token e role", () -> rejeitar("login", "{\"op\":\"login_response\",\"status\":\"200\",\"message\":\"Login realizado com sucesso\"}"));
        caso("Cliente rejeita cadastro consultado com campos ausentes", () -> rejeitar("read_user", "{\"op\":\"read_user_response\",\"status\":\"200\",\"message\":\"Consulta realizada com sucesso\"}"));
        caso("Cliente rejeita senha exposta na resposta", () -> rejeitar("logout", "{\"op\":\"logout_response\",\"status\":\"200\",\"message\":\"Logout realizado com sucesso\",\"password\":\"senha123\"}"));
        caso("Cliente rejeita tipos incorretos, token malformado, perfil desconhecido e op errada", () -> {
            for (String texto : List.of(
                    "{\"op\":\"login_response\",\"status\":200,\"message\":\"Login realizado com sucesso\",\"token\":\"" + "a".repeat(64) + "\",\"role\":\"user\"}",
                    "{\"op\":\"login_response\",\"status\":\"200\",\"message\":\"Login realizado com sucesso\",\"token\":\"curto\",\"role\":\"user\"}",
                    "{\"op\":\"login_response\",\"status\":\"200\",\"message\":\"Login realizado com sucesso\",\"token\":\"" + "a".repeat(64) + "\",\"role\":\"outro\"}",
                    "{\"op\":\"logout_response\",\"status\":\"200\",\"message\":\"Logout realizado com sucesso\"}")) rejeitar("login", texto);
        });
        caso("UTF-8 invalido e resposta acima do limite sao rejeitados sem reenvio", () -> {
            for (byte[] bytes : List.of(new byte[] {(byte) 0xc3, '\n'}, ("x".repeat(9000) + "\n").getBytes(StandardCharsets.UTF_8))) {
                try (Simulado simulado = new Simulado((socket, numero) -> {
                    Protocolo.ler(socket.getInputStream());
                    socket.getOutputStream().write(bytes);
                }); ClienteTcp cliente = new ClienteTcp("127.0.0.1", simulado.porta(), SILENCIO)) {
                    boolean rejeitou = false;
                    try { cliente.enviar(pedidoCliente("logout")); } catch (ErroProtocoloException e) { rejeitou = true; }
                    conferir(rejeitou && simulado.pedidos.get() == 1, "Resposta invalida foi tratada como falha de conexao");
                }
            }
        });
        caso("Requisicao local acima do limite nao abre conexao nem envia dados", () -> {
            try (Simulado simulado = responder("{}"); ClienteTcp cliente = new ClienteTcp("127.0.0.1", simulado.porta(), SILENCIO)) {
                JsonObject req = pedidoCliente("login");
                req.addProperty("extra", "x".repeat(9000));
                boolean rejeitou = false;
                try { cliente.enviar(req); } catch (IOException e) { rejeitou = true; }
                conferir(rejeitou && simulado.pedidos.get() == 0, "Requisicao grande chegou ao servidor");
            }
        });
        caso("Timeout de resposta e limitado a uma reconexao", () -> {
            try (Simulado simulado = new Simulado((socket, numero) -> {
                Protocolo.ler(socket.getInputStream());
                // O servidor simulado fica sem responder ate o cliente fechar.
                socket.getInputStream().read();
            }); ClienteTcp cliente = new ClienteTcp("127.0.0.1", simulado.porta(), SILENCIO, 100)) {
                boolean falhou = false;
                long inicio = System.nanoTime();
                try { cliente.enviar(pedidoCliente("logout")); } catch (IOException e) { falhou = true; }
                conferir(falhou && simulado.pedidos.get() == 2, "Numero inesperado de tentativas");
                conferir(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - inicio) < 3000, "Cliente ficou preso esperando resposta");
            }
        });
        caso("Cliente aceita resposta error 400 prevista no protocolo", () -> {
            try (Simulado simulado = responder("{\"op\":\"error\",\"status\":\"400\",\"message\":\"Requisicao invalida\"}");
                    ClienteTcp cliente = new ClienteTcp("127.0.0.1", simulado.porta(), SILENCIO)) {
                conferir("error".equals(Protocolo.string(cliente.enviar(req("read_user")), "op")), "Resposta error nao aceita");
                conferir(simulado.pedidos.get() == 1, "Error 400 provocou reenvio");
            }
        });
        caso("Resposta JSON malformada nao provoca repeticao da operacao", () -> {
            try (Simulado simulado = responder("{invalido}"); ClienteTcp cliente = new ClienteTcp("127.0.0.1", simulado.porta(), SILENCIO)) {
                boolean rejeitou = false;
                try { cliente.enviar(req("logout", "token", "a".repeat(64))); } catch (IOException e) { rejeitou = true; }
                conferir(rejeitou, "Resposta invalida aceita");
                conferir(simulado.pedidos.get() == 1, "Operacao foi reenviada apos uma resposta completa invalida");
            }
        });
        caso("Resposta fragmentada e remontada corretamente", () -> {
            String res = "{\"op\":\"logout_response\",\"status\":\"200\",\"message\":\"Logout realizado com sucesso\"}\n";
            try (Simulado simulado = new Simulado((socket, numero) -> {
                Protocolo.ler(socket.getInputStream());
                for (byte b : res.getBytes(StandardCharsets.UTF_8)) { socket.getOutputStream().write(b); socket.getOutputStream().flush(); }
            }); ClienteTcp cliente = new ClienteTcp("127.0.0.1", simulado.porta(), SILENCIO)) {
                status(cliente.enviar(req("logout", "token", "a".repeat(64))), "200");
                conferir(simulado.pedidos.get() == 1, "Fragmentacao causou reenvio");
            }
        });
        caso("Resposta interrompida antes do LF provoca uma reconexao", () -> {
            String resposta = "{\"op\":\"logout_response\",\"status\":\"200\",\"message\":\"Logout realizado com sucesso\"}\n";
            try (Simulado simulado = new Simulado((socket, numero) -> {
                Protocolo.ler(socket.getInputStream());
                socket.getOutputStream().write((numero == 1 ? resposta.substring(0, 20) : resposta).getBytes(StandardCharsets.UTF_8));
            }); ClienteTcp cliente = new ClienteTcp("127.0.0.1", simulado.porta(), SILENCIO)) {
                status(cliente.enviar(pedidoCliente("logout")), "200");
                conferir(simulado.pedidos.get() == 2, "Resposta parcial nao causou reconexao");
            }
        });
        caso("Chamadas simultaneas no mesmo cliente aguardam a resposta anterior", () -> {
            AtomicInteger adiantados = new AtomicInteger();
            try (Simulado simulado = new Simulado((socket, numero) -> {
                for (int i = 0; i < 2; i++) {
                    Protocolo.ler(socket.getInputStream());
                    Thread.sleep(50);
                    if (socket.getInputStream().available() > 0) adiantados.incrementAndGet();
                    Protocolo.enviar(socket.getOutputStream(), Protocolo.resposta("logout_response", "200", "Logout realizado com sucesso"));
                }
            }); ClienteTcp cliente = new ClienteTcp("127.0.0.1", simulado.porta(), SILENCIO)) {
                var pool = Executors.newFixedThreadPool(2);
                CountDownLatch inicio = new CountDownLatch(1);
                try {
                    var a = pool.submit(() -> { inicio.await(); return cliente.enviar(pedidoCliente("logout")); });
                    var b = pool.submit(() -> { inicio.await(); return cliente.enviar(pedidoCliente("logout")); });
                    inicio.countDown();
                    status(a.get(5, TimeUnit.SECONDS), "200");
                    status(b.get(5, TimeUnit.SECONDS), "200");
                    conferir(adiantados.get() == 0 && simulado.pedidos.get() == 1, "Cliente misturou duas requisicoes pendentes");
                } finally { pool.shutdownNow(); }
            }
        });
        caso("Resposta extra pendente nao e confundida com a proxima operacao", () -> {
            try (Simulado simulado = new Simulado((socket, numero) -> {
                Protocolo.ler(socket.getInputStream());
                String resposta = "{\"op\":\"logout_response\",\"status\":\"200\",\"message\":\"Logout realizado com sucesso\"}\n";
                socket.getOutputStream().write((resposta + resposta).getBytes(StandardCharsets.UTF_8));
                socket.getInputStream().read();
            }); ClienteTcp cliente = new ClienteTcp("127.0.0.1", simulado.porta(), SILENCIO)) {
                status(cliente.enviar(pedidoCliente("logout")), "200");
                boolean rejeitou = false;
                try { cliente.enviar(pedidoCliente("logout")); } catch (ErroProtocoloException e) { rejeitou = true; }
                conferir(rejeitou && simulado.pedidos.get() == 1, "Resposta extra causou falso sucesso");
            }
        });
    }

    private static void rejeitar(String op, String texto) throws Exception {
        try (Simulado simulado = responder(texto); ClienteTcp cliente = new ClienteTcp("127.0.0.1", simulado.porta(), SILENCIO)) {
            boolean rejeitou = false;
            try { cliente.enviar(pedidoCliente(op)); } catch (IOException e) { rejeitou = true; }
            conferir(rejeitou, "Cliente aceitou uma resposta fora do contrato");
            conferir(simulado.pedidos.get() == 1, "Cliente reenviou uma operacao por erro de protocolo");
        }
    }

    private static JsonObject pedidoCliente(String op) {
        return switch (op) {
            case "register" -> req(op, "user", "simulado", "email", "simulado@teste.com", "password", "senha123");
            case "login" -> req(op, "email", "simulado@teste.com", "password", "senha123");
            case "update_user" -> req(op, "token", "a".repeat(64), "user", "novo", "password", "");
            case "delete_user" -> req(op, "token", "a".repeat(64), "password", "senha123");
            default -> req(op, "token", "a".repeat(64));
        };
    }

    private static Simulado responder(String texto) throws IOException {
        return new Simulado((socket, numero) -> {
            Protocolo.ler(socket.getInputStream());
            socket.getOutputStream().write((texto + "\n").getBytes(StandardCharsets.UTF_8));
        });
    }

    private static void concorrencia() {
        caso("16 cadastros e logins concorrentes mantem unicidade", () -> {
            List<JsonObject> cadastros = paralelas(req("register", "user", "estresse", "email", "estresse@teste.com", "password", "senha123"));
            conferir(cadastros.stream().filter(r -> "201".equals(Protocolo.string(r, "status"))).count() == 1, "Cadastros duplicados ou nenhum sucesso");
            conferir(cadastros.stream().filter(r -> "409".equals(Protocolo.string(r, "status"))).count() == 15, "Conflitos de cadastro inesperados");
            List<JsonObject> logins = paralelas(req("login", "email", "estresse@teste.com", "password", "senha123"));
            conferir(logins.stream().filter(r -> "200".equals(Protocolo.string(r, "status"))).count() == 1, "Mais de uma sessao ativa");
            conferir(logins.stream().filter(r -> "409".equals(Protocolo.string(r, "status"))).count() == 15, "Conflitos de login inesperados");
            String token = logins.stream().filter(r -> "200".equals(Protocolo.string(r, "status"))).map(r -> Protocolo.string(r, "token")).findFirst().orElseThrow();
            List<JsonObject> exclusoes = paralelas(req("delete_user", "token", token, "password", "senha123"));
            conferir(exclusoes.stream().filter(r -> "200".equals(Protocolo.string(r, "status"))).count() == 1, "Exclusao concorrente falhou");
            conferir(exclusoes.stream().filter(r -> "401".equals(Protocolo.string(r, "status"))).count() == 15, "Sessao persistiu apos exclusao");
            status(chamar(req("read_user", "token", token)), "401");
        });
    }

    private static List<JsonObject> paralelas(JsonObject req) throws Exception {
        var pool = Executors.newFixedThreadPool(16);
        CountDownLatch inicio = new CountDownLatch(1);
        try {
            List<java.util.concurrent.Future<JsonObject>> tarefas = new ArrayList<>();
            for (int i = 0; i < 16; i++) tarefas.add(pool.submit(() -> { inicio.await(); return chamar(req); }));
            inicio.countDown();
            List<JsonObject> respostas = new ArrayList<>();
            for (var tarefa : tarefas) respostas.add(tarefa.get(15, TimeUnit.SECONDS));
            return respostas;
        } finally { pool.shutdownNow(); }
    }

    private static void falhasEReenvio() {
        caso("Outra bateria e impedida de limpar o banco em uso", () -> {
            int quantidade = new RepositorioArquivo(config).carregar().usuarios.size();
            Process processo = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-cp", System.getProperty("java.class.path"), TesteEp1.class.getName(), caminhoConfig.toString())
                    .redirectErrorStream(true).start();
            if (!processo.waitFor(10, TimeUnit.SECONDS)) {
                processo.destroyForcibly();
                throw new AssertionError("Segunda bateria ficou bloqueada");
            }
            String saida = new String(processo.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            conferir(processo.exitValue() != 0 && saida.contains("Outra bateria"), "Baterias concorrentes nao foram isoladas");
            conferir(new RepositorioArquivo(config).carregar().usuarios.size() == quantidade, "Outra bateria alterou o banco");
        });
        caso("Falha de repositorio retorna 500 sem reenvio e sem derrubar a conexao", () -> {
            AtomicInteger tentativas = new AtomicInteger();
            RepositorioArquivo real = new RepositorioArquivo(config);
            RepositorioUsuarios falho = new RepositorioUsuarios() {
                @Override public Banco carregar() throws IOException { return real.carregar(); }
                @Override public JsonObject transacionar(java.util.function.Function<Banco, JsonObject> operacao) throws IOException {
                    if (tentativas.incrementAndGet() == 1) throw new IOException("Falha de persistencia simulada");
                    return real.transacionar(operacao);
                }
            };
            ServidorTcp temporario = new ServidorTcp(0, new UsuarioService(falho, Clock.systemUTC()), SILENCIO);
            Thread aceitar = new Thread(() -> {
                try { temporario.executar(); } catch (IOException e) { throw new IllegalStateException(e); }
            }, "servidor-falha-db");
            aceitar.start();
            try (Socket socket = new Socket("127.0.0.1", temporario.porta())) {
                socket.setSoTimeout(5000);
                JsonObject cadastro = req("register", "user", "falhadb", "email", "falhadb@teste.com", "password", "senha123");
                Protocolo.enviar(socket.getOutputStream(), cadastro);
                JsonObject res = ler(socket);
                status(res, "500");
                conferir("register_response".equals(Protocolo.string(res, "op")) && tentativas.get() == 1, "Falha foi repetida ou resposta nao identificou operacao");
                Protocolo.enviar(socket.getOutputStream(), cadastro);
                status(ler(socket), "201");
                conferir(tentativas.get() == 2, "Conexao nao se recuperou depois do 500");
            } finally {
                temporario.close();
                aceitar.join(3000);
            }
            try (Simulado simulado = responder("{\"op\":\"login_response\",\"status\":\"500\",\"message\":\"Erro interno do servidor\"}");
                    ClienteTcp cliente = new ClienteTcp("127.0.0.1", simulado.porta(), SILENCIO)) {
                status(cliente.enviar(pedidoCliente("login")), "500");
                conferir(simulado.pedidos.get() == 1, "Cliente reenviou automaticamente uma resposta 500 completa");
            }
        });
        caso("Resposta de login perdida demonstra conflito 409 no reenvio", () -> {
            status(chamar(req("register", "user", "respostaperdida", "email", "respostaperdida@teste.com", "password", "senha123")), "201");
            AtomicReference<String> token = new AtomicReference<>();
            try (Simulado proxy = new Simulado((socket, numero) -> {
                JsonObject pedido = Protocolo.interpretar(Protocolo.ler(socket.getInputStream()).texto());
                JsonObject res = chamar(pedido);
                if (numero == 1) { token.set(Protocolo.string(res, "token")); return; }
                Protocolo.enviar(socket.getOutputStream(), res);
            }); ClienteTcp cliente = new ClienteTcp("127.0.0.1", proxy.porta(), SILENCIO)) {
                status(cliente.enviar(req("login", "email", "respostaperdida@teste.com", "password", "senha123")), "409");
                conferir(proxy.pedidos.get() == 2, "Reenvio depois de EOF nao ocorreu");
            }
            status(chamar(req("read_user", "token", token.get())), "200");
        });
        caso("Logout com resposta perdida e concluido no reenvio com 401", () -> {
            String token = cadastrarLogar("logoutperdido");
            try (Simulado proxy = new Simulado((socket, numero) -> {
                JsonObject pedido = Protocolo.interpretar(Protocolo.ler(socket.getInputStream()).texto());
                JsonObject res = chamar(pedido);
                if (numero > 1) Protocolo.enviar(socket.getOutputStream(), res);
            }); ClienteTcp cliente = new ClienteTcp("127.0.0.1", proxy.porta(), SILENCIO)) {
                status(cliente.enviar(req("logout", "token", token)), "401");
                conferir(proxy.pedidos.get() == 2, "Logout nao foi reenviado");
            }
            status(chamar(req("read_user", "token", token)), "401");
        });
    }

    private static void consoleESessao() {
        caso("Console mantem sessao depois de senha de exclusao incorreta", () -> {
            status(chamar(req("register", "user", "consolesessao", "email", "consolesessao@teste.com", "password", "senha123")), "201");
            String saida = console(porta, "2\nconsolesessao@teste.com\nsenha123\n3\nSIM\nerrada\n1\n4\n0\n");
            conferir(saida.contains("403 - Senha incorreta"), "Senha incorreta nao foi mostrada como 403");
            conferir(saida.contains("Usuario: consolesessao"), "Sessao foi descartada depois do 403");
            conferir(saida.contains("200 - Logout realizado com sucesso"), "Logout nao foi concluido");
        });
        caso("Console nao descarta token ao receber um 401 fora do contrato", () -> {
            String token = "a".repeat(64);
            AtomicBoolean preservado = new AtomicBoolean();
            try (Simulado simulado = new Simulado((socket, numero) -> {
                if (numero == 1) {
                    Protocolo.ler(socket.getInputStream());
                    JsonObject login = Protocolo.resposta("login_response", "200", "Login realizado com sucesso");
                    login.addProperty("token", token);
                    login.addProperty("role", "user");
                    Protocolo.enviar(socket.getOutputStream(), login);
                    Protocolo.ler(socket.getInputStream());
                    Protocolo.enviar(socket.getOutputStream(), Protocolo.resposta("read_user_response", "401", "mensagem falsa"));
                } else {
                    JsonObject pedido = Protocolo.interpretar(Protocolo.ler(socket.getInputStream()).texto());
                    preservado.set("read_user".equals(Protocolo.string(pedido, "op")) && token.equals(Protocolo.string(pedido, "token")));
                    JsonObject leitura = Protocolo.resposta("read_user_response", "200", "Consulta realizada com sucesso");
                    leitura.addProperty("user", "simulado");
                    leitura.addProperty("email", "simulado@teste.com");
                    leitura.addProperty("role", "user");
                    leitura.addProperty("created_at", "2026-10-14 10:00:00");
                    Protocolo.enviar(socket.getOutputStream(), leitura);
                    Protocolo.ler(socket.getInputStream());
                    Protocolo.enviar(socket.getOutputStream(), Protocolo.resposta("logout_response", "200", "Logout realizado com sucesso"));
                }
            })) {
                String saida = console(simulado.porta(), "2\nsimulado@teste.com\nsenha123\n1\n1\n0\n");
                conferir(preservado.get(), "Resposta invalida descartou o token ou provocou login/cadastro indevido");
                conferir(saida.contains("Usuario: simulado"), "Cliente nao continuou com a sessao original");
                conferir(saida.contains("200 - Logout realizado com sucesso"), "Cliente nao concluiu logout");
            }
        });
    }

    private static String console(int porta, String dados) throws Exception {
        Process processo = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), Cliente.class.getName(), "--console", "127.0.0.1", String.valueOf(porta))
                .redirectErrorStream(true).start();
        processo.getOutputStream().write(dados.getBytes(StandardCharsets.UTF_8));
        processo.getOutputStream().close();
        if (!processo.waitFor(15, TimeUnit.SECONDS)) {
            processo.destroyForcibly();
            throw new AssertionError("Cliente nao encerrou");
        }
        String saida = new String(processo.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        conferir(processo.exitValue() == 0, "Cliente encerrou com erro: " + saida);
        return saida;
    }

    private static final class Simulado implements AutoCloseable {
        private final ServerSocket listener = new ServerSocket(0);
        private final Thread thread;
        final AtomicInteger pedidos = new AtomicInteger();
        private final AtomicReference<Throwable> erro = new AtomicReference<>();
        Simulado(Tratamento tratamento) throws IOException {
            listener.setSoTimeout(1000);
            thread = new Thread(() -> {
                while (!listener.isClosed()) {
                    try (Socket socket = listener.accept()) {
                        socket.setSoTimeout(3000);
                        tratamento.atender(socket, pedidos.incrementAndGet());
                    } catch (SocketTimeoutException e) {
                        // Permite encerrar o simulador sem bloquear a bateria.
                    } catch (Exception e) {
                        if (!listener.isClosed()) erro.set(e);
                    }
                }
            }, "servidor-simulado");
            thread.start();
        }
        int porta() { return listener.getLocalPort(); }
        @Override public void close() throws IOException {
            listener.close();
            try { thread.join(4000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
            if (thread.isAlive()) throw new IOException("Simulador nao encerrou");
            if (erro.get() != null) throw new IOException("Falha no servidor simulado", erro.get());
        }
    }
}
