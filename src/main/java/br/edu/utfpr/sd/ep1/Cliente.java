package br.edu.utfpr.sd.ep1;

import com.google.gson.JsonObject;
import java.io.EOFException;
import java.io.IOException;
import java.util.Arrays;

/** Inicia a GUI Swing por padrao; --console preserva o cliente de terminal. */
public final class Cliente {
    private final EntradaConsole console = new EntradaConsole();
    private String token;

    public static void main(String[] args) {
        if (args.length == 0 || !args[0].equals("--console")) {
            ClienteGui.main(args);
            return;
        }
        args = Arrays.copyOfRange(args, 1, args.length);
        if (args.length != 0 && args.length != 2) {
            System.err.println("Uso: cliente.sh [--console] [ip porta]");
            System.exit(1);
        }
        try { new Cliente().executar(args); }
        catch (IOException | IllegalArgumentException e) {
            System.err.println("Cliente encerrado: " + e.getMessage());
            System.exit(1);
        }
    }

    private void executar(String[] args) throws IOException {
        String host = args.length == 2 ? args[0] : console.perguntar("IP do servidor [127.0.0.1]: ").trim();
        if (host.isEmpty()) host = "127.0.0.1";
        int porta = args.length == 2 ? EntradaConsole.validarPorta(args[1]) : console.porta("Porta do servidor: ");
        try (ClienteTcp cliente = new ClienteTcp(host, porta, System.out)) {
            while (true) {
                try {
                    if (token == null) {
                        System.out.println("\n1 - Cadastrar | 2 - Login | 0 - Sair");
                    } else {
                        System.out.println("\n1 - Meu cadastro | 2 - Atualizar | 3 - Apagar conta | 4 - Logout | 0 - Sair");
                    }
                    String opcao = console.perguntar("Opcao: ").trim();
                    JsonObject req;
                    if (token == null) {
                        switch (opcao) {
                            case "0": return;
                            case "1": req = cadastro(); break;
                            case "2": req = login(); break;
                            default: System.out.println("Opcao invalida"); continue;
                        }
                    } else {
                        switch (opcao) {
                            case "1": req = requisicao("read_user"); break;
                            case "2": req = atualizar(); break;
                            case "3":
                                if (!console.perguntar("Apagar a conta? Digite SIM: ").equals("SIM")) continue;
                                req = requisicao("delete_user");
                                req.addProperty("password", console.perguntar("Senha de confirmacao: "));
                                break;
                            case "4": case "0": req = requisicao("logout"); break;
                            default: System.out.println("Opcao invalida"); continue;
                        }
                    }
                    try {
                        JsonObject resposta = cliente.enviar(req);
                        tratar(req, resposta);
                        if (opcao.equals("0") && token == null) return;
                    } catch (IOException e) {
                        System.out.println("Nao foi possivel concluir: " + e.getMessage());
                        // Token permanece em memoria ate uma resposta confirmar logout ou invalidacao.
                    }
                } catch (EOFException e) {
                    if (token != null) {
                        try { tratar(requisicao("logout"), cliente.enviar(requisicao("logout"))); }
                        catch (IOException falha) { System.out.println("Logout sem resposta: a sessao expirara por inatividade."); }
                    }
                    return;
                }
            }
        }
    }

    private JsonObject requisicao(String op) {
        JsonObject req = new JsonObject();
        req.addProperty("op", op);
        if (token != null) req.addProperty("token", token);
        return req;
    }

    private JsonObject cadastro() throws IOException {
        JsonObject req = requisicao("register");
        req.addProperty("email", console.perguntar("Email (minusculo): "));
        req.addProperty("user", console.perguntar("Usuario (letras minusculas): "));
        req.addProperty("password", console.perguntar("Senha (letras e numeros): "));
        return req;
    }

    private JsonObject login() throws IOException {
        JsonObject req = requisicao("login");
        req.addProperty("email", console.perguntar("Email: "));
        req.addProperty("password", console.perguntar("Senha: "));
        return req;
    }

    private JsonObject atualizar() throws IOException {
        JsonObject req = requisicao("update_user");
        req.addProperty("user", console.perguntar("Novo usuario (Enter para manter): "));
        req.addProperty("password", console.perguntar("Nova senha (Enter para manter): "));
        return req;
    }

    private void tratar(JsonObject req, JsonObject resposta) throws IOException {
        String op = Protocolo.string(req, "op");
        String status = Protocolo.string(resposta, "status");
        if (op.equals("login") && status.equals("200")) {
            String novo = Protocolo.string(resposta, "token");
            if (novo == null || !novo.matches("^[a-f0-9]{64}$")) throw new IOException("Token de login invalido");
            token = novo;
        }
        if (status.equals("401") || (status.equals("200") && (op.equals("logout") || op.equals("delete_user")))) {
            token = null;
        }
        System.out.println(status + " - " + Protocolo.string(resposta, "message"));
        if (op.equals("read_user") && status.equals("200")) {
            System.out.println("Usuario: " + Protocolo.string(resposta, "user")
                    + " | Email: " + Protocolo.string(resposta, "email")
                    + " | Perfil: " + Protocolo.string(resposta, "role")
                    + " | Criado em: " + Protocolo.string(resposta, "created_at"));
        }
    }
}
