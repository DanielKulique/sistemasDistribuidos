package br.edu.utfpr.sd.ep1;

import com.google.gson.JsonObject;
import java.util.Map;
import java.util.Set;

/** Validacao das respostas das seis operacoes da EP-1, contrato v2.4.0. */
final class ValidacaoResposta {
    private static final String INTERNO = "500|Erro interno do servidor";
    private static final String TOKEN = "401|Token invalido ou expirado";
    private static final Map<String, Set<String>> CATALOGO = Map.of(
            "register", Set.of("201|Usuario cadastrado com sucesso", "400|Dados de cadastro em formato invalido",
                    "409|Usuario ou email ja cadastrado", INTERNO),
            "login", Set.of("200|Login realizado com sucesso", "400|Email ou senha em formato invalido",
                    "401|Email ou senha incorretos", "409|Usuario ja possui sessao ativa", INTERNO),
            "logout", Set.of("200|Logout realizado com sucesso", "400|Token em formato invalido", TOKEN, INTERNO),
            "read_user", Set.of("200|Consulta realizada com sucesso", "400|Token em formato invalido", TOKEN, INTERNO),
            "update_user", Set.of("200|Dados atualizados com sucesso", "400|Dados em formato invalido",
                    "400|Nenhum dado para atualizar", "409|Usuario ja esta em uso", TOKEN, INTERNO),
            "delete_user", Set.of("200|Usuario removido com sucesso", "400|Senha em formato invalido",
                    "403|Senha incorreta", "403|Nao e possivel remover o ultimo administrador", TOKEN, INTERNO));
    private static final Set<String> ERROS = Set.of("Requisicao invalida", "Operacao desconhecida", "Mensagem excede o tamanho maximo");

    private ValidacaoResposta() { }

    static void conferir(JsonObject req, JsonObject res) throws ErroProtocoloException {
        String op = campo(res, "op");
        String status = campo(res, "status");
        String message = campo(res, "message");
        if (res.has("password")) throw new ErroProtocoloException("Servidor devolveu senha na resposta");
        if (op.equals("error")) {
            if (!status.equals("400") || !ERROS.contains(message)) throw new ErroProtocoloException("Resposta error fora do contrato");
            return;
        }
        String operacao = Protocolo.string(req, "op");
        if (operacao == null || !CATALOGO.containsKey(operacao)) throw new ErroProtocoloException("Operacao da resposta nao prevista");
        if (!(operacao + "_response").equals(op)) throw new ErroProtocoloException("Resposta de outra operacao");
        Set<String> permitidas = CATALOGO.get(operacao);
        if (permitidas == null || !permitidas.contains(status + "|" + message)) {
            throw new ErroProtocoloException("Status/message fora do contrato da operacao");
        }
        if (!status.equals("200")) return;
        if (operacao.equals("login")) {
            padrao(res, "token", "^[a-f0-9]{64}$");
            perfil(res);
        } else if (operacao.equals("read_user")) {
            padrao(res, "user", "^[a-z]{1,30}$");
            padrao(res, "email", "^[a-z0-9._-]+@[a-z0-9-]+(\\.[a-z0-9-]+)*\\.[a-z]{2,}$");
            perfil(res);
            padrao(res, "created_at", "^[0-9]{4}-[0-9]{2}-[0-9]{2} ([01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9]$");
        }
    }

    private static String campo(JsonObject res, String nome) throws ErroProtocoloException {
        String valor = Protocolo.string(res, nome);
        if (valor == null) throw new ErroProtocoloException("Resposta sem campo string obrigatorio: " + nome);
        return valor;
    }

    private static void padrao(JsonObject res, String nome, String regex) throws ErroProtocoloException {
        if (!campo(res, nome).matches(regex)) throw new ErroProtocoloException("Campo invalido na resposta: " + nome);
    }

    private static void perfil(JsonObject res) throws ErroProtocoloException {
        if (!Set.of("user", "admin").contains(campo(res, "role"))) throw new ErroProtocoloException("Perfil invalido na resposta");
    }
}
