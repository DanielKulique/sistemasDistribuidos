# Relatório final — EP-2

Validação realizada em 05/11/2026. Protocolo oficial 2.8.0, commit `342bbcf167977e445c9261610d7992c0dbc59ccf`. O SHA-256 de `docs/asyncapi.yaml` permanece `57964822b0858b3dc8c0222a358f6d884f3ca98ef363ae2353c8f7f797186a94`.

## Arquitetura final

Cliente Swing/console → ClienteTcp → JSON UTF-8/LF sobre TCP → ServidorTcp (uma thread por conexão) → UsuarioService (autenticação/administração) → SalaService/ReservaService → RepositorioUsuarios → RepositorioMySql → InnoDB.

A inicialização normal por Servidor e Aplicacao usa MySQL. RepositorioArquivo é mantido para regressão e compatibilidade. UUIDs, hashes PBKDF2, salts e sessões não dependem do nome mutável do usuário. Não há importação automática de arquivos JSON legados.

Uma transação lê o estado, executa a regra, valida a integridade e grava diferenças. Todas as instâncias bloqueiam a linha de controle no MySQL; falhas executam rollback. O bootstrap do administrador é idempotente e usa a mesma transação. A whitelist é reconstruída das sessões válidas e dos perfis atuais, sem tabela adicional ou cache permanente de permissões.

## Banco de dados

`usuarios`, `sessoes`, `salas`, `sala_recursos`, `sala_slots`, `reservas` e `controle_transacoes` estão definidos em `sql/schema.sql`, com chaves primárias, unicidade de nomes e sessão por usuário, índices de período e chaves estrangeiras. Exclusão de usuário elimina sessões e todas as reservas. Exclusão de sala é recusada enquanto houver reserva não encerrada.

O e-mail permanece TEXT. Sua unicidade é garantida pelas validações do Service/repositório dentro da seção crítica; não há índice UNIQUE de e-mail nem hash adicional. Gravações externas que ignorem o repositório não têm essa garantia.

## Operações implementadas

- EP-1: register, login, logout, read_user, update_user, delete_user.
- Administração: admin_list_users, admin_read_user, admin_update_user, admin_delete_user.
- Salas: create_room, list_rooms, read_room, update_room, delete_room.
- Disponibilidade e agenda: check_availability, slots UTFPR, agenda por data, responsável e visibilidade restrita de reservation_id.
- Suporte persistente a reservas: create_reservation, list_reservations, read_reservation, update_reservation, delete_reservation.

Ambos os clientes oferecem os fluxos. O cliente gráfico tem formulários, grade de horários e tabelas; o console tem submenus. A autorização ocorre no servidor, independentemente da tela.

## Testes executados

Ambiente: OpenJDK 25 compilando com `--release 17`, MySQL **8.4.11** real via TCP e driver MariaDB Connector/J **3.5.11**. Esquemas da aplicação e dos testes foram executados no MySQL. A conta da aplicação não é root; as credenciais reais estão em arquivos ignorados pelo Git.

| Verificação | Backend | Resultado |
|---|---|---|
| TesteArquivo | Arquivo isolado | 11 verificações aprovadas |
| TesteEp1 | Arquivo + sockets reais | 214 verificações aprovadas |
| TesteComunicacao | Arquivo, sockets e servidores simulados | 33 casos, 342 verificações, zero falhas |
| TesteGui | Memória + sockets reais + Swing | 45 verificações aprovadas |
| TesteAplicacao | MySQL real + entrada integrada + Swing | Inicialização, cadastro TCP, persistência e encerramento aprovados |
| TesteEp2 | MySQL real + sockets + dois servidores/processos | 180 verificações aprovadas |
| TesteGuiEp2 | MySQL real + formulários Swing + TCP | 22 verificações aprovadas |
| Testador oficial do commit fixado | MySQL real + servidor Java | **86 passaram, zero falhas, zero pulados** |
| Ajv independente sobre capturas EP-2 | YAML oficial dereferenciado | 79 respostas conformes; 1 exceção de capacidade autorizada |
| Terminal/Ant e JAR | Classpath real | Compilação, recursos e empacotamento aprovados |
| Execução normal com config/mysql.properties | MySQL da aplicação | Login admin, administração no console, consulta de salas e logout aprovados |

Total das baterias Java com contador: **814 verificações**, além do teste integrado da aplicação e dos 86 testes oficiais.

A bateria MySQL testa: bootstrap concorrente; permissões por token; promoção/rebaixamento sem relogar; proteção do último admin; campos proibidos; CRUD e slots inválidos; disponibilidade por data; privacidade da agenda; exclusão de dependências; expiração e logout; rollback após uma escrita SQL seguida de erro; FK de sessões; criação/atualização/exclusão de salas e reservas concorrentes entre servidores; reinício de um processo Java conservando tokens e dados. A regressão cobre framing, limites, UTF-8, JSON inválido, respostas malformadas, retransmissão, falha de persistência e fluxo do console.

As baterias Java/MySQL só limpam `ep1_reserva_salas_teste` e usam lock para impedir execuções simultâneas. Não apontam para o banco da aplicação. O testador oficial deve ser executado em sequência, com o admin de testes sem sessão manual ativa.

Artefatos locais, fora do Git: `build/validacao-final.log`, `build/relatorio-oficial.log`, `build/respostas-ep2.json`, `build/gui-ep2.png`, `build/reinicio-mysql.log`, `build/smoke-console-ep2.log` e `build/smoke-servidor-aplicacao.log`.

## Exceções e limites reais

1. A pendência do protocolo sobre redução de capacidade foi resolvida nesta implementação conforme autorização: update_room retorna 409 **Sala possui reservas ativas**. O YAML oficial não foi alterado; o cliente possui uma exceção explícita apenas para essa combinação. Ela é separada da contagem de respostas conformes ao schema na validação independente.
2. XAMPP/MariaDB específico não estava instalado neste ambiente. A execução real foi validada em MySQL 8.4.11; o driver e o SQL utilizam recursos compatíveis com o MySQL/MariaDB local previsto para avaliação. Não foi realizada uma execução adicional no painel do XAMPP.
3. O bloqueio transacional é global: prioriza correção e simplicidade, não alto volume. O protocolo limita respostas a 8192 bytes e não define paginação; listas grandes recebem 500, sem truncamento.
4. O contrato não oferece identificador de requisição para recuperar uma resposta perdida. A reconexão pode receber conflito após uma operação já executada; os testes demonstram esse limite sem alterar o protocolo.
5. O ambiente Linux executou as classes, os scripts Bash e o Ant. Os arquivos PowerShell e do NetBeans foram ajustados; não houve execução em Windows nem abertura da IDE NetBeans. A entrada integrada e a GUI foram exercitadas por testes reais.

Não há funcionalidade obrigatória da EP-2 deixada por implementar. A exceção de capacidade é intencional, autorizada e documentada.

## Como repetir e demonstrar

As instruções completas de preparação MySQL/XAMPP, importação do esquema, contas locais, configuração, compilação e testes estão no README.

1. Inicie MySQL e execute sql/schema.sql com uma conta administrativa.
2. Preencha config/mysql.properties usando o exemplo, sem versionar a senha.
3. Execute ./build.sh --classes-only e o TesteConexaoMySql.
4. Inicie ./servidor.sh 5000 config/mysql.properties e ./cliente.sh 127.0.0.1 5000.
5. Entre como admin@admin.com / admin; abra Usuários — ADM e liste os cadastros.
6. Cadastre um usuário em outro cliente, atualize seu nome pelo ADM e confira que a sessão continua válida. Demonstre promoção/rebaixamento e a proteção do último admin.
7. Cadastre uma sala, capacidade 6, com 2M1, 2M2 e 2M3. Consulte a agenda de uma segunda-feira futura.
8. Faça reserva 07:30–08:20 com o usuário comum. Compare a agenda do ADM e de outro usuário.
9. Atualize a sala e demonstre a recusa de exclusão enquanto houver reserva futura. Exclua o usuário pelo ADM, confira a remoção da reserva e então exclua a sala.
10. Mostre as mensagens nos terminais. Reinicie o servidor com o mesmo banco e confirme os dados persistidos. Faça logout ao encerrar.

Para regressão completa, prepare config/mysql-test.properties e o banco isolado e execute ./testar.sh (sessão gráfica). Para o oficial: python3 tools/testar-protocolo.py. No Windows/NetBeans, veja os comandos equivalentes no README.

## Arquivos criados

- `config/mysql-test.properties.example`
- `config/mysql.properties.example`
- `docs/RELATORIO_EP2.md`
- `lib/MARIADB-CONNECTOR-J-LICENSE.txt`
- `lib/mariadb-java-client-3.5.11.jar`
- `sql/schema.sql`
- `src/main/java/br/edu/utfpr/sd/ep1/ConfiguracaoMySql.java`
- `src/main/java/br/edu/utfpr/sd/ep1/Contrato.java`
- `src/main/java/br/edu/utfpr/sd/ep1/OperacoesCliente.java`
- `src/main/java/br/edu/utfpr/sd/ep1/PainelOperacoes.java`
- `src/main/java/br/edu/utfpr/sd/ep1/RepositorioMySql.java`
- `src/main/java/br/edu/utfpr/sd/ep1/ReservaService.java`
- `src/main/java/br/edu/utfpr/sd/ep1/SalaService.java`
- `src/main/java/br/edu/utfpr/sd/ep1/TesteConexaoMySql.java`
- `src/main/resources/contrato-2.8.0.json`
- `src/test/java/br/edu/utfpr/sd/ep1/SuporteMySqlTeste.java`
- `src/test/java/br/edu/utfpr/sd/ep1/TesteEp2.java`
- `src/test/java/br/edu/utfpr/sd/ep1/TesteGuiEp2.java`
- `tools/gerar-contrato.py`
- `tools/testar-protocolo.py`

## Arquivos alterados

- `README.md`
- `build.ps1`
- `build.sh`
- `build.xml`
- `docs/RELATORIO_TESTES.md`
- `docs/RELATORIO_TESTES_ARQUIVO.md`
- `docs/RELATORIO_TESTES_GUI.md`
- `docs/RELATORIO_TESTES_MONGO.md`
- `docs/RELATORIO_TESTES_NETBEANS.md`
- `docs/asyncapi.yaml`
- `lib/README.md`
- `nbproject/project.xml`
- `src/main/java/br/edu/utfpr/sd/ep1/Aplicacao.java`
- `src/main/java/br/edu/utfpr/sd/ep1/Banco.java`
- `src/main/java/br/edu/utfpr/sd/ep1/Cliente.java`
- `src/main/java/br/edu/utfpr/sd/ep1/ClienteGui.java`
- `src/main/java/br/edu/utfpr/sd/ep1/Servidor.java`
- `src/main/java/br/edu/utfpr/sd/ep1/UsuarioService.java`
- `src/main/java/br/edu/utfpr/sd/ep1/ValidacaoResposta.java`
- `src/test/java/br/edu/utfpr/sd/ep1/TesteAplicacao.java`
- `src/test/java/br/edu/utfpr/sd/ep1/TesteComunicacao.java`
- `src/test/java/br/edu/utfpr/sd/ep1/TesteEp1.java`
- `src/test/java/br/edu/utfpr/sd/ep1/TesteGui.java`
- `src/test/resources/respostas-ep1.json`
- `testar.ps1`
- `testar.sh`
