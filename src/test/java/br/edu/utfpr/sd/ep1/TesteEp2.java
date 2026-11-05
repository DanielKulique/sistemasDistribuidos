package br.edu.utfpr.sd.ep1;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** EP-2 por sockets reais, MySQL real, multiplos servidores e reinicio de processo. */
@SuppressWarnings("try")
public final class TesteEp2 {
    private static final PrintStream SILENCIO = new PrintStream(OutputStream.nullOutputStream());
    private static final List<JsonObject> CAPTURAS = new ArrayList<>();
    private static int verificacoes;
    private static int porta;
    private static SuporteMySqlTeste banco;
    private static final Relogio CLOCK = new Relogio();
    private TesteEp2() { }

    private static final class Relogio extends Clock {
        private volatile Instant agora = Instant.now();
        void avancar(Duration d) { agora = agora.plus(d); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId z) { return Clock.fixed(agora, z); }
        @Override public Instant instant() { return agora; }
    }

    public static void main(String[] args) throws Exception {
        try (SuporteMySqlTeste suporte = new SuporteMySqlTeste(args)) {
            banco = suporte; banco.limpar();
            try {
                bootstrapConcorrente();
                try (ServidorTcp servidor = new ServidorTcp(0, new UsuarioService(banco.repositorio, CLOCK), SILENCIO)) {
                    Thread thread = iniciar(servidor); porta = servidor.porta();
                    try { fluxo(); } finally { servidor.close(); thread.join(3000); }
                }
                Files.writeString(Path.of("build/respostas-ep2.json"), Protocolo.GSON.toJson(CAPTURAS));
                System.out.println("EP-2 MySQL: " + verificacoes + " verificacoes aprovadas; " + CAPTURAS.size() + " respostas TCP.");
            } finally { banco.limpar(); }
        }
    }

    private static Thread iniciar(ServidorTcp s) {
        Thread t = new Thread(() -> { try { s.executar(); } catch (IOException e) { throw new IllegalStateException(e); } });
        t.start(); return t;
    }

    private static void ok(boolean valor, String mensagem) {
        verificacoes++; if (!valor) throw new AssertionError(mensagem);
    }

    private static JsonObject req(String op, String token, String... campos) {
        JsonObject r = new JsonObject(); r.addProperty("op", op);
        if (token != null) r.addProperty("token", token);
        for (int i = 0; i < campos.length; i += 2) r.addProperty(campos[i], campos[i + 1]);
        return r;
    }

    private static JsonObject chamar(JsonObject req, String status) throws IOException {
        JsonObject res;
        try (ClienteTcp cliente = new ClienteTcp("127.0.0.1", porta, SILENCIO)) { res = cliente.enviar(req); }
        ok(status.equals(Protocolo.string(res, "status")), "Esperado " + status + ", recebido " + res + " para " + req.get("op"));
        boolean excecao = "update_room".equals(Protocolo.string(req, "op")) && status.equals("409")
                && "Sala possui reservas ativas".equals(Protocolo.string(res, "message"));
        ok(excecao || Contrato.mensagem(Protocolo.string(res, "op"), res), "Resposta fora do contrato: " + res);
        JsonObject captura = new JsonObject(); captura.add("request", req); captura.add("response", res);
        synchronized (CAPTURAS) { CAPTURAS.add(captura); }
        return res;
    }

    private static String login(String email, String senha) throws IOException {
        return Protocolo.string(chamar(req("login", null, "email", email, "password", senha), "200"), "token");
    }

    private static JsonObject sala(String op, String token, String id, String nome, String capacidade, String status, String... slots) {
        JsonObject r = req(op, token, "name", nome, "capacity", capacidade, "location", "Bloco B");
        r.add("resources", new JsonArray());
        if (id != null) { r.addProperty("room_id", id); r.addProperty("room_status", status); }
        r.add("slots", Protocolo.GSON.toJsonTree(List.of(slots))); return r;
    }

    private static JsonObject reserva(String token, String sala, String data, String inicio, String fim) {
        return req("create_reservation", token, "room_id", sala, "date", data, "start_time", inicio,
                "end_time", fim, "topic", "Estudo", "participants", "3");
    }

    private static void bootstrapConcorrente() throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        try {
            var a = pool.submit(() -> new UsuarioService(new RepositorioMySql(banco.config), CLOCK));
            var b = pool.submit(() -> new UsuarioService(new RepositorioMySql(banco.config), CLOCK));
            a.get(10, TimeUnit.SECONDS); b.get(10, TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); }
        Banco estado = banco.repositorio.carregar();
        ok(estado.usuarios.size() == 1 && estado.usuarios.get(0).role.equals("admin"), "Bootstrap idempotente entre servidores");
        var admin = estado.usuarios.get(0);
        ok(admin.user.equals("admin") && admin.email.equals("admin@admin.com") && Senhas.conferir(admin, "admin")
                && !admin.senhaHash.equals("admin"), "Credenciais e hash do admin inicial");
    }

    private static void fluxo() throws Exception {
        String admin = login("admin@admin.com", "admin");
        chamar(req("register", null, "user", "alfa", "email", "alfa@teste.com", "password", "senha123", "role", "admin"), "201");
        chamar(req("register", null, "user", "beta", "email", "beta@teste.com", "password", "senha123"), "201");
        String alfa = login("alfa@teste.com", "senha123"), beta = login("beta@teste.com", "senha123");
        ok("user".equals(Protocolo.string(chamar(req("read_user", alfa), "200"), "role")), "Cadastro ignora perfil enviado pelo cliente");
        chamar(req("admin_list_users", alfa, "role", "admin"), "403");
        chamar(req("admin_list_users", "x"), "400"); chamar(req("admin_list_users", "0".repeat(64)), "401");
        chamar(req("admin_list_users", null), "400");
        JsonObject lista = chamar(req("admin_list_users", admin), "200");
        ok(lista.getAsJsonArray("users").size() == 3, "Listagem completa sem segredos");
        chamar(req("admin_read_user", admin, "target_user", "inexistente"), "404");
        chamar(req("admin_read_user", admin, "target_user", "alfa"), "200");
        chamar(req("admin_update_user", admin, "target_user", "alfa", "user", "", "password", "", "role", ""), "400");
        chamar(req("admin_update_user", admin, "target_user", "alfa", "user", "beta", "password", "", "role", ""), "409");
        chamar(req("admin_update_user", admin, "target_user", "alfa", "user", "", "password", "", "role", "", "email", "novo@teste.com"), "400");
        chamar(req("admin_delete_user", admin, "target_user", "admin"), "403");
        chamar(req("admin_update_user", admin, "target_user", "admin", "user", "", "password", "", "role", "user"), "403");
        chamar(req("delete_user", admin, "password", "admin"), "403");
        chamar(req("admin_update_user", admin, "target_user", "alfa", "user", "", "password", "", "role", "admin"), "200");
        chamar(req("admin_list_users", alfa), "200");
        new UsuarioService(new RepositorioMySql(banco.config), CLOCK);
        chamar(req("admin_list_users", alfa), "200");
        chamar(req("admin_update_user", admin, "target_user", "alfa", "user", "", "password", "", "role", "user"), "200");
        chamar(req("admin_list_users", alfa), "403");
        chamar(sala("create_room", alfa, null, "Sala A", "6", null, "2M1"), "403");
        for (String slot : List.of("1M1", "2N6", "2M0", "8T1", "2m1"))
            chamar(sala("create_room", admin, null, "Sala A", "6", null, slot), "400");
        chamar(sala("create_room", admin, null, "Sala A", "6", null), "400");
        chamar(sala("create_room", admin, null, "Sala A", "6", null, "2M1", "2M1"), "400");
        JsonObject cadastro = sala("create_room", admin, null, "Sala A", "6", null, "2M1", "2M2", "2M3", "3T1");
        cadastro.getAsJsonArray("resources").add("projeção");
        String id = Protocolo.string(chamar(cadastro, "201"), "room_id");
        chamar(cadastro, "409");
        chamar(req("read_room", beta, "room_id", "999999"), "404");
        String data = LocalDate.now(CLOCK.withZone(SalaService.FUSO)).with(TemporalAdjusters.next(DayOfWeek.MONDAY)).toString();
        JsonObject leitura = chamar(req("read_room", beta, "room_id", id, "date", data), "200");
        ok(leitura.getAsJsonArray("agenda").size() == 3, "Agenda ordenada por dia e aula");
        ok(leitura.getAsJsonArray("resources").get(0).getAsString().equals("projeção"), "Recursos UTF-8 persistidos sem perda");
        chamar(req("read_room", beta, "room_id", id, "date", "2026-02-30"), "400");
        JsonObject livre = chamar(req("list_rooms", beta), "200");
        ok(!livre.getAsJsonArray("rooms").get(0).getAsJsonObject().has("available"), "Consulta sem periodo omite disponibilidade");
        chamar(req("list_rooms", beta, "min_capacity", "7"), "200");
        chamar(req("list_rooms", beta, "date", data), "400");
        String reserva = Protocolo.string(chamar(reserva(alfa, id, data, "07:30", "08:20"), "201"), "reservation_id");
        chamar(reserva(beta, id, data, "07:30", "08:20"), "409");
        chamar(reserva(beta, id, data, "08:20", "09:10"), "201");
        chamar(reserva(beta, id, data, "14:00", "16:00"), "400");
        chamar(reserva(beta, id, data, "10:20", "11:10"), "409");
        chamar(req("read_reservation", beta, "reservation_id", reserva), "403");
        chamar(req("read_reservation", admin, "reservation_id", reserva), "200");
        chamar(req("list_reservations", beta, "scope", "all"), "403");
        chamar(req("list_reservations", admin, "scope", "all", "room_id", id), "200");
        JsonObject agenda = chamar(req("read_room", beta, "room_id", id, "date", data), "200");
        JsonObject primeiro = agenda.getAsJsonArray("agenda").get(0).getAsJsonObject();
        ok(primeiro.get("user").getAsString().equals("alfa") && !primeiro.has("reservation_id"), "Agenda publica dono, protege id de terceiro");
        ok(chamar(req("read_room", admin, "room_id", id, "date", data), "200").getAsJsonArray("agenda").get(0).getAsJsonObject().has("reservation_id"), "Admin ve id da reserva");
        chamar(sala("update_room", admin, id, "", "2", ""), "409");
        JsonObject atualizar = sala("update_room", admin, id, "Sala Nova", "8", "inactive"); atualizar.remove("slots");
        chamar(atualizar, "200");
        chamar(reserva(alfa, id, data, "09:10", "10:00"), "409");
        ok(chamar(req("read_room", beta, "room_id", id), "200").getAsJsonArray("slots").size() == 4, "Slots ausentes mantem funcionamento");
        chamar(sala("update_room", admin, id, "", "", "active"), "200");
        chamar(req("delete_room", admin, "room_id", id), "409");
        concorrencia(admin, beta, data);
        reiniciarProcesso(admin, id);
        rollbackSql(id);
        chamar(req("admin_update_user", admin, "target_user", "alfa", "user", "alfanova", "password", "nova123", "role", ""), "200");
        ok("alfanova".equals(Protocolo.string(chamar(req("read_user", alfa), "200"), "user")), "UUID preserva sessao apos renomear");
        chamar(req("admin_delete_user", admin, "target_user", "alfanova"), "200");
        chamar(req("read_user", alfa), "401");
        ok(banco.repositorio.carregar().reservas.stream().noneMatch(r -> r.id.equals(reserva)), "Exclusao remove reservas dependentes");
        chamar(req("register", null, "user", "alfanova", "email", "alfa@teste.com", "password", "senha123"), "201");
        chamar(req("delete_room", beta, "room_id", id), "403");
        String domingo = LocalDate.parse(data).plusDays(6).toString();
        ok("false".equals(Protocolo.string(chamar(req("check_availability", beta, "room_id", id, "date", domingo, "start_time", "07:30", "end_time", "08:20"), "200"), "available")), "Domingo nao funciona");
        CLOCK.avancar(Duration.ofMinutes(30));
        chamar(req("read_user", beta), "401"); chamar(req("admin_list_users", admin), "401");
        admin = login("admin@admin.com", "admin");
        chamar(req("logout", admin), "200"); chamar(req("admin_list_users", admin), "401");
        admin = login("admin@admin.com", "admin");
        CLOCK.avancar(Duration.ofDays(15)); admin = login("admin@admin.com", "admin");
        chamar(req("delete_room", admin, "room_id", id), "200"); chamar(req("read_room", admin, "room_id", id), "404");
        chamar(req("admin_delete_user", admin, "target_user", "naoexiste"), "404");
        System.out.println("OK: admin, whitelist, CRUD, UTFPR, agenda, reservas, persistencia, rollback e concorrencia.");
    }

    private static void concorrencia(String admin, String usuario, String data) throws Exception {
        String id = Protocolo.string(chamar(sala("create_room", admin, null, "Concorrente", "8", null, "2M1"), "201"), "room_id");
        try (ServidorTcp segundo = new ServidorTcp(0, new UsuarioService(new RepositorioMySql(banco.config), CLOCK), SILENCIO)) {
            Thread thread = iniciar(segundo);
            var pool = Executors.newFixedThreadPool(2); CountDownLatch inicio = new CountDownLatch(1);
            try {
                var a = pool.submit(() -> { inicio.await(); try (ClienteTcp c = new ClienteTcp("127.0.0.1", porta, SILENCIO)) { return c.enviar(reserva(usuario,id,data,"07:30","08:20")); } });
                var b = pool.submit(() -> { inicio.await(); try (ClienteTcp c = new ClienteTcp("127.0.0.1", segundo.porta(), SILENCIO)) { return c.enviar(reserva(usuario,id,data,"07:30","08:20")); } });
                inicio.countDown();
                List<String> codigos = List.of(Protocolo.string(a.get(10, TimeUnit.SECONDS),"status"),Protocolo.string(b.get(10, TimeUnit.SECONDS),"status")).stream().sorted().toList();
                ok(codigos.equals(List.of("201","409")), "Reserva atomica entre dois servidores");
                List<String> criacoes = paralelas(pool, segundo.porta(),
                        sala("create_room",admin,null,"Disputa","8",null,"2M1"),
                        sala("create_room",admin,null,"Disputa","8",null,"2M1"));
                ok(criacoes.equals(List.of("201","409")),"Criacao concorrente preserva nome unico");
                String disputa = banco.repositorio.carregar().salas.stream().filter(s -> s.nome.equals("Disputa")).findFirst().orElseThrow().id;
                List<String> exclusoes = paralelas(pool, segundo.porta(), req("delete_room",admin,"room_id",disputa),req("delete_room",admin,"room_id",disputa));
                ok(exclusoes.equals(List.of("200","404")),"Exclusao concorrente produz uma unica remocao");
                String outra = Protocolo.string(chamar(sala("create_room",admin,null,"Outra","8",null,"2M1"),"201"),"room_id");
                List<String> atualizacoes = paralelas(pool, segundo.porta(),
                        sala("update_room",admin,id,"Mesmo Nome","",""),sala("update_room",admin,outra,"Mesmo Nome","",""));
                ok(atualizacoes.equals(List.of("200","409")),"Atualizacao concorrente nao duplica nome");
            } finally { pool.shutdownNow(); segundo.close(); thread.join(3000); }
        }
    }

    private static List<String> paralelas(java.util.concurrent.ExecutorService pool, int segundaPorta, JsonObject primeira, JsonObject segunda) throws Exception {
        CountDownLatch inicio = new CountDownLatch(1);
        var a = pool.submit(() -> { inicio.await(); try (ClienteTcp c = new ClienteTcp("127.0.0.1",porta,SILENCIO)) { return c.enviar(primeira); } });
        var b = pool.submit(() -> { inicio.await(); try (ClienteTcp c = new ClienteTcp("127.0.0.1",segundaPorta,SILENCIO)) { return c.enviar(segunda); } });
        inicio.countDown();
        return List.of(Protocolo.string(a.get(10,TimeUnit.SECONDS),"status"),Protocolo.string(b.get(10,TimeUnit.SECONDS),"status")).stream().sorted().toList();
    }

    private static void reiniciarProcesso(String token, String id) throws Exception {
        int livre;
        try (ServerSocket s = new ServerSocket(0)) { livre = s.getLocalPort(); }
        Path log = Path.of("build/reinicio-mysql.log");
        Process p = new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",System.getProperty("java.class.path"),Servidor.class.getName(),Integer.toString(livre),banco.arquivo.toString()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        int anterior = porta;
        try {
            boolean pronto = false;
            for (int i=0;i<50;i++) {
                try (Socket s = new Socket("127.0.0.1",livre)) { pronto = s.isConnected(); break; }
                catch (IOException e) { if (!p.isAlive()) throw new AssertionError(Files.readString(log)); Thread.sleep(100); }
            }
            ok(pronto,"Processo MySQL reiniciado"); porta = livre;
            chamar(req("admin_list_users",token),"200"); chamar(req("read_room",token,"room_id",id),"200");
        } finally { porta=anterior; p.destroy(); if (!p.waitFor(5,TimeUnit.SECONDS)) p.destroyForcibly(); }
    }

    private static void rollbackSql(String sala) throws Exception {
        Banco antes = banco.repositorio.carregar(); String nome = antes.usuarios.get(0).user;
        boolean falhou = false;
        try {
            banco.repositorio.transacionar(b -> {
                b.usuarios.get(0).user = "rollbacksql";
                SalaService.encontrar(b,sala).capacidade=0;
                return new JsonObject();
            });
        } catch (IOException e) { falhou=true; }
        Banco depois = banco.repositorio.carregar();
        ok(falhou && depois.usuarios.get(0).user.equals(nome) && SalaService.encontrar(depois,sala).capacidade>0,"Rollback de erro SQL desfaz gravacao anterior");
        try (var c=banco.config.abrir(); var stmt=c.prepareStatement("INSERT INTO sessoes(token,usuario_id,ultimo_uso) VALUES(?,?,?)")) {
            stmt.setString(1,"f".repeat(64)); stmt.setString(2,"00000000-0000-0000-0000-000000000000"); stmt.setLong(3,0);
            try { stmt.executeUpdate(); throw new AssertionError("FK nao protegeu sessao"); }
            catch (java.sql.SQLException e) { ok(e.getSQLState().startsWith("23"),"FK de sessoes existe no MySQL"); }
        }
    }
}
