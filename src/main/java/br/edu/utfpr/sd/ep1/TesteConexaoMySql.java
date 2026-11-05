package br.edu.utfpr.sd.ep1;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/** Diagnostico JDBC independente: nao inicia o servidor nem altera dados. */
public final class TesteConexaoMySql {
    private TesteConexaoMySql() { }

    public static void main(String[] args) {
        if (args.length > 2 || (args.length == 2 && !args[1].equals("--verificar-ep2"))) {
            System.err.println("Uso: TesteConexaoMySql [config/mysql.properties] [--verificar-ep2]");
            System.exit(1);
        }
        Path arquivo = Path.of(args.length >= 1 ? args[0] : "config/mysql.properties");
        try {
            conferir(ConfiguracaoMySql.carregar(arquivo), args.length == 2);
        } catch (IOException e) {
            System.err.println("Configuracao invalida: " + e.getMessage());
            System.exit(1);
        } catch (SQLException e) {
            System.err.println("Falha JDBC: " + (e instanceof VerificacaoBancoMySql.FalhaPreparacao
                    ? e.getMessage() : VerificacaoBancoMySql.explicar(e)));
            System.exit(1);
        }
    }

    private static void conferir(ConfiguracaoMySql config, boolean verificarEp2) throws SQLException {
        Connection conexao = config.abrir();
        try (conexao) {
            if (conexao.isClosed() || !conexao.isValid(3)) {
                throw new SQLException("A conexao nao esta valida");
            }
            if (verificarEp2) VerificacaoBancoMySql.conferir(conexao);
            System.out.println("Conexao aberta e valida.");
            try (Statement consulta = conexao.createStatement();
                    ResultSet resultado = consulta.executeQuery("SELECT VERSION() AS versao, DATABASE() AS banco")) {
                if (!resultado.next() || resultado.getString("banco") == null) {
                    throw new SQLException("Nenhum banco selecionado");
                }
                System.out.println("Versao: " + resultado.getString("versao"));
                System.out.println("Banco selecionado: " + resultado.getString("banco"));
            }
        }
        if (!conexao.isClosed()) throw new SQLException("A conexao nao foi fechada");
        System.out.println("Conexao fechada corretamente.");
    }
}
