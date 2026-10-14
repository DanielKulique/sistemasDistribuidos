# EP-1 — Reserva de salas com MySQL

Cliente de console e servidor Java TCP com uma thread por conexão, Gson e MySQL. Implementa cadastro, login, leitura, atualização, logout e exclusão do próprio usuário conforme o contrato v2.4.0.

## Requisitos

- JDK 17 ou superior (`java`, `javac` e `jar`).
- Bash e OpenSSL para os scripts.
- Docker com Docker Compose para iniciar o MySQL local. Também é possível usar uma instalação MySQL 8.0+ existente.
- Dependências já incluídas em `lib/`: Gson 2.14.0 e MySQL Connector/J 9.6.0. A compilação não faz downloads.

## Iniciar o projeto

Na pasta `ep1-reserva-salas`:

```bash
./mysql-iniciar.sh
./servidor.sh
```

O primeiro comando cria e inicia o MySQL do projeto em `127.0.0.1:3306`, com volume persistente. Na primeira execução, cria o banco `ep1_reserva_salas`, suas tabelas e um usuário próprio da aplicação. As senhas são geradas automaticamente e a configuração JDBC fica em `config/mysql.properties`.

O servidor pergunta a porta TCP, por exemplo `5000`. Em outro terminal, na mesma pasta:

```bash
./cliente.sh
```

O cliente pergunta IP e porta do servidor. O menu permite criar conta, fazer login, consultar, atualizar, apagar a conta e fazer logout. As mensagens JSON aparecem nos dois terminais para a avaliação. Há confirmação explícita para excluir a conta. Enter em um campo de atualização mantém o valor atual.

Parâmetros opcionais:

```bash
./servidor.sh 5000
./cliente.sh 127.0.0.1 5000
./servidor.sh 5001 config/outro-mysql.properties
```

A porta **3306** é do MySQL; a porta **5000** é um exemplo para o servidor TCP do projeto. O cliente de reservas se conecta ao servidor TCP, que acessa o MySQL por JDBC.

Para parar e iniciar novamente o banco:

```bash
./mysql-parar.sh
./mysql-iniciar.sh
```

Parar o contêiner preserva os dados no volume `ep1-reserva-salas_mysql-data`. Os arquivos gerados em `config/` guardam as credenciais dessa instância e devem ser mantidos entre reinícios. Eles estão excluídos do Git e do ZIP de distribuição. Uma cópia nova do projeto em outra máquina gera suas próprias credenciais ao iniciar o banco.

Se 3306 estiver ocupada, escolha outra porta **antes da primeira inicialização**:

```bash
EP1_MYSQL_PORT=3307 ./mysql-iniciar.sh
```

## Banco e tabelas

O [esquema SQL](sql/schema.sql) cria tabelas InnoDB:

| Tabela | Conteúdo e restrições |
|---|---|
| `usuarios` | UUID interno, nome único, e-mail único, hash/salt da senha, perfil e data de criação. |
| `sessoes` | Token como chave primária, uma sessão por usuário, último uso em milissegundos e chave estrangeira com `ON DELETE CASCADE`. |
| `controle_transacoes` | Uma linha usada para bloquear a seção crítica da EP-1 no próprio MySQL. |

Nome e e-mail usam comparação exata. Como o protocolo não limita e-mail a 255 caracteres, ele é salvo como `TEXT` e sua unicidade é garantida por um índice do SHA-256 completo gerado pelo MySQL. As senhas são hashes PBKDF2 com salt individual, nunca senhas em texto no banco.

Cada requisição de negócio abre uma transação JDBC, bloqueia a linha de controle com `SELECT ... FOR UPDATE`, lê o estado atual e persiste somente as alterações. O resultado só é devolvido depois do commit. Erros desfazem a transação por rollback e recebem 500. Essa seção crítica compartilhada também protege dois processos servidores conectados ao mesmo banco. É uma implementação simples para a EP-1; o lock é global e serializa as operações.

Há um segundo banco, `ep1_reserva_salas_teste`, com usuário e credenciais separados para a bateria de testes. O usuário da aplicação não usa a conta root.

## Usar uma instalação MySQL existente

Em vez do Docker, um administrador pode criar o esquema, executando na pasta deste projeto:

```bash
mysql -u root -p < sql/criar-banco.sql
cp config/mysql.properties.example config/mysql.properties
```

Crie um usuário MySQL para a aplicação e conceda `SELECT`, `INSERT`, `UPDATE` e `DELETE` no banco `ep1_reserva_salas`. Preencha `jdbc.url`, `jdbc.user` e `jdbc.password` na configuração. O servidor não cria usuários MySQL nem executa DDL automaticamente: ele verifica conexão e tabelas ao iniciar.

A URL de exemplo utiliza conexão local sem TLS, adequada à instância publicada somente em `127.0.0.1`. Para um servidor MySQL remoto, configure a URL e o TLS conforme esse ambiente. O cliente TCP não precisa das credenciais do banco.

## Estrutura

```text
lib/                       Gson, Connector/J e respectivas licenças
config/                    Exemplos e configurações locais de conexão
sql/schema.sql             Tabelas, índices e chaves estrangeiras
sql/criar-banco.sql         Criação manual do banco da aplicação
sql/docker-init.sh         Inicialização do banco separado de testes
compose.yaml               MySQL 8.4 e volume persistente
src/main/java/br/edu/utfpr/sd/ep1/
  Cliente.java             Menu e formulários
  ClienteTcp.java          Envio, resposta e reconexão
  Servidor.java            Porta TCP e configuração JDBC
  ServidorTcp.java         Sockets e threads
  Protocolo.java           JSON estrito, UTF-8, LF e limite de bytes
  UsuarioService.java      Regras das seis operações
  Banco.java               Modelos de usuário e sessão usados nas transações
  RepositorioUsuarios.java Contrato do repositório
  RepositorioMySql.java     Queries JDBC, bloqueio, commit e rollback
  ConfiguracaoMySql.java    Leitura da configuração e abertura de conexão
  Senhas.java              PBKDF2 com salt
  EntradaConsole.java      Leitor único e validação de portas
src/test/java/             Testes reais com MySQL, TCP e console
build/                     Classes, JAR e respostas gerados localmente
```

Scripts: `build.sh`, `servidor.sh`, `cliente.sh`, `mysql-iniciar.sh`, `mysql-parar.sh` e `testar.sh`.

## Protocolo e decisões da EP-1

Referências incluídas: [AsyncAPI v2.4.0](docs/asyncapi.yaml) e [critérios da EP-1](docs/EP1_Itens-Avaliados.pdf).

| Operação | Campos obrigatórios além de `op` | Sucesso |
|---|---|---|
| `register` | `email`, `user`, `password` | 201; não cria sessão |
| `login` | `email`, `password` | 200; devolve `token` e `role` |
| `read_user` | `token` | 200; devolve cadastro sem senha |
| `update_user` | `token`, `user`, `password` | 200; vazios mantêm valores |
| `logout` | `token` | 200; invalida sessão |
| `delete_user` | `token`, `password` | 200; apaga cadastro e sessão |

Mensagens: objeto JSON estrito, UTF-8, uma linha terminada por LF, até 8.192 bytes incluindo LF. Campos conhecidos são strings; números/booleanos não são convertidos. Toda resposta usa `op`, `status` e `message` com os textos do contrato. Campos desconhecidos são ignorados, exceto `email` nas atualizações, que é proibido.

Regras principais: nome/e-mail únicos; e-mail imutável; nome com 1–30 letras minúsculas; senha alfanumérica com 1–20 caracteres. Atualização totalmente vazia responde 400. Senha errada ao excluir responde 403 e mantém a sessão. O ID interno é estável: mudar o nome preserva o token.

Token tem 64 caracteres hexadecimais minúsculos e expira após 30 minutos sem uso. A conexão fecha após 300 segundos de inatividade, preservando a sessão. O cliente reconecta e reenvia uma vez ao perder a conexão e aguarda confirmação do logout antes de descartar o token. Sair pelo menu com sessão ativa faz logout.

Decisões nas ambiguidades do contrato:

- Campo obrigatório/token ausente, nulo ou malformado: 400. Token bem formado sem sessão ativa: 401.
- Nome/e-mail com maiúsculas: rejeitados, seguindo as regex.
- Ordem: formato → token → negócio; no login, formato → credenciais → sessão ativa.
- Formato válido e token ativo renovam a sessão, inclusive em respostas 403/409. Erro de formato não renova.
- Se uma resposta se perder depois da execução, o reenvio pode retornar conflito ou token inválido; o contrato não tem identificador para recuperar o resultado original.

Administração, salas e reservas ficam para as próximas entregas. A exclusão do último administrador é protegida, mas cadastro público sempre cria perfil `user`. A remoção de reservas em cascata deverá ser adicionada quando essas entidades existirem.

## Compilação e testes

```bash
./build.sh
./testar.sh
```

Os testes exigem o MySQL iniciado. Usam e limpam exclusivamente `ep1_reserva_salas_teste`; o código rejeita qualquer outro nome de banco para a bateria. Cobrem as seis operações, persistência entre instâncias, validade/renovação de sessão com relógio controlado, rollback, restrições SQL, exclusão de sessão em cascata, concorrência entre dois servidores, limite de bytes, JSON inválido, fragmentação TCP, reconexão e menu do cliente. Não requerem JUnit.

Para executar classes já compiladas:

```bash
java -cp 'build/ep1.jar:lib/*' br.edu.utfpr.sd.ep1.Servidor
java -cp 'build/ep1.jar:lib/*' br.edu.utfpr.sd.ep1.Cliente
```

O testador do protocolo original pode enviar mensagens manuais:

```bash
node ../protocolo-reserva-salas-main/scripts/testar-servidor.mjs 127.0.0.1 5000 --interativo
```

A bateria completa dele também exige administração, salas e reservas; essas operações não integram a EP-1. Para esta entrega, use a bateria Java ou as seis operações no modo interativo.

Na entrega, inclua fontes, dependências, SQL, Compose, exemplos de configuração, scripts e documentação. O ZIP contém esses arquivos e não inclui bancos, classes compiladas ou credenciais geradas.

Referências técnicas: [Connector/J e Maven](https://dev.mysql.com/doc/connector-j/en/connector-j-installing-maven.html) e [leituras com lock no InnoDB](https://dev.mysql.com/doc/refman/8.0/en/innodb-locking-reads.html).
