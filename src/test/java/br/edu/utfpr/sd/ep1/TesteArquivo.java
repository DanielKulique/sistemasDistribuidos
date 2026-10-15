package br.edu.utfpr.sd.ep1;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/** Falhas de arquivo e trava efetiva entre processos Java independentes. */
public final class TesteArquivo {
    private static int verificacoes;
    private TesteArquivo() { }
    public static void main(String[] args) throws Exception {
        if (args.length == 1) {
            RepositorioArquivo repo = new RepositorioArquivo(Path.of(args[0]));
            for (int i = 0; i < 20; i++) repo.transacionar(banco -> {
                banco.sessoes.get(0).ultimoUso++;
                return new JsonObject();
            });
            return;
        }
        Path pasta = Files.createTempDirectory(Path.of("build"), "persistencia-");
        Path arquivo = pasta.resolve("estado.json");
        RepositorioArquivo repo = new RepositorioArquivo(arquivo);
        conferir(repo.carregar().usuarios.isEmpty(), "Arquivo ausente deve iniciar vazio");
        repo.transacionar(banco -> {
            Banco.Usuario u = new Banco.Usuario();
            u.id = "id"; u.user = "teste"; u.email = "teste@email.com";
            u.senhaHash = "hash"; u.senhaSalt = "salt"; u.role = "user"; u.createdAt = "2026-10-14 00:00:00";
            banco.usuarios.add(u);
            Banco.Sessao s = new Banco.Sessao();
            s.token = "a".repeat(64); s.usuarioId = u.id;
            banco.sessoes.add(s);
            return new JsonObject();
        });
        String original = Files.readString(arquivo);
        conferir(new RepositorioArquivo(arquivo).carregar().usuarios.size() == 1, "Nova instancia deve ler persistencia");
        try {
            repo.transacionar(banco -> { banco.usuarios.clear(); throw new IllegalStateException("Falha simulada"); });
            throw new AssertionError("Excecao deveria ser propagada");
        } catch (IllegalStateException esperado) { /* Sem commit. */ }
        conferir(original.equals(Files.readString(arquivo)), "Excecao nao deve alterar arquivo");
        for (String invalido : new String[] {"", "{", "null", "{}", "{\"usuarios\":null,\"sessoes\":[]}"}) {
            Files.writeString(arquivo, invalido);
            try { repo.carregar(); throw new AssertionError("Arquivo invalido aceito"); }
            catch (IOException esperado) { /* Dados preservados. */ }
            conferir(invalido.equals(Files.readString(arquivo)), "Arquivo invalido nao deve ser sobrescrito");
        }
        Files.writeString(arquivo, original);
        String executavel = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process primeiro = new ProcessBuilder(executavel, "-cp", System.getProperty("java.class.path"),
                TesteArquivo.class.getName(), arquivo.toString()).redirectErrorStream(true).start();
        Process segundo = new ProcessBuilder(executavel, "-cp", System.getProperty("java.class.path"),
                TesteArquivo.class.getName(), arquivo.toString()).redirectErrorStream(true).start();
        for (Process processo : new Process[] {primeiro, segundo}) {
            if (!processo.waitFor(15, TimeUnit.SECONDS)) { processo.destroyForcibly(); throw new AssertionError("Trava bloqueou processo"); }
            conferir(processo.exitValue() == 0, "Processo falhou: " + new String(processo.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        }
        conferir(repo.carregar().sessoes.get(0).ultimoUso == 40, "Trava deve preservar 40 gravacoes entre processos");
        System.out.println("Arquivo: " + verificacoes + " verificacoes aprovadas.");
    }
    private static void conferir(boolean condicao, String mensagem) {
        verificacoes++;
        if (!condicao) throw new AssertionError(mensagem);
    }
}
