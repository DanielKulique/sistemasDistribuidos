package br.edu.utfpr.sd.ep1;

import java.awt.Component;
import java.awt.Container;
import java.awt.event.WindowEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.swing.*;

/** Mesmo ponto de entrada usado pelo F6: GUI visível e servidor real em arquivo isolado. */
public final class TesteAplicacao {
    private TesteAplicacao() { }
    public static void main(String[] args) throws Exception {
        Path arquivo = Files.createTempDirectory(Path.of("build"), "netbeans-").resolve("estado.json");
        ClienteGui janela = Aplicacao.iniciar(0, arquivo);
        try {
            SwingUtilities.invokeAndWait(() -> {
                if (!janela.isShowing()) throw new AssertionError("A janela não abriu");
                ((JTextField) componente(janela, "nome")).setText("netbeans");
                ((JTextField) componente(janela, "email")).setText("netbeans@email.com");
                ((JPasswordField) componente(janela, "senha")).setText("senha123");
                ((JButton) componente(janela, "cadastrar")).doClick();
            });
            long limite = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
            boolean[] pronto = new boolean[1];
            do {
                SwingUtilities.invokeAndWait(() -> pronto[0] = componente(janela, "cadastrar").isEnabled());
                if (System.nanoTime() > limite) throw new AssertionError("Cadastro não terminou");
                if (!pronto[0]) Thread.sleep(20);
            } while (!pronto[0]);
            SwingUtilities.invokeAndWait(() -> {
                if (!((JLabel) componente(janela, "status")).getText().startsWith("201"))
                    throw new AssertionError("Cadastro pela aplicação integrada falhou");
            });
            if (new RepositorioArquivo(arquivo).carregar().usuarios.size() != 1)
                throw new AssertionError("Cadastro não foi persistido");
        } finally {
            SwingUtilities.invokeAndWait(() -> janela.dispatchEvent(new WindowEvent(janela, WindowEvent.WINDOW_CLOSING)));
        }
        System.out.println("Aplicacao: GUI visivel, cadastro TCP, persistencia e encerramento aprovados.");
    }
    private static Component componente(Container pai, String nome) {
        for (Component filho : pai.getComponents()) {
            if (nome.equals(filho.getName())) return filho;
            if (filho instanceof Container container) {
                Component achado = componente(container, nome);
                if (achado != null) return achado;
            }
        }
        return null;
    }
}
