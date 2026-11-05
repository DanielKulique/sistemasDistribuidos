package br.edu.utfpr.sd.ep1;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** Preflight de infraestrutura. Nao cria schema, usuarios, admin nem dados de demonstracao. */
final class VerificacaoBancoMySql {
    private record Tabela(String nome, String colunas) { }
    private static final List<Tabela> TABELAS = List.of(
            new Tabela("controle_transacoes", "id,proxima_sala,proxima_reserva"),
            new Tabela("usuarios", "id,nome,email,senha_hash,senha_salt,perfil,criado_em"),
            new Tabela("sessoes", "token,usuario_id,ultimo_uso"),
            new Tabela("salas", "id,nome,capacidade,localizacao,situacao"),
            new Tabela("sala_recursos", "sala_id,ordem,recurso"),
            new Tabela("sala_slots", "sala_id,slot"),
            new Tabela("reservas", "id,sala_id,usuario_id,data,inicio,fim,assunto,participantes,criado_em"));

    private VerificacaoBancoMySql() { }

    static void conferir(Connection conexao) throws SQLException {
        List<String> falhas = new ArrayList<>();
        boolean autoCommit = conexao.getAutoCommit();
        conexao.setAutoCommit(false);
        try {
            for (Tabela tabela : TABELAS) {
                boolean leitura = consultar(conexao, tabela, falhas);
                if (!leitura) continue;
                // WHERE 1=0 e usado em TODOS os comandos: nenhuma linha e inserida,
                // atualizada ou excluida, mesmo que o banco principal ja tenha dados.
                // O servidor ainda verifica as permissoes reais da conta conectada.
                String destino = "`" + tabela.nome + "`";
                conferirDml(conexao, tabela, "INSERT", "INSERT INTO " + destino + " (" + tabela.colunas
                        + ") SELECT " + tabela.colunas + " FROM " + destino + " WHERE 1=0", falhas);
                String alteracoes = String.join(",", java.util.Arrays.stream(tabela.colunas.split(","))
                        .map(c -> "`" + c + "`=`" + c + "`").toList());
                conferirDml(conexao, tabela, "UPDATE", "UPDATE " + destino + " SET " + alteracoes + " WHERE 1=0", falhas);
                conferirDml(conexao, tabela, "DELETE", "DELETE FROM " + destino + " WHERE 1=0", falhas);
            }
            if (falhas.isEmpty()) {
                try (var comando = conexao.createStatement();
                        var resultado = comando.executeQuery("SELECT proxima_sala,proxima_reserva FROM controle_transacoes WHERE id=1")) {
                    if (!resultado.next() || resultado.getLong(1) < 1 || resultado.getLong(2) < 1)
                        falhas.add("controle_transacoes: linha id=1/sequencias ausentes ou invalidas; confira sql/schema.sql");
                }
            }
        } finally {
            // Nenhum commit, inclusive no caminho de sucesso.
            conexao.rollback();
            conexao.setAutoCommit(autoCommit);
        }
        if (!falhas.isEmpty()) throw new FalhaPreparacao(String.join(System.lineSeparator(), falhas));
        System.out.println("Tabelas EP-2: controle_transacoes, usuarios, sessoes, salas, sala_recursos, sala_slots, reservas.");
        System.out.println("Permissoes SELECT, INSERT, UPDATE, DELETE verificadas; zero registros alterados; rollback concluido.");
    }

    private static boolean consultar(Connection conexao, Tabela tabela, List<String> falhas) {
        try (var comando = conexao.createStatement();
                var resultado = comando.executeQuery("SELECT " + tabela.colunas + " FROM `" + tabela.nome + "` WHERE 1=0")) {
            resultado.getMetaData(); // Verifica todas as colunas sem ler conteudo dos registros.
            try (var consulta = conexao.prepareStatement("SELECT ENGINE FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=?")) {
                consulta.setString(1, tabela.nome);
                try (var motor = consulta.executeQuery()) {
                    if (!motor.next() || !"InnoDB".equalsIgnoreCase(motor.getString(1))) {
                        falhas.add(tabela.nome + ": deve ser uma tabela InnoDB, conforme sql/schema.sql");
                        return false;
                    }
                }
            }
            return true;
        } catch (SQLException e) {
            falhas.add(tabela.nome + " / SELECT: " + explicar(e));
            return false;
        }
    }

    private static void conferirDml(Connection conexao, Tabela tabela, String permissao, String sql, List<String> falhas) {
        try (var comando = conexao.createStatement()) {
            if (comando.executeUpdate(sql) != 0) throw new FalhaPreparacao("Verificacao deve afetar zero linhas");
        } catch (SQLException e) { falhas.add(tabela.nome + " / " + permissao + ": " + explicar(e)); }
    }

    /** Nao retorna mensagens do driver: elas podem conter valores da configuracao. */
    static String explicar(SQLException erro) {
        String motivo = switch (erro.getErrorCode()) {
            case 1045, 1698 -> "autenticacao recusada; confira user/password no arquivo local";
            case 1044 -> "conta sem acesso ao banco selecionado";
            case 1049 -> "banco configurado nao existe; solicite a preparacao inicial de sql/schema.sql";
            case 1146 -> "tabela ausente; solicite a aplicacao de sql/schema.sql";
            case 1054 -> "coluna ausente ou schema desatualizado; compare com sql/schema.sql";
            case 1142, 1143, 1227 -> "permissao insuficiente; solicite SELECT, INSERT, UPDATE, DELETE nas tabelas da aplicacao";
            case 1290, 1792 -> "banco/conexao em modo somente leitura";
            default -> erro.getSQLState() != null && erro.getSQLState().startsWith("08")
                    ? "servidor inacessivel ou falha de comunicacao; confira host/port"
                    : "falha na verificacao; informe SQLState/codigo a equipe responsavel pelo banco";
        };
        return motivo + " (SQLState " + erro.getSQLState() + ", codigo " + erro.getErrorCode() + ")";
    }

    static final class FalhaPreparacao extends SQLException {
        private static final long serialVersionUID = 1L;
        FalhaPreparacao(String mensagem) { super(mensagem); }
    }
}
