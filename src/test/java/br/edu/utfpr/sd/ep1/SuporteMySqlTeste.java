package br.edu.utfpr.sd.ep1;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;

/** Banco separado e lock mantido ate o fim da bateria; nunca limpa o banco da aplicacao. */
final class SuporteMySqlTeste implements AutoCloseable {
    final Path arquivo;
    final ConfiguracaoMySql config;
    final RepositorioMySql repositorio;
    private final Connection trava;

    SuporteMySqlTeste(String[] args) throws IOException, SQLException {
        arquivo = Path.of(args.length == 0 ? System.getProperty("ep2.mysql.config", "config/mysql-test.properties") : args[0]);
        if (!Files.isRegularFile(arquivo))
            throw new IOException("Configuracao de testes ausente: " + arquivo
                    + ". Copie config/mysql-test.properties.example para o arquivo local e solicite a preparacao"
                    + " separada de ep1_reserva_salas_teste a quem ja tem permissao no banco."
                    + " Nenhum banco, usuario ou permissao sera criado automaticamente.");
        config = ConfiguracaoMySql.carregar(arquivo);
        try { trava = config.abrir(); }
        catch (SQLException e) {
            if (e.getErrorCode() == 1049)
                throw new SQLException("Banco de testes ausente. Solicite a preparacao separada de ep1_reserva_salas_teste"
                        + " conforme README; testes nao fazem provisionamento automatico.", e.getSQLState(), e.getErrorCode());
            throw e;
        }
        try {
            if (!"ep1_reserva_salas_teste".equals(trava.getCatalog()))
                throw new SQLException("Testes exigem ep1_reserva_salas_teste; dados da aplicacao protegidos");
            try (var stmt = trava.prepareStatement("SELECT GET_LOCK('ep2_bateria_mysql',0)"); var rs = stmt.executeQuery()) {
                if (!rs.next() || rs.getInt(1) != 1) throw new SQLException("Outra bateria MySQL esta em execucao");
            }
            repositorio = new RepositorioMySql(config);
        } catch (SQLException | RuntimeException e) { trava.close(); throw e; }
    }

    void limpar() throws IOException {
        repositorio.transacionar(b -> {
            b.reservas.clear(); b.sessoes.clear(); b.salas.clear(); b.usuarios.clear();
            b.proximaSalaId = 1; b.proximaReservaId = 1;
            return new com.google.gson.JsonObject();
        });
    }

    @Override public void close() throws SQLException { trava.close(); }
}
