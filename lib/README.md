# Dependências locais

`gson-2.14.0.jar` foi copiado do ZIP do exemplo Java TCP fornecido, sem download.

Coordenadas Maven, caso o projeto seja convertido depois: `com.google.code.gson:gson:2.14.0`.

O código-fonte da biblioteca está em `../../gson-main.zip`. A licença Apache 2.0 está em `GSON-LICENSE.txt`.

`mysql-connector-j-9.6.0.jar` é o driver JDBC oficial do MySQL, baixado de [Maven Central](https://repo.maven.apache.org/maven2/com/mysql/mysql-connector-j/9.6.0/), com o checksum publicado conferido. Coordenadas: `com.mysql:mysql-connector-j:9.6.0`. Sua licença está em `MYSQL-CONNECTOR-J-LICENSE.txt`.

Este projeto usa a API JDBC; não usa X DevAPI e não precisa de Protocol Buffers. Referência: [documentação oficial do Connector/J](https://dev.mysql.com/doc/connector-j/en/connector-j-installing-maven.html).
