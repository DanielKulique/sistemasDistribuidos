# EP-1 — Reserva de salas com MongoDB

Cliente gráfico Swing e servidor Java TCP com uma thread por conexão, Gson e MongoDB. Implementa as seis operações de usuários da EP-1 conforme o contrato v2.4.0. Swing faz parte do Java padrão; não exige JavaFX ou bibliotecas gráficas externas.

## Requisitos

- JDK 17 ou superior (`java`, `javac` e `jar`).
- MongoDB em execução, local ou remoto, com permissão de leitura e escrita no banco escolhido. Não exige Docker nem replica set.
- Dependências incluídas em `lib/`: Gson 2.14.0 e driver Java MongoDB 5.2.1 (sync, core e BSON). A compilação não faz downloads.
- PowerShell no Windows ou Bash no Linux/macOS.

## Executar no Windows

Na pasta do projeto, copie os exemplos (apenas na primeira configuração):

```powershell
Copy-Item config/mongo.properties.example config/mongo.properties
Copy-Item config/mongo-test.properties.example config/mongo-test.properties
```

Edite `config/mongo.properties` se necessário:

```properties
mongo.uri=mongodb://127.0.0.1:27017
mongo.database=ep1_reserva_salas
```

O MongoDB deve estar iniciado. Se usar outro endereço, porta ou autenticação, altere `mongo.uri` (por exemplo `mongodb://usuario:senha@servidor:27017/?authSource=admin`, com caracteres especiais percent-encoded). Os arquivos `.properties` locais não são versionados. Não coloque credenciais nos exemplos.

Inicie o servidor e, em outro terminal, o cliente:

```powershell
.\servidor.ps1 5000
.\cliente.ps1 127.0.0.1 5000
```

Os scripts compilam antes de executar e usam o Java do mesmo JDK encontrado por javac, evitando conflito com o Java 8 instalado. Se a política local bloquear scripts e permitir execução por processo, execute `powershell -ExecutionPolicy Bypass -File .\servidor.ps1 5000` (e o equivalente para o cliente). Não é necessário instalar serviços nem alterar a política da máquina.

Sem parâmetros, servidor e cliente perguntam porta/endereço. Para outra configuração: `.\servidor.ps1 5001 config/outro-mongo.properties`.

No Bash, use `./servidor.sh 5000` e `./cliente.sh 127.0.0.1 5000`. A porta padrão do MongoDB é **27017**; **5000** é a porta TCP da aplicação. O cliente conecta ao servidor Java, que acessa o banco.

A janela permite criar conta, fazer login, consultar, atualizar, excluir a conta com confirmação e fazer logout. Informe endereço e porta no topo. Após o login, o servidor fica fixo até sair da sessão. Deixe os campos de atualização vazios para manter os valores atuais. Fechar a janela autenticada envia logout; uma falha de comunicação pede confirmação antes de sair sem resposta.

As chamadas TCP são executadas fora da thread gráfica para manter a janela responsiva. As requisições e respostas JSON completas aparecem nos terminais do cliente e do servidor, identificadas por `C -> S` e `S -> C`, inclusive cadastro, login, consulta, atualização, exclusão, logout e reenvios. Conexões e falhas também são registradas. Mensagens com UTF-8 inválido são registradas em Base64; mensagens acima do limite têm o prefixo registrado, mantendo o limite de memória do protocolo.

Para usar o cliente de console: `./cliente.sh --console 127.0.0.1 5000` ou `.\cliente.ps1 --console 127.0.0.1 5000`. O servidor continua no terminal e deve estar em execução antes de usar os formulários.

## Persistência e concorrência

Na primeira conexão, a aplicação cria o documento `_id: ep1` na coleção `estado` do banco configurado. Não há SQL nem criação manual de tabelas. Usuários e sessões são armazenados como arrays BSON; senhas permanecem como hash PBKDF2 com salt.

Cada operação lê o estado e sua versão, executa as regras em memória e substitui o documento somente se a versão ainda for a mesma. Um conflito refaz a operação sobre o estado atual, até 100 tentativas. A resposta só é devolvida depois da gravação confirmada. A substituição é atômica, inclusive em MongoDB standalone, e impede perda de alterações entre processos servidores. Falhas de validação ou exceções antes da gravação não persistem alterações.

O repositório valida unicidade de ID, nome, e-mail e sessão por usuário, além da referência de sessões para usuários. A exclusão de conta pelo serviço remove também sua sessão. Essas regras são garantidas pelo código do repositório; edição manual dos documentos pode violá-las.

**Limite desta implementação para a EP-1:** todo o estado fica em um documento, sujeito ao limite BSON de 16 MiB e à contenção em um único documento. É adequada à entrega acadêmica; uma aplicação maior precisará separar coleções e usar transações com replica set. Dados de uma instalação MySQL anterior não são migrados automaticamente.

## Estrutura

- `src/main/java/`: cliente, servidor TCP, protocolo, regras e `RepositorioMongo`/`ConfiguracaoMongo`.
- `src/test/java/`: testes de persistência, serviço, concorrência, TCP e console.
- `config/`: exemplos de conexão da aplicação e dos testes.
- `lib/`: bibliotecas e licenças.
- `build/`: classes, JAR e resultados gerados, ignorados pelo Git.
- Scripts `build`, `servidor`, `cliente` e `testar`, em versões `.ps1` e `.sh`.

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

Chaves JSON repetidas são rejeitadas para evitar interpretações diferentes entre bibliotecas. O cliente valida as respostas, incluindo campos obrigatórios de sucesso. Respostas completas inválidas, erro genérico 400 ou erro de negócio/servidor não provocam reenvio automático; falhas de conexão ou resposta interrompida permitem uma reconexão. Chamadas no mesmo cliente são serializadas, e dados extras já pendentes na conexão são rejeitados antes de novo envio.

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

Resultado com Swing: **602 verificações aprovadas**, conforme o [relatório da GUI e dos logs](docs/RELATORIO_TESTES_GUI.md).

```powershell
.\build.ps1
.\testar.ps1
```

No Bash: `./build.sh` e `./testar.sh`. Para outra conexão de testes: `.\testar.ps1 config/outro-teste.properties`.

Configure `config/mongo-test.properties` com a URI do MongoDB e `mongo.database=ep1_reserva_salas_teste`. Os testes apagam dados exclusivamente desse banco, e rejeitam outro nome. Uma trava na coleção `travas_teste` impede baterias simultâneas. Se o processo for morto abruptamente, a trava pode ficar retida; após confirmar que nenhuma bateria está rodando, remova apenas o documento `_id: bateria` dessa coleção no banco de testes.

As baterias cobrem operações, persistência entre instâncias, expiração/renovação de sessões, rejeição de estados inconsistentes sem gravação parcial, concorrência entre servidores, JSON inválido, fragmentação TCP, reconexão, console e interface Swing. Não requerem JUnit. A bateria gráfica exige uma sessão com ambiente gráfico e testa botões com servidor TCP real, além dos JSONs em ambos os terminais. Resultados da versão MongoDB estão em [relatório](docs/RELATORIO_TESTES_MONGO.md); o relatório anterior de MySQL é apenas histórico.

Para executar sem scripts após compilar, no Windows:

```powershell
java -cp 'build/ep1.jar;lib/*' br.edu.utfpr.sd.ep1.Servidor 5000
java -cp 'build/ep1.jar;lib/*' br.edu.utfpr.sd.ep1.Cliente 127.0.0.1 5000
```

No Linux/macOS use `:` como separador do classpath.

Referências: [atomicidade e concorrência no MongoDB](https://www.mongodb.com/docs/manual/core/write-operations-atomicity/) e [substituição de documentos com o driver Java](https://www.mongodb.com/docs/drivers/java/sync/current/crud/replace-documents/).
