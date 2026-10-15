package br.edu.utfpr.sd.ep1;

import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.time.Clock;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/** Entrada do NetBeans: inicia servidor local e GUI em uma única execução. */
public final class Aplicacao {
    private Aplicacao() { }

    static ClienteGui iniciar(int porta, Path arquivo) throws IOException, InterruptedException, InvocationTargetException {
        UsuarioService usuarios = new UsuarioService(new RepositorioArquivo(arquivo), Clock.systemUTC());
        ServidorTcp servidor = new ServidorTcp(porta, usuarios, System.out);
        Thread atendimento = new Thread(() -> {
            try { servidor.executar(); }
            catch (IOException e) { System.err.println("Falha no servidor local: " + e.getMessage()); }
        }, "servidor-local");
        atendimento.setDaemon(true);
        atendimento.start();
        ClienteGui[] janela = new ClienteGui[1];
        try {
            SwingUtilities.invokeAndWait(() -> {
                try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
                catch (ReflectiveOperationException | javax.swing.UnsupportedLookAndFeelException e) {
                    System.err.println("Usando aparência padrão: " + e.getMessage());
                }
                janela[0] = new ClienteGui(new String[] {"127.0.0.1", Integer.toString(servidor.porta())});
                janela[0].addWindowListener(new WindowAdapter() {
                    @Override public void windowClosed(WindowEvent e) {
                        try { servidor.close(); }
                        catch (IOException falha) { System.err.println("Falha ao encerrar servidor: " + falha.getMessage()); }
                    }
                });
                janela[0].setVisible(true);
            });
        } catch (InterruptedException | InvocationTargetException | RuntimeException e) {
            try { servidor.close(); } catch (IOException falha) { e.addSuppressed(falha); }
            throw e;
        }
        System.out.println("Aplicação local iniciada. Dados: " + arquivo.toAbsolutePath());
        System.out.println("Cliente e servidor registram todos os JSONs nesta saída.");
        return janela[0];
    }

    public static void main(String[] args) {
        try {
            if (args.length > 2) throw new IllegalArgumentException("Uso: Aplicacao [porta] [arquivo-dados.json]");
            int porta = args.length > 0 ? EntradaConsole.validarPorta(args[0]) : 5000;
            Path arquivo = Path.of(args.length > 1 ? args[1] : "data/ep1.json");
            iniciar(porta, arquivo);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            String mensagem = e instanceof java.net.BindException
                    ? "A porta já está ocupada. Encerre o servidor anterior ou escolha outra porta."
                    : "Não foi possível iniciar: " + e.getMessage();
            System.err.println(mensagem);
            JOptionPane.showMessageDialog(null, mensagem, "EP-1", JOptionPane.ERROR_MESSAGE);
            System.exit(1);
        }
    }
}
