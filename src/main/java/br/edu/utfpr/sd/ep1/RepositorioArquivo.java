package br.edu.utfpr.sd.ep1;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import static java.nio.file.StandardOpenOption.*;

/** Arquivo JSON UTF-8, trava entre processos e substituição atômica. */
public final class RepositorioArquivo implements RepositorioUsuarios {
    private static final ConcurrentHashMap<Path, ReentrantLock> TRAVAS = new ConcurrentHashMap<>();
    private final Path arquivo;

    public RepositorioArquivo(Path arquivo) {
        this.arquivo = arquivo.toAbsolutePath().normalize();
    }

    private <T> T exclusivo(Operacao<T> operacao) throws IOException {
        Files.createDirectories(arquivo.getParent());
        Path canonico = arquivo.getParent().toRealPath().resolve(arquivo.getFileName());
        ReentrantLock local = TRAVAS.computeIfAbsent(canonico, p -> new ReentrantLock());
        local.lock();
        try (FileChannel canal = FileChannel.open(canonico.resolveSibling(canonico.getFileName() + ".lock"), CREATE, WRITE);
                var trava = canal.lock()) {
            if (!trava.isValid()) throw new IOException("Não foi possível bloquear o arquivo");
            return operacao.executar();
        } finally { local.unlock(); }
    }

    @FunctionalInterface private interface Operacao<T> { T executar() throws IOException; }

    private Banco ler() throws IOException {
        if (!Files.exists(arquivo)) return new Banco();
        try {
            JsonObject objeto = Protocolo.interpretar(Files.readString(arquivo, StandardCharsets.UTF_8));
            if (!objeto.has("usuarios") || !objeto.get("usuarios").isJsonArray()
                    || !objeto.has("sessoes") || !objeto.get("sessoes").isJsonArray())
                throw new IOException("Estrutura inválida no arquivo " + arquivo);
            Banco banco = Protocolo.GSON.fromJson(objeto, Banco.class);
            validar(banco);
            return banco;
        } catch (RuntimeException e) { throw new IOException("Arquivo de dados inválido: " + arquivo + ". Preserve-o para recuperação.", e); }
    }

    @Override public Banco carregar() throws IOException { return exclusivo(this::ler); }

    @Override public JsonObject transacionar(Function<Banco, JsonObject> operacao) throws IOException {
        return exclusivo(() -> {
            Banco novo = ler();
            JsonObject resposta = operacao.apply(novo);
            validar(novo);
            Path temporario = Files.createTempFile(arquivo.getParent(), "ep1-", ".tmp");
            try {
                byte[] bytes = Protocolo.GSON.toJson(novo).getBytes(StandardCharsets.UTF_8);
                try (FileChannel canal = FileChannel.open(temporario, WRITE, TRUNCATE_EXISTING)) {
                    ByteBuffer buffer = ByteBuffer.wrap(bytes);
                    while (buffer.hasRemaining()) canal.write(buffer);
                    canal.force(true);
                }
                Files.move(temporario, arquivo, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally { Files.deleteIfExists(temporario); }
            return resposta;
        });
    }

    private static void validar(Banco banco) throws IOException {
        Set<String> ids = new HashSet<>(), nomes = new HashSet<>(), emails = new HashSet<>();
        for (Banco.Usuario u : banco.usuarios) {
            if (u == null || u.senhaHash == null || u.senhaSalt == null || u.role == null || u.createdAt == null)
                throw new IOException("Usuário incompleto no arquivo");
            if (u.id == null || u.user == null || u.email == null || !ids.add(u.id)
                    || !nomes.add(u.user) || !emails.add(u.email))
                throw new IOException("Usuario, nome ou email duplicado/invalido");
        }
        Set<String> tokens = new HashSet<>(), usuarios = new HashSet<>();
        for (Banco.Sessao s : banco.sessoes) {
            if (s == null || s.token == null || !ids.contains(s.usuarioId) || !tokens.add(s.token) || !usuarios.add(s.usuarioId))
                throw new IOException("Sessao duplicada ou sem usuario");
        }
    }
}
