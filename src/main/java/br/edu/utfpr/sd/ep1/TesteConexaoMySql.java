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
        if (args.length > 1) {
            System.err.println("Uso: TesteConexaoMySql [config/mysql.properties]");
            System.exit(1);
        }
        Path arquivo = Path.of(args.length == 1 ? args[0] : "config/mysql.properties");
        try {
            conferir(ConfiguracaoMySql.carregar(arquivo));
        } catch (IOException e) {
            System.err.println("Configuracao invalida: " + e.getMessage());
            System.exit(1);
        } catch (SQLException e) {
            System.err.println("Falha JDBC (SQLState " + e.getSQLState() + "): " + e.getMessage());
            System.exit(1);
        }
    }

    private static void conferir(ConfiguracaoMySql config) throws SQLException {
        Connection conexao = config.abrir();
        try (conexao) {
            if (conexao.isClosed() || !conexao.isValid(3)) {
                throw new SQLException("A conexao nao esta valida");
            }
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
