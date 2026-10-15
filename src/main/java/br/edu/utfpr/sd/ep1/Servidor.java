package br.edu.utfpr.sd.ep1;

import java.io.IOException;
import java.net.BindException;
import java.nio.file.Path;
import java.time.Clock;

public final class Servidor {
    private Servidor() { }

    public static void main(String[] args) {
        if (args.length > 2) {
            System.err.println("Uso: servidor.sh [porta] [configuracao-mongo]");
            System.exit(1);
        }
        try {
            EntradaConsole console = new EntradaConsole();
            int porta = args.length > 0 ? EntradaConsole.validarPorta(args[0]) : console.porta("Porta do servidor: ");
            Path arquivo = Path.of(args.length > 1 ? args[1] : "config/mongo.properties");
            try (ConfiguracaoMongo config = ConfiguracaoMongo.carregar(arquivo)) {
                UsuarioService usuarios = new UsuarioService(new RepositorioMongo(config), Clock.systemUTC());
                try (ServidorTcp servidor = new ServidorTcp(porta, usuarios, System.out)) {
                    registrarEncerramento(servidor);
                    System.out.println("MongoDB configurado por: " + arquivo.toAbsolutePath());
                    servidor.executar();
                }
            }
        } catch (BindException e) {
            System.err.println("Porta ocupada. Escolha outra porta.");
            System.exit(1);
        } catch (IOException | IllegalArgumentException e) {
            System.err.println("Nao foi possivel iniciar o servidor: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void registrarEncerramento(ServidorTcp servidor) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { servidor.close(); } catch (IOException e) { System.err.println(e.getMessage()); }
        }, "encerrar-servidor"));
    }
}
