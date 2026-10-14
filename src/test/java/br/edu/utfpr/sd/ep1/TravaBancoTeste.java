package br.edu.utfpr.sd.ep1;

import java.sql.Connection;
import java.sql.SQLException;

/** Impede que duas baterias limpem o mesmo banco simultaneamente. */
final class TravaBancoTeste implements AutoCloseable {
    private final Connection conexao;

    TravaBancoTeste(ConfiguracaoMySql config) throws SQLException {
        conexao = config.abrir();
        try {
            conferirBanco();
            try (var stmt = conexao.prepareStatement("SELECT GET_LOCK('ep1_bateria_testes', 0)"); var rs = stmt.executeQuery()) {
                if (!rs.next() || rs.getInt(1) != 1) throw new SQLException("Outra bateria ja esta usando o banco de testes");
            }
        } catch (SQLException | RuntimeException e) {
            conexao.close();
            throw e;
        }
    }

    void conferirBanco() throws SQLException {
        if (!"ep1_reserva_salas_teste".equals(conexao.getCatalog())) throw new SQLException("Banco de testes incorreto");
    }

    @Override public void close() throws SQLException { conexao.close(); }
}
