package br.edu.utfpr.sd.ep1;

import com.google.gson.JsonObject;
import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.CharacterCodingException;

/** Transporte sincrono. Reenvia por falha de conexao; resposta invalida nao e reenviada. */
public final class ClienteTcp implements AutoCloseable {
    private final String host;
    private final int porta;
    private final PrintStream log;
    private final int timeoutMs;
    private Socket socket;
    private BufferedInputStream entrada;

    public ClienteTcp(String host, int porta, PrintStream log) {
        this(host, porta, log, 10_000);
    }

    ClienteTcp(String host, int porta, PrintStream log, int timeoutMs) {
        if (timeoutMs <= 0) throw new IllegalArgumentException("Timeout deve ser positivo");
        this.host = host;
        this.porta = porta;
        this.log = log;
        this.timeoutMs = timeoutMs;
    }

    private void conectar() throws IOException {
        if (socket != null && !socket.isClosed()) return;
        Socket novo = new Socket();
        try {
            novo.connect(new InetSocketAddress(host, porta), 5000);
            novo.setSoTimeout(timeoutMs);
            entrada = new BufferedInputStream(novo.getInputStream());
            socket = novo;
            log.println("[TCP] Conectado a " + host + ":" + porta);
        } catch (IOException | RuntimeException e) {
            novo.close();
            throw e;
        }
    }

    public synchronized JsonObject enviar(JsonObject req) throws IOException {
        // Falha local antes de abrir conexao. Mantem os mesmos bytes no reenvio.
        byte[] bytes = Protocolo.codificar(req);
        for (int tentativa = 0; tentativa < 2; tentativa++) {
            try {
                conectar();
                if (entrada.available() > 0) throw new ErroProtocoloException("Dados recebidos sem uma requisicao pendente");
                log.println("C -> S " + Protocolo.GSON.toJson(req));
                socket.getOutputStream().write(bytes);
                socket.getOutputStream().flush();
                Protocolo.Linha linha = Protocolo.ler(entrada);
                if (linha == null) throw new EOFException("Servidor fechou a conexao sem resposta");
                if (linha.excedida()) {
                    log.println("S -> C [resposta acima do limite; prefixo Base64] "
                            + java.util.Base64.getEncoder().encodeToString(linha.bytes()));
                    throw new ErroProtocoloException("Resposta excede o limite de bytes");
                }
                JsonObject resposta;
                try {
                    String texto = linha.texto();
                    log.println("S -> C " + texto);
                    resposta = Protocolo.interpretar(texto);
                } catch (CharacterCodingException | RuntimeException e) {
                    if (e instanceof CharacterCodingException) log.println("S -> C [UTF-8 invalido; Base64] "
                            + java.util.Base64.getEncoder().encodeToString(linha.bytes()));
                    throw new ErroProtocoloException("Resposta JSON/UTF-8 invalida", e);
                }
                ValidacaoResposta.conferir(req, resposta);
                return resposta;
            } catch (ErroProtocoloException e) {
                log.println("[TCP] Resposta rejeitada: " + e.getMessage());
                fecharAposFalha(e);
                throw e;
            } catch (IOException e) {
                log.println("[TCP] Falha na comunicacao: " + e.getMessage());
                fecharAposFalha(e);
                if (tentativa == 1) throw e;
                log.println("Conexao interrompida; reconectando e reenviando a mesma requisicao...");
            }
        }
        throw new IOException("Sem resposta");
    }

    private void fecharAposFalha(IOException original) {
        try { close(); } catch (IOException e) { original.addSuppressed(e); }
    }

    @Override
    public synchronized void close() throws IOException {
        Socket anterior = socket;
        socket = null;
        entrada = null;
        if (anterior != null) {
            anterior.close();
            log.println("[TCP] Conexao encerrada com " + host + ":" + porta);
        }
    }
}
