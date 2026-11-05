package br.edu.utfpr.sd.ep1;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.awt.BorderLayout;
import java.awt.GridLayout;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;

/** Formularios e resultados da EP-2. A rede e executada pelo worker de ClienteGui. */
final class PainelOperacoes extends JPanel {
    private static final long serialVersionUID = 1L;
    private final transient List<JComponent> controles = new ArrayList<>();
    private final JTable tabela = new JTable();
    private final JTextArea detalhes = new JTextArea(5, 40);
    private final transient List<String> operacoes;

    PainelOperacoes(String grupo, Consumer<JsonObject> enviar) {
        super(new BorderLayout(8, 8));
        operacoes = OperacoesCliente.grupo(grupo);
        JPanel acoes = new JPanel(new GridLayout(0, 3, 6, 6));
        for (String op : operacoes) {
            JButton botao = new JButton(OperacoesCliente.TITULOS.get(op)); botao.setName(op);
            botao.addActionListener(e -> {
                JsonObject req = formulario(op);
                if (req != null) enviar.accept(req);
            });
            controles.add(botao); acoes.add(botao);
        }
        add(acoes, BorderLayout.NORTH);
        tabela.setName(grupo + "Tabela"); tabela.setAutoCreateRowSorter(true);
        add(new JScrollPane(tabela), BorderLayout.CENTER);
        detalhes.setEditable(false); detalhes.setLineWrap(true); detalhes.setWrapStyleWord(true);
        detalhes.setName(grupo + "Detalhes");
        add(new JScrollPane(detalhes), BorderLayout.SOUTH);
    }

    List<JComponent> controles() { return controles; }

    private JsonObject formulario(String op) {
        JsonObject req = new JsonObject(); req.addProperty("op", op);
        if (OperacoesCliente.campos(op).isEmpty()) return req;
        JPanel campos = new JPanel();
        campos.setLayout(new BoxLayout(campos, BoxLayout.Y_AXIS));
        Map<String, JTextField> valores = new LinkedHashMap<>();
        List<JCheckBox> horarios = new ArrayList<>();
        for (String campo : OperacoesCliente.campos(op)) {
            if (campo.equals("slots")) {
                campos.add(new JLabel("Funcionamento: marque os horários. Na atualização, vazio mantém a grade."));
                JPanel grade = new JPanel(new GridLayout(0, 7, 2, 2));
                grade.add(new JLabel("Aula"));
                for (String dia : new String[] {"Seg", "Ter", "Qua", "Qui", "Sex", "Sáb"}) grade.add(new JLabel(dia));
                var aulas = Contrato.DOCUMENTO.getAsJsonObject("schemas").getAsJsonObject("slot").getAsJsonObject("x-horarios");
                for (var aula : aulas.entrySet()) {
                    grade.add(new JLabel(aula.getKey() + " " + aula.getValue().getAsJsonArray().get(0).getAsString()));
                    for (int dia = 2; dia <= 7; dia++) {
                        JCheckBox opcao = new JCheckBox(); opcao.setName(dia + aula.getKey());
                        opcao.setToolTipText(dia + aula.getKey()); horarios.add(opcao); grade.add(opcao);
                    }
                }
                campos.add(grade);
            } else {
                campos.add(new JLabel(OperacoesCliente.rotulo(campo)
                        + (OperacoesCliente.obrigatorio(op, campo) ? "" : " (opcional)")));
                JTextField entrada = campo.equals("password") ? new JPasswordField(28) : new JTextField(28);
                entrada.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
                entrada.setName(campo); valores.put(campo, entrada); campos.add(entrada);
            }
        }
        JScrollPane rolagem = new JScrollPane(campos);
        rolagem.setPreferredSize(new Dimension(op.contains("room") ? 720 : 500, 500));
        if (JOptionPane.showConfirmDialog(this, rolagem, OperacoesCliente.TITULOS.get(op),
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return null;
        valores.forEach((campo, entrada) -> OperacoesCliente.preencher(req, campo,
                entrada instanceof JPasswordField senha ? new String(senha.getPassword()) : entrada.getText()));
        if (OperacoesCliente.campos(op).contains("slots")) {
            JsonArray slots = new JsonArray(); horarios.stream().filter(JCheckBox::isSelected).forEach(c -> slots.add(c.getName()));
            req.add("slots", slots);
        }
        if ((op.startsWith("delete_") || op.equals("admin_delete_user")) && JOptionPane.showConfirmDialog(this,
                "Confirmar a exclusão?", "Confirmação", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return null;
        return req;
    }

    void mostrar(JsonObject req, JsonObject res) {
        if (!operacoes.contains(Protocolo.string(req, "op"))) return;
        detalhes.setText(Protocolo.string(res, "status") + " — " + Protocolo.string(res, "message"));
        if (!"200".equals(Protocolo.string(res, "status")) && !"201".equals(Protocolo.string(res, "status"))) return;
        JsonArray linhas = null;
        for (String lista : new String[] {"users", "rooms", "reservations", "agenda"}) if (res.has(lista)) linhas = res.getAsJsonArray(lista);
        StringBuilder texto = new StringBuilder(detalhes.getText());
        for (var campo : res.entrySet()) if (!List.of("op", "status", "message").contains(campo.getKey()) && !campo.getValue().isJsonArray())
            texto.append("\n").append(OperacoesCliente.rotulo(campo.getKey())).append(": ").append(campo.getValue().getAsString());
        detalhes.setText(texto.toString());
        if (linhas == null) { linhas = new JsonArray(); JsonObject dado = res.deepCopy();
            dado.remove("op"); dado.remove("status"); dado.remove("message"); linhas.add(dado); }
        var colunas = new java.util.LinkedHashSet<String>();
        linhas.forEach(l -> colunas.addAll(l.getAsJsonObject().keySet()));
        List<String> chaves = new ArrayList<>(colunas);
        DefaultTableModel modelo = new DefaultTableModel(chaves.stream().map(OperacoesCliente::rotulo).toArray(), 0) {
            private static final long serialVersionUID = 1L;
            @Override public boolean isCellEditable(int linha, int coluna) { return false; }
        };
        for (var linha : linhas) modelo.addRow(chaves.stream().map(c -> {
            var valor = linha.getAsJsonObject().get(c);
            if (valor == null) return "";
            if (valor.isJsonArray()) return String.join(", ", SalaService.lista(valor.getAsJsonArray()));
            return valor.getAsString();
        }).toArray());
        tabela.setModel(modelo);
    }
}
