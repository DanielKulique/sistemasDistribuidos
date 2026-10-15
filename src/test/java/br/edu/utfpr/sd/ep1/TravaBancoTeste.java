package br.edu.utfpr.sd.ep1;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import static java.nio.file.StandardOpenOption.*;

/** Impede duas baterias simultâneas e protege o arquivo da aplicação. */
final class TravaBancoTeste implements AutoCloseable {
    private final FileChannel canal;
    private final FileLock trava;
    private final Path arquivo;
    TravaBancoTeste(Path arquivo) throws IOException {
        conferir(arquivo);
        this.arquivo = arquivo;
        Files.createDirectories(arquivo.toAbsolutePath().getParent());
        canal = FileChannel.open(arquivo.resolveSibling("bateria.lock"), CREATE, WRITE);
        try {
            trava = canal.tryLock();
            if (trava == null) throw new IOException("Outra bateria já está usando o arquivo de testes");
        } catch (IOException | OverlappingFileLockException e) {
            canal.close();
            throw new IOException("Outra bateria já está usando o arquivo de testes", e);
        }
    }
    static void conferir(Path arquivo) throws IOException {
        Path esperado = Path.of("build/ep1-teste.json").toAbsolutePath().normalize();
        if (!arquivo.toAbsolutePath().normalize().equals(esperado))
            throw new IOException("Os testes exigem build/ep1-teste.json; dados da aplicação são protegidos");
    }
    void conferirBanco() throws IOException { conferir(arquivo); }
    @Override public void close() throws IOException {
        try { trava.release(); } finally { canal.close(); }
    }
}
