# EP-2 — Reserva de salas com MySQL e TCP

Projeto Java 17+, Swing, sockets TCP e persistência principal em MySQL/MariaDB via JDBC. A execução normal do servidor e a execução integrada do NetBeans usam `RepositorioMySql`. `RepositorioArquivo` permanece para compatibilidade e regressão isolada; arquivos JSON antigos não são importados automaticamente.

## Protocolo fixado

A fonte de verdade é [docs/asyncapi.yaml](docs/asyncapi.yaml), copiada sem adaptações do protocolo **2.8.0**, datado de **04/11/2026**:

- Commit: `342bbcf167977e445c9261610d7992c0dbc59ccf`.
- [Arquivo imutável](https://raw.githubusercontent.com/pedrosalvatori/protocolo-reserva-salas/342bbcf167977e445c9261610d7992c0dbc59ccf/asyncapi.yaml).
- SHA-256: `57964822b0858b3dc8c0222a358f6d884f3ca98ef363ae2353c8f7f797186a94`.

`src/main/resources/contrato-2.8.0.json` é derivado desse YAML e acompanha os JARs. `Contrato` valida os campos e `ValidacaoResposta` valida as respostas contra esses schemas. Para regenerar o recurso: `python3 tools/gerar-contrato.py` (requer PyYAML apenas nessa tarefa de desenvolvimento). Compilar e executar Java não requer Python, Node ou downloads.

**Exceção acordada para a pendência de capacidade:** o contrato exige 409 ao reduzir uma sala abaixo dos participantes de uma reserva futura, mas não define sua mensagem nem inclui a variante no schema de `update_room_response`. A aplicação recusa essa alteração com **409 `Sala possui reservas ativas`**, reutilizando uma mensagem existente, conforme decisão autorizada. O cliente aceita somente essa exceção específica; o YAML permanece intacto.

## Windows institucional, sem administrador

Copie **todo o projeto**, incluindo `lib/`, para uma pasta em que seu usuário possa escrever. Disponibilize um **JDK 17+** no PATH da sessão. Em uma VM com MySQL/MariaDB já ativo e banco previamente preparado, abra um terminal comum, entre na pasta do projeto e execute:

```bat
preparar-ep2.cmd
servidor-ep2.cmd 26000
```

Em outro terminal:

```bat
cliente-ep2.cmd 127.0.0.1 26000
```

Para conectar a outra máquina: `cliente-ep2.cmd 10.20.50.X 26000`, substituindo o endereço pelo IP do servidor. O cliente usa o Swing existente e **não precisa de banco nem configuração MySQL na máquina cliente**. Os launchers continuam mostrando todos os logs `C -> S {...}` / `S -> C {...}`. A porta TCP Java padrão desses launchers é **26000**; os scripts antigos preservam seu comportamento.

Os `.cmd` usam `%~dp0` para localizar o projeto e chamam `powershell.exe -NoProfile -ExecutionPolicy Bypass -File ...`. O bypass vale somente para aquele processo, inclusive a compilação filha. Não há alteração permanente de ExecutionPolicy, pedido de UAC, instalação, gerenciamento de serviços ou encerramento de processos que ocupem portas. Uma política institucional de grupo (`MachinePolicy`/`UserPolicy`) pode ter precedência sobre o parâmetro por processo; nesse caso o erro deve ser encaminhado à instituição, sem tentar contorná-la. [Referência Microsoft](https://learn.microsoft.com/en-us/powershell/module/microsoft.powershell.core/about/about_execution_policies?view=powershell-5.1).

`preparar-ep2.cmd` verifica Java, o handshake MySQL/MariaDB no host/porta configurados, a configuração local, a compilação e o JDBC. Reutiliza o banco ativo, mesmo que o XAMPP também esteja instalado. Não inicia outro MySQL, Apache ou MongoDB. Se `config/mysql.properties` já existir, seus bytes são preservados, mesmo se uma variável de senha estiver definida. Se faltar, o script copia o exemplo e solicita a senha sem eco; alternativamente lê `EP2_MYSQL_PASSWORD` **somente na criação inicial**. Não há senha de laboratório embutida nem opção com senha fixa. Não digite senhas como argumentos de comandos nem as inclua em capturas de tela.

O arquivo segue o formato simples `host=...`, `port=...`, `database=ep1_reserva_salas`, `user=ep1`, `password=...`. A senha informada pelo script recebe os escapes de Java Properties automaticamente. Se o arquivo existente contiver um marcador de exemplo, edite-o localmente; o script não o substitui. A variável opcional não é propagada aos processos Java filhos. Os arquivos reais `config/*.properties` são ignorados pelo Git.

Diagnóstico ampliado:

```bat
preparar-ep2.cmd --diagnostico
```

Mostra versão/caminho do Java, raiz do projeto, endereço/porta do banco, usuário, presença da configuração, driver e classes, processo da porta quando a consulta for permitida, versão real do banco e resultado JDBC. Não imprime a senha nem o conteúdo de `mysql.properties`. O teste opcional de infraestrutura verifica as sete tabelas, suas colunas, InnoDB, a linha de controle e `SELECT/INSERT/UPDATE/DELETE`. Os comandos DML possuem `WHERE 1=0`, afetam zero linhas e são finalizados com rollback; não cadastram usuários/salas/reservas nem reinicializam o admin. A conexão simples continua disponível sem essa opção.

Se o preparo falhar:

| Erro | Providência do usuário comum |
|---|---|
| Java ausente/antigo ou `javac` ausente | Disponibilizar um JDK 17+ permitido pela instituição no PATH da sessão. O script não instala Java. |
| Banco inacessível ou porta com outro protocolo | Conferir host/porta com a equipe da VM. Se o MySQL do XAMPP já estiver ativo, reutilizá-lo. Não iniciar outra instância, matar o processo ou mexer em serviços protegidos. |
| Autenticação recusada | Conferir `user/password` no arquivo local, sem publicar seu conteúdo. |
| Banco/tabelas/colunas ausentes, motor diferente de InnoDB ou linha de controle ausente | Solicitar uma única preparação/revisão de `sql/schema.sql` por quem já tem permissão no banco. |
| Permissões insuficientes | Solicitar `SELECT`, `INSERT`, `UPDATE`, `DELETE` nas tabelas de `ep1_reserva_salas` para a conta da aplicação. Não é necessário root na execução normal. |
| Porta Java ocupada | Usar outra porta em ambos os launchers; nenhum processo será encerrado. |
| Driver ausente | Copiar a pasta `lib/` completa junto do projeto. |

O preparo retorna código diferente de zero se faltar algo. Ele não executa `CREATE USER`, `GRANT`, DDL ou limpeza de dados. A criação inicial do admin permanece no Java, ao iniciar o servidor. Repetir o preparo não apaga nem reseta dados. Para apresentar: [roteiro de testes manuais EP-2](docs/TESTES_MANUAIS_EP2.md).

## Preparação inicial do banco (responsável com permissão)

Esta seção é para quem prepara a infraestrutura uma única vez, com uma conta MySQL já autorizada. **Não é uma exigência de privilégios administrativos do Windows para executar a aplicação.** Na VM com banco/tabelas e conta já disponíveis, basta a seção anterior.

1. Confirme que há um MySQL/MariaDB disponível no host/porta. Se ele já responder, reutilize-o. A aplicação não depende de Apache ou phpMyAdmin.
2. Execute **sql/schema.sql** com uma conta MySQL que já tenha permissão para essa preparação. No Bash, a partir da raiz: `mysql -u CONTA_DE_PREPARACAO -p < sql/schema.sql`. No Windows, `C:\xampp\mysql\bin\mysql.exe` pode ser usado, caso exista; o caminho do projeto continua relativo à sua própria raiz.
3. Crie uma conta para a aplicação, por exemplo no banco local do XAMPP:

```sql
CREATE USER 'ep1'@'localhost' IDENTIFIED BY 'ESCOLHA_UMA_SENHA_LOCAL';
GRANT SELECT, INSERT, UPDATE, DELETE
    ON ep1_reserva_salas.* TO 'ep1'@'localhost';
```

A senha acima é um marcador, não uma credencial. Para acesso a um MySQL em contêiner, o host da conta deve corresponder à origem vista pelo banco. A instância de desenvolvimento foi publicada somente em `127.0.0.1:3306` e usa uma conta `ep1` própria. A aplicação não usa root e não executa DDL automaticamente.

4. Use `config/mysql.properties.example` como modelo de **config/mysql.properties**:

```properties
host=127.0.0.1
port=3306
database=ep1_reserva_salas
user=ep1
password=SUA_SENHA_LOCAL
```

Ajuste a porta conforme o banco. A porta **3306** é do MySQL; **5000** será a do servidor TCP Java. Uma senha vazia é representada por `password=`. As propriedades são UTF-8; uma barra invertida literal deve ser escrita como `\\`, conforme o formato Java Properties. Os arquivos reais de configuração são ignorados pelo Git. As chaves antigas `jdbc.url`, `jdbc.user` e `jdbc.password` não são utilizadas.

O driver local é **MariaDB Connector/J 3.5.11**, compatível com MySQL e MariaDB. A URL gerada é `jdbc:mariadb://host:port/database`, inclusive para MySQL. O `DriverManager` o descobre automaticamente; não é necessário `Class.forName`. Para o primeiro login TCP local no MySQL 8, a recuperação da chave RSA é habilitada somente em `localhost` e `127.0.0.1`.

## Compilar e verificar a conexão

Bash/Linux/Git Bash:

```bash
./build.sh --classes-only
java -cp 'build/classes:lib/*' br.edu.utfpr.sd.ep1.TesteConexaoMySql
```

PowerShell/Windows (bypass restrito ao processo):

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\build.ps1 -SomenteClasses
java -cp 'build/classes;lib/*' br.edu.utfpr.sd.ep1.TesteConexaoMySql
```

O diagnóstico deve mostrar conexão válida, versão real, banco `ep1_reserva_salas` selecionado e conexão fechada corretamente. Ele não cria tabelas. Com Ant: `ant test-mysql`. Se aparecer `No suitable driver`, confira `lib/mariadb-java-client-3.5.11.jar` e o classpath. A pasta `lib/` deve acompanhar os JARs.

Para conferir também schema/permissões, sem modificar registros: `java -cp "build/classes;lib/*" br.edu.utfpr.sd.ep1.TesteConexaoMySql config/mysql.properties --verificar-ep2` (no Linux, use `:` no classpath). Os launchers fazem essa checagem automaticamente.

## Iniciar servidor e cliente

Em dois terminais na raiz:

```bash
./servidor.sh 5000 config/mysql.properties
./cliente.sh 127.0.0.1 5000
```

No Windows, prefira `servidor-ep2.cmd 26000` e `cliente-ep2.cmd 127.0.0.1 26000`. Os scripts `.ps1` anteriores continuam disponíveis; para executá-los sob bloqueio de scripts locais use `powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\servidor.ps1 5000 config/mysql.properties` e o equivalente para `cliente.ps1`.

Para o cliente de console: `./cliente.sh --console 127.0.0.1 5000`. Ele preserva as opções da EP-1 e acrescenta os menus Salas, Administração e Reservas.

### NetBeans / Ant

Abra a pasta que contém `build.xml` e `nbproject/` como projeto existente. Configure JDK 17+ e pressione **F6**: `Aplicacao` inicia o servidor local e a GUI usando MySQL. Não execute outro servidor na mesma porta. Ao fechar a janela, o servidor integrado encerra; o banco permanece ativo.

O menu do projeto contém executar somente servidor, somente cliente e testar conexão MySQL. Fora da IDE: `ant run`, `ant run-server`, `ant run-client`, `ant jar`. Exemplo de configuração alternativa: `ant -Dport=5001 -Dmysql.config=config/outro.properties run`.

### Administrador inicial

Quando nenhum administrador existir, o servidor cria, de forma transacional e idempotente:

- Usuário: `admin`.
- E-mail: **admin@admin.com**.
- Senha: **admin**.
- Perfil: `admin`.

A senha recebe PBKDF2 e salt individual. Cadastro público sempre cria `user`. O último administrador não pode ser excluído nem rebaixado. Uma conta legada comum que já ocupe o nome/e-mail do admin inicial provoca erro de inicialização para revisão manual, preservando os dados.

## Operações e interface

| Área | Operações |
|---|---|
| Própria conta | `register`, `login`, `logout`, `read_user`, `update_user`, `delete_user` |
| Administração | `admin_list_users`, `admin_read_user`, `admin_update_user`, `admin_delete_user` |
| Salas | `create_room`, `list_rooms`, `read_room`, `update_room`, `delete_room` |
| Disponibilidade e reservas | `check_availability`, `create_reservation`, `list_reservations`, `read_reservation`, `update_reservation`, `delete_reservation` |

Na GUI, use **Minha conta**, **Usuários — ADM**, **Salas e agenda** e **Reservas**. Os resultados são exibidos em tabelas e os pedidos/respostas completos aparecem no terminal. O formulário de sala oferece uma grade de dias e aulas UTFPR. No console, informe códigos separados por vírgula, como `2M1,2M2,3T1`.

Os horários seguem exatamente o contrato: `2M1` significa segunda-feira, manhã, aula 1 (07:30–08:20). São aceitos dias 2 a 7, M1–M6, T1–T6 e N1–N5. A reserva deve começar e terminar nos limites das aulas. Domingo e horários fora dos slots não ficam disponíveis. `read_room` com data mostra a agenda, o responsável e os identificadores permitidos para o solicitante.

`room_status=inactive` bloqueia novas reservas, preservando as existentes. `available` é calculado por sala, data e período. `slots` ausente ou vazio na atualização mantém a grade; uma lista preenchida a substitui. `resources` representa sempre a lista completa, portanto vazio remove os recursos. Campos escalares vazios em atualização mantêm o valor anterior.

As permissões são verificadas no servidor. A whitelist de admin é reconstruída dentro de cada transação a partir dos tokens válidos e dos perfis atuais. Promoção/rebaixamento vale sem novo login; para atualizar a aba visível do cliente após mudança de perfil, consulte novamente Minha conta. Logout, expiração e exclusão removem o poder administrativo. O token continua associado ao UUID ao renomear um usuário.

## Arquitetura e persistência

```text
Cliente Swing ou console → ClienteTcp → TCP/JSON → ServidorTcp
  → UsuarioService (autenticação e administração)
  → SalaService / ReservaService
  → RepositorioUsuarios → RepositorioMySql → MySQL/InnoDB
```

`Servidor` monta os componentes; `ServidorTcp` mantém uma thread por conexão. Todas as operações de negócio leem e alteram o estado dentro da mesma transação JDBC. Um `SELECT ... FOR UPDATE` na linha de `controle_transacoes` coordena inclusive instâncias diferentes do servidor. É um bloqueio global simples: protege login, administração e reservas conflitantes, mas serializa o acesso e não foi projetado para grande volume. O repositório persiste apenas as diferenças, confirma com commit e executa rollback em falhas.

| Tabela | Finalidade |
|---|---|
| `usuarios` | UUID, nome único, e-mail, hash/salt, perfil e criação |
| `sessoes` | Token único, uma sessão por usuário, último uso e FK com cascata |
| `salas` | Identidade numérica, nome único, capacidade, localização e situação |
| `sala_recursos` | Recursos da sala, preservando a ordem da lista |
| `sala_slots` | Horários UTFPR da sala, sem duplicatas |
| `reservas` | Usuário/sala, data, intervalo, assunto, participantes e criação |
| `controle_transacoes` | Bloqueio entre servidores e sequências de IDs |

O e-mail continua `TEXT`, sem limite artificial de 255 caracteres e sem hash adicional. Sua unicidade é validada pelo Service e pelo repositório na seção crítica. Escritas externas que ignorem esse fluxo podem violar essa regra; a tabela não tem `UNIQUE(email)`. Remover um usuário elimina suas sessões e todas as reservas, passadas e futuras. Salas com reservas ainda não encerradas não podem ser excluídas.

## Transporte e erros

JSON estrito UTF-8, uma linha terminada por LF, até 8192 bytes incluindo LF. Uma resposta por requisição, na ordem e na mesma conexão. Campos escalares são strings; arrays seguem o contrato. Token: 64 hexadecimais, validade de 30 minutos sem uso. A conexão TCP fecha após 300 segundos de inatividade sem invalidar o token.

O cliente reconecta e reenvia uma vez em falhas de transporte. Respostas completas inválidas não provocam reenvio. Sem identificador de requisição no protocolo, uma resposta perdida pode deixar o resultado incerto, por exemplo um login repetido recebe 409. Listagens que não cabem em 8192 bytes recebem 500; o protocolo não define paginação e a aplicação não trunca resultados. Erros enviados ao cliente não incluem SQL nem stack trace.

## Testes

Prepare um banco **separado**, chamado exatamente `ep1_reserva_salas_teste`, com as mesmas tabelas. Uma forma de gerar o SQL, sem alterar o esquema principal:

```bash
./build.sh --classes-only
python3 -c "from pathlib import Path; Path('build/schema-test.sql').write_text(Path('sql/schema.sql').read_text().replace('ep1_reserva_salas','ep1_reserva_salas_teste'))"
mysql -u CONTA_DE_PREPARACAO -p < build/schema-test.sql
```

Essa preparação é separada e feita por quem já tem permissão no banco. Os launchers não criam configuração, banco ou conta de testes automaticamente. No Windows, use `python` se esse for o nome disponível e importe `build/schema-test.sql` com um cliente MySQL autorizado. Crie a conta de testes com uma senha local:

```sql
CREATE USER 'ep1_teste'@'localhost' IDENTIFIED BY 'ESCOLHA_OUTRA_SENHA_LOCAL';
GRANT SELECT, INSERT, UPDATE, DELETE
    ON ep1_reserva_salas_teste.* TO 'ep1_teste'@'localhost';
```

Configure `config/mysql-test.properties` conforme o exemplo correspondente. As baterias MySQL recusam outro nome de banco, usam lock para impedir execuções simultâneas e limpam os dados de testes ao terminar. Elas não devem apontar para o banco da aplicação. Os testes legados de arquivo usam exclusivamente o caminho protegido `build/ep1-teste.json`.

Execute **em sequência**, em uma sessão com ambiente gráfico:

```bash
./testar.sh
# ou somente EP-2 e GUI com MySQL:
ant test-ep2
```

No PowerShell: `powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\testar.ps1`. No NetBeans: **Test Project**. Para configuração alternativa, `./testar.sh config/outro-teste.properties` ou `ant -Dmysql.test.config=config/outro-teste.properties test`.

O testador oficial do commit fixado pode ser baixado e executado contra um processo Java temporário no banco de testes:

```bash
python3 tools/testar-protocolo.py
```

Esse comando adicional requer Node.js 18+, Python 3 e internet na primeira execução. O servidor temporário é encerrado ao final. Ele não requer npm install. Faça logout do admin de testes antes de rodá-lo caso tenha feito login manualmente.

Resultados e limitações verificados estão em [docs/RELATORIO_EP2.md](docs/RELATORIO_EP2.md). `build/` contém logs, capturas JSON e screenshots gerados e permanece fora do Git. O relatório do arquivo é histórico de regressão; MySQL é a persistência principal desta entrega.

## Demonstração ao professor

1. Na VM com banco já preparado, execute `preparar-ep2.cmd` usando a configuração local. Se faltar schema/permissão, solicite a preparação inicial separada; o launcher não faz provisionamento.
2. Execute `servidor-ep2.cmd 26000` e `cliente-ep2.cmd 127.0.0.1 26000` em terminais separados.
3. Entre com `admin@admin.com` / `admin` e liste os usuários.
4. Cadastre um usuário comum em outro cliente; atualize seu nome pelo ADM e confirme que o token continua válido. Teste promoção e rebaixamento.
5. Cadastre uma sala com capacidade 6 e marque `2M1`, `2M2` e `2M3` na grade.
6. Liste a sala, consulte sua agenda para uma segunda-feira futura e faça uma reserva 07:30–08:20 pelo cliente comum.
7. Consulte a mesma agenda pelo ADM e por outro usuário: ambos veem quem reservou, mas o identificador da reserva é restrito ao dono/ADM.
8. Atualize capacidade/localização/situação e demonstre que `inactive` bloqueia novas reservas.
9. Tente excluir a sala com reserva futura e observe 409. Exclua o usuário comum pelo ADM, confirme a remoção da reserva e então exclua a sala.
10. Mostre os JSONs nos terminais. Reinicie o servidor com o mesmo banco e confira a persistência. Faça logout dos clientes ao terminar.

O [roteiro manual completo](docs/TESTES_MANUAIS_EP2.md) detalha as pré-condições, formulários, JSONs e resultados dos 24 cenários, incluindo validações de espaços, slots, conflitos, visibilidade, persistência e logout.
