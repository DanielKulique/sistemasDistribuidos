package br.edu.utfpr.sd.ep1;

import com.google.gson.JsonObject;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Uma thread por conexao. Todos os atendimentos compartilham o mesmo servico. */
public final class ServidorTcp implements AutoCloseable {
    private final ServerSocket servidor;
    private final UsuarioService usuarios;
    private final PrintStream log;
    private final int timeoutMs;
    private final Set<Socket> conexoes = ConcurrentHashMap.newKeySet();
    private final AtomicInteger sequencia = new AtomicInteger();

    public ServidorTcp(int porta, UsuarioService usuarios, PrintStream log) throws IOException {
        this(porta, usuarios, log, 300_000);
    }

    ServidorTcp(int porta, UsuarioService usuarios, PrintStream log, int timeoutMs) throws IOException {
        servidor = new ServerSocket(porta);
        this.usuarios = usuarios;
        this.log = log;
        this.timeoutMs = timeoutMs;
    }

    public int porta() { return servidor.getLocalPort(); }

    public void executar() throws IOException {
        log.println("Servidor TCP na porta " + porta() + ". Aguardando conexoes...");
        while (!servidor.isClosed()) {
            try {
                Socket cliente = servidor.accept();
                conexoes.add(cliente);
                if (servidor.isClosed()) {
                    cliente.close();
                    conexoes.remove(cliente);
                    break;
                }
                Thread atendimento = new Thread(() -> atender(cliente), "cliente-" + sequencia.incrementAndGet());
                atendimento.start();
            } catch (SocketException e) {
                if (!servidor.isClosed()) throw e;
            }
        }
    }

    private void atender(Socket cliente) {
        String origem = cliente.getRemoteSocketAddress().toString();
        log.println("[" + origem + "] conectado em " + Thread.currentThread().getName());
        try (cliente; BufferedInputStream entrada = new BufferedInputStream(cliente.getInputStream())) {
            cliente.setSoTimeout(timeoutMs);
            Protocolo.Linha linha;
            while ((linha = Protocolo.ler(entrada)) != null) {
                JsonObject resposta;
                if (linha.excedida()) {
                    log.println("[" + origem + "] recebida linha acima de " + Protocolo.LIMITE_BYTES + " bytes");
                    resposta = Protocolo.erro("Mensagem excede o tamanho maximo");
                } else {
                    JsonObject req = null;
                    try {
                        String texto = linha.texto();
                        log.println("[" + origem + "] C -> S " + texto);
                        req = Protocolo.interpretar(texto);
                    } catch (RuntimeException | IOException e) {
                        // JSON ou UTF-8 invalidos recebem uma resposta, mantendo a conexao.
                    }
                    if (req == null) {
                        resposta = Protocolo.erro("Requisicao invalida");
                    } else {
                        try {
                            resposta = usuarios.executar(req);
                        } catch (IOException | RuntimeException e) {
                            log.println("[" + origem + "] falha interna: " + e.getClass().getSimpleName());
                            String op = Protocolo.string(req, "op");
                            resposta = Protocolo.resposta(op == null ? "error" : op + "_response", "500", "Erro interno do servidor");
                        }
                    }
                }
                if ((Protocolo.GSON.toJson(resposta) + "\n").getBytes(StandardCharsets.UTF_8).length > Protocolo.LIMITE_BYTES) {
                    log.println("[" + origem + "] resposta nao cabe no limite do protocolo");
                    resposta = Protocolo.resposta(Protocolo.string(resposta, "op"), "500", "Erro interno do servidor");
                }
                Protocolo.enviar(cliente.getOutputStream(), resposta);
                log.println("[" + origem + "] S -> C " + Protocolo.GSON.toJson(resposta));
            }
        } catch (SocketTimeoutException e) {
            log.println("[" + origem + "] conexao encerrada por inatividade; token preservado");
        } catch (IOException e) {
            if (!servidor.isClosed()) log.println("[" + origem + "] conexao encerrada: " + e.getMessage());
        } finally {
            conexoes.remove(cliente);
            log.println("[" + origem + "] desconectado");
        }
    }

    @Override
    public void close() throws IOException {
        servidor.close();
        for (Socket cliente : conexoes) {
            try { cliente.close(); } catch (IOException e) { log.println("Falha ao fechar conexao: " + e.getMessage()); }
        }
    }
}
