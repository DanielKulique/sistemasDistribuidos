package br.edu.utfpr.sd.ep1;

import com.google.gson.JsonObject;
import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/** Transporte sincrono. Reconecta e reenvia uma vez quando a conexao falha. */
public final class ClienteTcp implements AutoCloseable {
    private final String host;
    private final int porta;
    private final PrintStream log;
    private Socket socket;
    private BufferedInputStream entrada;

    public ClienteTcp(String host, int porta, PrintStream log) {
        this.host = host;
        this.porta = porta;
        this.log = log;
    }

    private void conectar() throws IOException {
        if (socket != null && !socket.isClosed()) return;
        Socket novo = new Socket();
        try {
            novo.connect(new InetSocketAddress(host, porta), 5000);
            novo.setSoTimeout(10_000);
            entrada = new BufferedInputStream(novo.getInputStream());
            socket = novo;
        } catch (IOException | RuntimeException e) {
            novo.close();
            throw e;
        }
    }

    public JsonObject enviar(JsonObject req) throws IOException {
        for (int tentativa = 0; tentativa < 2; tentativa++) {
            try {
                conectar();
                log.println("C -> S " + Protocolo.GSON.toJson(req));
                Protocolo.enviar(socket.getOutputStream(), req);
                Protocolo.Linha linha = Protocolo.ler(entrada);
                if (linha == null) throw new EOFException("Servidor fechou a conexao sem resposta");
                if (linha.excedida()) throw new IOException("Resposta excede o limite de bytes");
                String texto = linha.texto();
                log.println("S -> C " + texto);
                JsonObject resposta;
                try { resposta = Protocolo.interpretar(texto); }
                catch (RuntimeException e) { throw new IOException("Resposta JSON invalida", e); }
                String op = Protocolo.string(resposta, "op");
                if (!(Protocolo.string(req, "op") + "_response").equals(op)
                        || Protocolo.string(resposta, "status") == null || Protocolo.string(resposta, "message") == null) {
                    throw new IOException("Resposta fora do protocolo da operacao");
                }
                return resposta;
            } catch (IOException e) {
                close();
                if (tentativa == 1) throw e;
                log.println("Conexao interrompida; reconectando e reenviando a mesma requisicao...");
            }
        }
        throw new IOException("Sem resposta");
    }

    @Override
    public void close() throws IOException {
        Socket anterior = socket;
        socket = null;
        entrada = null;
        if (anterior != null) anterior.close();
    }
}
