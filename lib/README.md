# Dependências locais

O projeto utiliza `gson-2.14.0.jar`, mantido da versão original, para serialização JSON. Sua licença Apache 2.0 está em `GSON-LICENSE.txt`. Coordenadas Maven: `com.google.code.gson:gson:2.14.0`.

`mariadb-java-client-3.5.11.jar` é o driver JDBC para MySQL e MariaDB (inclusive XAMPP). Coordenadas Maven: `org.mariadb.jdbc:mariadb-java-client:3.5.11`.

- Origem: https://repo.maven.apache.org/maven2/org/mariadb/jdbc/mariadb-java-client/3.5.11/mariadb-java-client-3.5.11.jar
- SHA-256 conferido com o publicado: `42604178b9e57ec08ee0035079961516f086e455d0141f0b22c6eff29fb2fff4`.
- Licença LGPL 2.1 ou posterior em `MARIADB-CONNECTOR-J-LICENSE.txt`, copiada de https://raw.githubusercontent.com/mariadb-corporation/mariadb-connector-j/3.5.11/LICENSE.
- Código-fonte correspondente: https://github.com/mariadb-corporation/mariadb-connector-j/tree/3.5.11.
- A URL `jdbc:mariadb://host:port/database` atende ambos os bancos. O driver é descoberto automaticamente por `DriverManager`.

Swing, sockets, leitura/gravação de arquivos e travas usam o Java padrão. Não há downloads durante a compilação. A execução normal persiste em MySQL/MariaDB via JDBC. RepositorioArquivo permanece para testes isolados e compatibilidade.
