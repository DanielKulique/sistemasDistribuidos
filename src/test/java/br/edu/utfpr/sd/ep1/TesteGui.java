package br.edu.utfpr.sd.ep1;

import com.google.gson.JsonObject;
import java.awt.Component;
import java.awt.Container;
import java.awt.Window;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.function.Function;
import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.text.JTextComponent;

/** Exercita componentes Swing na EDT com servidor TCP real, sem exigir banco externo. */
public final class TesteGui {
    private static ClienteGui tela;
    private static int verificacoes;
    private TesteGui() { }

    @SuppressWarnings("try")
    public static void main(String[] args) throws Exception {
        PrintStream terminal = System.out;
        ByteArrayOutputStream mensagensCliente = new ByteArrayOutputStream();
        ByteArrayOutputStream mensagensServidor = new ByteArrayOutputStream();
        RepositorioUsuarios memoria = new RepositorioUsuarios() {
            private Banco estado = new Banco();
            @Override public synchronized Banco carregar() { return estado.copiar(); }
            @Override public synchronized JsonObject transacionar(Function<Banco, JsonObject> operacao) {
                Banco novo = estado.copiar();
                JsonObject resposta = operacao.apply(novo);
                estado = novo;
                return resposta;
            }
        };
        try (PrintStream logCliente = new PrintStream(mensagensCliente, true, StandardCharsets.UTF_8);
                PrintStream logServidor = new PrintStream(mensagensServidor, true, StandardCharsets.UTF_8);
                ServidorTcp servidor = new ServidorTcp(0, new UsuarioService(memoria, Clock.systemUTC()), logServidor)) {
            Thread thread = new Thread(() -> {
                try { servidor.executar(); } catch (java.io.IOException e) { throw new IllegalStateException(e); }
            }, "servidor-teste-gui");
            thread.start();
            System.setOut(logCliente);
            try {
                SwingUtilities.invokeAndWait(() -> tela = new ClienteGui(new String[] {"127.0.0.1", "" + servidor.porta()}));
                preencher("email", "gui@email.com");
                preencher("nome", "usuario");
                preencher("senha", "senha123");
                clicar("cadastrar", "201");
                conferir(memoria.carregar().usuarios.size() == 1, "Cadastro pela GUI");
                preencher("senha", "errada");
                clicar("entrar", "401");
                conferir(habilitado("host"), "Login incorreto deve manter tela de acesso");
                preencher("senha", "senha123");
                clicar("entrar", "200");
                conferir(!habilitado("host"), "Servidor deve ficar fixo durante a sessao");
                clicar("consultar", "200");
                conferir(texto("cadastro").contains("gui@email.com"), "Consulta deve exibir cadastro");
                imagem("build/gui-conta.png");
                preencher("novoNome", "alterado");
                preencher("novaSenha", "nova123");
                clicar("atualizar", "200");
                clicar("consultar", "200");
                conferir(texto("cadastro").contains("alterado"), "Atualizacao deve preservar sessao");
                preencher("senhaExclusao", "errada");
                confirmarExclusao();
                clicar("excluir", "403");
                conferir(!habilitado("host"), "Exclusao com senha errada deve manter sessao");
                clicar("logout", "200");
                conferir(habilitado("host"), "Logout deve liberar servidor");
                preencher("senha", "nova123");
                clicar("entrar", "200");
                preencher("senhaExclusao", "nova123");
                confirmarExclusao();
                clicar("excluir", "200");
                conferir(memoria.carregar().usuarios.isEmpty() && memoria.carregar().sessoes.isEmpty(), "Exclusao deve remover conta e sessao");
                imagem("build/gui-acesso.png");
                // Fechar a janela autenticada deve confirmar logout no servidor antes de descartar a sessao.
                preencher("nome", "outro");
                preencher("senha", "senha123");
                clicar("cadastrar", "201");
                preencher("senha", "senha123");
                clicar("entrar", "200");
                SwingUtilities.invokeAndWait(() -> tela.dispatchEvent(new WindowEvent(tela, WindowEvent.WINDOW_CLOSING)));
                aguardar();
                conferir(memoria.carregar().sessoes.isEmpty(), "Fechar janela deve executar logout");
                for (String op : new String[] {"register", "login", "read_user", "update_user", "delete_user", "logout"}) {
                    for (String log : new String[] {mensagensCliente.toString(StandardCharsets.UTF_8), mensagensServidor.toString(StandardCharsets.UTF_8)}) {
                        conferir(log.contains("C -> S {\"op\":\"" + op + "\""), "Requisicao ausente no log: " + op);
                        conferir(log.contains("S -> C {\"op\":\"" + op + "_response\""), "Resposta ausente no log: " + op);
                    }
                }
                Files.writeString(Path.of("build/gui-json-cliente.log"), mensagensCliente.toString(StandardCharsets.UTF_8));
                Files.writeString(Path.of("build/gui-json-servidor.log"), mensagensServidor.toString(StandardCharsets.UTF_8));
            } finally {
                SwingUtilities.invokeAndWait(() -> { if (tela != null) tela.dispose(); });
                servidor.close();
                thread.join(3000);
                System.setOut(terminal);
            }
        }
        terminal.println("GUI: " + verificacoes + " verificacoes aprovadas; seis operacoes e JSON nos dois terminais.");
    }

    private static Component componente(Container pai, String nome) {
        for (Component filho : pai.getComponents()) {
            if (nome.equals(filho.getName())) return filho;
            if (filho instanceof Container container) {
                Component encontrado = componente(container, nome);
                if (encontrado != null) return encontrado;
            }
        }
        return null;
    }
    private static void preencher(String nome, String valor) throws Exception {
        SwingUtilities.invokeAndWait(() -> ((JTextComponent) componente(tela, nome)).setText(valor));
    }
    private static String texto(String nome) throws Exception {
        String[] resultado = new String[1];
        SwingUtilities.invokeAndWait(() -> {
            Component c = componente(tela, nome);
            resultado[0] = c instanceof JLabel label ? label.getText() : ((JTextComponent) c).getText();
        });
        return resultado[0];
    }
    private static boolean habilitado(String nome) throws Exception {
        boolean[] resultado = new boolean[1];
        SwingUtilities.invokeAndWait(() -> resultado[0] = componente(tela, nome).isEnabled());
        return resultado[0];
    }
    private static void clicar(String nome, String status) throws Exception {
        SwingUtilities.invokeAndWait(() -> ((JButton) componente(tela, nome)).doClick());
        aguardar();
        conferir(texto("status").startsWith(status), "Resposta inesperada de " + nome + ": " + texto("status"));
    }
    private static void aguardar() throws Exception {
        long limite = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while (!habilitado("entrar")) {
            if (System.nanoTime() > limite) throw new AssertionError("GUI nao concluiu a operacao");
            Thread.sleep(20);
        }
    }
    private static void confirmarExclusao() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Timer timer = new Timer(100, null);
            timer.addActionListener(e -> {
                for (Window janela : Window.getWindows()) {
                    if (janela instanceof JDialog dialogo && dialogo.isVisible()) {
                        JOptionPane painel = opcao(dialogo);
                        if (painel != null) { painel.setValue(JOptionPane.YES_OPTION); timer.stop(); }
                    }
                }
            });
            timer.start();
        });
    }
    private static JOptionPane opcao(Container pai) {
        for (Component filho : pai.getComponents()) {
            if (filho instanceof JOptionPane painel) return painel;
            if (filho instanceof Container container) {
                JOptionPane painel = opcao(container);
                if (painel != null) return painel;
            }
        }
        return null;
    }
    private static void imagem(String caminho) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            tela.validate();
            BufferedImage imagem = new BufferedImage(tela.getWidth(), tela.getHeight(), BufferedImage.TYPE_INT_RGB);
            var graphics = imagem.createGraphics();
            try {
                tela.getContentPane().printAll(graphics);
                ImageIO.write(imagem, "png", Path.of(caminho).toFile());
            } catch (java.io.IOException e) { throw new IllegalStateException(e); }
            finally { graphics.dispose(); }
        });
    }
    private static void conferir(boolean condicao, String mensagem) {
        verificacoes++;
        if (!condicao) throw new AssertionError(mensagem);
    }
}
