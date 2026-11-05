package br.edu.utfpr.sd.ep1;

import java.io.IOException;
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
        config = ConfiguracaoMySql.carregar(arquivo);
        trava = config.abrir();
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
