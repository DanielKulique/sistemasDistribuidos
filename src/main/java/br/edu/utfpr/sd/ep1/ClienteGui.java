package br.edu.utfpr.sd.ep1;

import com.google.gson.JsonObject;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import javax.swing.*;
import javax.swing.border.EmptyBorder;

/** Interface Swing; somente o worker acessa a rede, e somente a EDT altera a tela. */
public final class ClienteGui extends JFrame {
    private static final long serialVersionUID = 1L;
    private final JTextField host = campo("host", "127.0.0.1");
    private final JTextField porta = campo("porta", "5000");
    private final JTextField email = campo("email", "");
    private final JTextField nome = campo("nome", "");
    private final JPasswordField senha = segredo("senha");
    private final JTextField novoNome = campo("novoNome", "");
    private final JPasswordField novaSenha = segredo("novaSenha");
    private final JPasswordField senhaExclusao = segredo("senhaExclusao");
    private final JTextArea cadastro = new JTextArea(6, 35);
    private final JLabel status = new JLabel("Informe o servidor e faça login ou cadastre uma conta.");
    private final JLabel sessao = new JLabel("Sem sessão");
    private final CardLayout cards = new CardLayout();
    private final JPanel paginas = new JPanel(cards);
    private final transient List<JComponent> controles = new ArrayList<>();
    private transient ClienteTcp cliente;
    private String destino;
    private String token;
    private boolean ocupado;

    public ClienteGui(String[] args) {
        super("Reserva de salas · EP-1");
        if (args.length == 2) {
            host.setText(args[0]);
            porta.setText(String.valueOf(EntradaConsole.validarPorta(args[1])));
        }
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        JPanel raiz = new JPanel(new BorderLayout(16, 16));
        raiz.setBorder(new EmptyBorder(20, 24, 20, 24));
        JPanel topo = new JPanel(new BorderLayout(8, 12));
        JLabel titulo = new JLabel("Minha conta");
        titulo.setFont(titulo.getFont().deriveFont(24f));
        topo.add(titulo, BorderLayout.NORTH);
        JPanel conexao = new JPanel(new BorderLayout(12, 0));
        conexao.setBorder(BorderFactory.createTitledBorder("Servidor TCP"));
        conexao.add(formulario("Endereço", host, "Porta", porta), BorderLayout.CENTER);
        conexao.add(sessao, BorderLayout.EAST);
        topo.add(conexao, BorderLayout.CENTER);
        raiz.add(topo, BorderLayout.NORTH);

        JPanel acesso = new JPanel(new BorderLayout(8, 16));
        acesso.add(new JLabel("Use e-mail e senha para entrar. Para cadastrar, preencha também o usuário."), BorderLayout.NORTH);
        JPanel formularioAcesso = new JPanel(new BorderLayout(8, 12));
        formularioAcesso.add(formulario("E-mail", email, "Usuário (cadastro)", nome, "Senha", senha), BorderLayout.NORTH);
        JPanel acoes = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        acoes.add(botao("Cadastrar", "cadastrar", () -> enviar(credenciais("register"), false)));
        acoes.add(botao("Entrar", "entrar", () -> enviar(credenciais("login"), false)));
        formularioAcesso.add(acoes, BorderLayout.CENTER);
        acesso.add(formularioAcesso, BorderLayout.CENTER);
        paginas.add(acesso, "acesso");

        JPanel conta = new JPanel(new BorderLayout(8, 12));
        cadastro.setName("cadastro");
        cadastro.setEditable(false);
        cadastro.setLineWrap(true);
        cadastro.setWrapStyleWord(true);
        JScrollPane dados = new JScrollPane(cadastro);
        dados.setBorder(BorderFactory.createTitledBorder("Meu cadastro"));
        JPanel leitura = new JPanel(new BorderLayout());
        leitura.add(dados, BorderLayout.CENTER);
        leitura.add(botao("Consultar cadastro", "consultar", () -> enviar(requisicao("read_user"), false)), BorderLayout.SOUTH);
        conta.add(leitura, BorderLayout.NORTH);
        JPanel alteracoes = new JPanel(new GridLayout(2, 1, 0, 12));
        JPanel atualizar = new JPanel(new BorderLayout());
        atualizar.setBorder(BorderFactory.createTitledBorder("Atualizar · deixe vazio para manter"));
        atualizar.add(formulario("Novo usuário", novoNome, "Nova senha", novaSenha), BorderLayout.CENTER);
        atualizar.add(botao("Salvar alterações", "atualizar", () -> {
            JsonObject req = requisicao("update_user");
            req.addProperty("user", novoNome.getText());
            req.addProperty("password", texto(novaSenha));
            enviar(req, false);
        }), BorderLayout.SOUTH);
        alteracoes.add(atualizar);
        JPanel excluir = new JPanel(new BorderLayout());
        excluir.setBorder(BorderFactory.createTitledBorder("Excluir minha conta"));
        excluir.add(formulario("Senha atual", senhaExclusao), BorderLayout.CENTER);
        excluir.add(botao("Excluir conta…", "excluir", () -> {
            if (JOptionPane.showConfirmDialog(this, "Excluir definitivamente sua conta?", "Confirmar exclusão",
                    JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION) {
                JsonObject req = requisicao("delete_user");
                req.addProperty("password", texto(senhaExclusao));
                enviar(req, false);
            }
        }), BorderLayout.SOUTH);
        alteracoes.add(excluir);
        conta.add(alteracoes, BorderLayout.CENTER);
        JPanel sair = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        sair.add(botao("Sair da sessão", "logout", () -> enviar(requisicao("logout"), false)));
        conta.add(sair, BorderLayout.SOUTH);
        paginas.add(conta, "conta");
        raiz.add(paginas, BorderLayout.CENTER);
        status.setName("status");
        JPanel rodape = new JPanel(new BorderLayout(8, 8));
        rodape.add(status, BorderLayout.CENTER);
        rodape.add(new JLabel("As mensagens JSON enviadas e recebidas aparecem no terminal."), BorderLayout.SOUTH);
        raiz.add(rodape, BorderLayout.SOUTH);
        setContentPane(raiz);
        controles.addAll(List.of(email, nome, senha, novoNome, novaSenha, senhaExclusao));
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) {
                if (ocupado) { status.setText("Aguarde a operação terminar antes de fechar."); return; }
                if (token != null) enviar(requisicao("logout"), true);
                else encerrar();
            }
        });
        setMinimumSize(new Dimension(730, 750));
        pack();
        setLocationRelativeTo(null);
        atualizarControles();
    }

    private static JTextField campo(String id, String valor) {
        JTextField campo = new JTextField(valor, 24);
        campo.setName(id);
        return campo;
    }

    private static JPasswordField segredo(String id) {
        JPasswordField campo = new JPasswordField(24);
        campo.setName(id);
        return campo;
    }

    private static String texto(JPasswordField campo) {
        char[] valor = campo.getPassword();
        try { return new String(valor); }
        finally { Arrays.fill(valor, '\0'); campo.setText(""); }
    }

    private JPanel formulario(Object... itens) {
        JPanel painel = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.anchor = GridBagConstraints.NORTHWEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(4, 4, 4, 8);
        for (int i = 0; i < itens.length; i += 2) {
            JLabel label = new JLabel((String) itens[i]);
            label.setLabelFor((JComponent) itens[i + 1]);
            c.gridy = i / 2;
            c.gridx = 0;
            c.weightx = 0;
            painel.add(label, c);
            c.gridx = 1;
            c.weightx = 1;
            painel.add((JComponent) itens[i + 1], c);
        }
        c.gridy = itens.length / 2;
        c.weighty = 1;
        painel.add(Box.createVerticalGlue(), c);
        return painel;
    }

    private JButton botao(String titulo, String id, Runnable acao) {
        JButton botao = new JButton(titulo);
        botao.setName(id);
        botao.addActionListener(e -> acao.run());
        controles.add(botao);
        return botao;
    }

    private JsonObject requisicao(String op) {
        JsonObject req = new JsonObject();
        req.addProperty("op", op);
        if (token != null) req.addProperty("token", token);
        return req;
    }

    private JsonObject credenciais(String op) {
        JsonObject req = requisicao(op);
        req.addProperty("email", email.getText());
        req.addProperty("password", texto(senha));
        if (op.equals("register")) req.addProperty("user", nome.getText());
        return req;
    }

    private void enviar(JsonObject req, boolean fechar) {
        if (ocupado) return;
        final String endereco = host.getText().trim();
        final int numero;
        try {
            if (endereco.isEmpty()) throw new IllegalArgumentException("Informe o endereço do servidor.");
            numero = EntradaConsole.validarPorta(porta.getText().trim());
        } catch (IllegalArgumentException e) { status.setText(e.getMessage()); return; }
        ocupado = true;
        status.setText("Aguardando resposta do servidor…");
        atualizarControles();
        new SwingWorker<JsonObject, Void>() {
            @Override protected JsonObject doInBackground() throws IOException {
                String novoDestino = endereco + ":" + numero;
                if (cliente == null || !novoDestino.equals(destino)) {
                    if (cliente != null) cliente.close();
                    cliente = new ClienteTcp(endereco, numero, System.out);
                    destino = novoDestino;
                }
                return cliente.enviar(req);
            }
            @Override protected void done() {
                boolean encerrar = false;
                try {
                    JsonObject resposta = get();
                    tratar(req, resposta);
                    encerrar = fechar && token == null;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    status.setText("Operação interrompida.");
                } catch (ExecutionException e) {
                    status.setText("Falha de comunicação. Confira o terminal e tente novamente.");
                    System.out.println("[GUI] Falha: " + e.getCause().getMessage());
                    if (fechar && JOptionPane.showConfirmDialog(ClienteGui.this,
                            "Logout sem confirmação. A sessão poderá ficar ativa até expirar. Fechar mesmo assim?",
                            "Falha ao sair", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) encerrar = true;
                } finally {
                    ocupado = false;
                    atualizarControles();
                }
                if (encerrar) encerrar();
            }
        }.execute();
    }

    private void tratar(JsonObject req, JsonObject res) {
        String op = Protocolo.string(req, "op"), codigo = Protocolo.string(res, "status");
        status.setText(codigo + " — " + Protocolo.string(res, "message"));
        if (op.equals("login") && codigo.equals("200")) {
            token = Protocolo.string(res, "token");
            cadastro.setText("Sessão iniciada. Clique em Consultar cadastro para visualizar seus dados.");
        }
        if (codigo.equals("401") || (codigo.equals("200") && (op.equals("logout") || op.equals("delete_user")))) {
            token = null;
            cadastro.setText("");
            novoNome.setText("");
            novaSenha.setText("");
            senhaExclusao.setText("");
        }
        if (op.equals("read_user") && codigo.equals("200")) {
            cadastro.setText("Usuário: " + Protocolo.string(res, "user") + "\nE-mail: " + Protocolo.string(res, "email")
                    + "\nPerfil: " + Protocolo.string(res, "role") + "\nCriado em: " + Protocolo.string(res, "created_at"));
        }
        if (op.equals("update_user") && codigo.equals("200")) {
            novoNome.setText("");
            cadastro.setText("Dados atualizados. Consulte novamente para visualizar o cadastro atual.");
        }
    }

    private void atualizarControles() {
        for (JComponent controle : controles) controle.setEnabled(!ocupado);
        host.setEnabled(!ocupado && token == null);
        porta.setEnabled(!ocupado && token == null);
        sessao.setText(token == null ? "Sem sessão" : "Sessão ativa");
        cards.show(paginas, token == null ? "acesso" : "conta");
    }

    private void encerrar() {
        try { if (cliente != null) cliente.close(); }
        catch (IOException e) { System.out.println("[GUI] Falha ao fechar conexão: " + e.getMessage()); }
        dispose();
    }

    public static void main(String[] args) {
        if (args.length != 0 && args.length != 2) throw new IllegalArgumentException("Uso: cliente.sh [ip porta]");
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
                new ClienteGui(args).setVisible(true);
            } catch (Exception e) {
                System.err.println("Não foi possível abrir a interface: " + e.getMessage());
            }
        });
    }
}
