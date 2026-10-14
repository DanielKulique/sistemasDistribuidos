# Bateria de testes da EP-1 — funcionalidades, TCP e protocolo

Data: 14/10/2026. Contrato: AsyncAPI v2.4.0. Base inicial: commit `31b8c85`.

## Resultado final

| Verificação | Resultado |
|---|---|
| Bateria funcional e persistência (`TesteEp1`) | 214 verificações aprovadas |
| Bateria de comunicação (`TesteComunicacao`) | 33 cenários, 343 verificações aprovadas |
| Total da execução final | **557 verificações, sem falhas** |
| Respostas capturadas conferidas com Ajv/AsyncAPI | 300 respostas válidas |
| Variantes canônicas extraídas do contrato para testar o cliente | 29 variantes aceitas |

Foram usados sockets TCP reais, MySQL 8.4 e processos Java reais. Servidores simulados e proxies locais foram usados para produzir respostas incorretas, fragmentadas ou perdidas. A validação com Ajv utilizou o esquema AsyncAPI original, independentemente do validador Java do cliente. As 300 respostas capturadas incluem as variantes canônicas dos servidores simulados; respostas deliberadamente inválidas foram verificadas como casos de rejeição.

Os testes utilizam somente `ep1_reserva_salas_teste`, com portas TCP temporárias. O repositório rejeita outro nome de banco para a bateria e um lock nomeado do MySQL impede que duas baterias limpem o mesmo banco simultaneamente. As tabelas de teste são limpas ao finalizar.

## Problemas reproduzidos e corrigidos

A execução inicial em sequência passou nas 214 verificações funcionais, mas falhou em 8 dos 19 cenários iniciais de comunicação. Esses cenários revelaram os seguintes problemas:

| Problema | Antes | Correção |
|---|---|---|
| Respostas incompletas ou fora do contrato | O cliente aceitava `status: "999"`, mensagem incorreta, login sem token/perfil, consulta sem dados e senha exposta na resposta. | Validação das combinações exatas de operação/status/message, campos obrigatórios, tipos e formatos; rejeição de `password` na resposta. |
| Reenvio depois de resposta inválida | JSON malformado ou resposta de outra operação causavam nova conexão e repetição do pedido. Isso poderia repetir uma alteração já executada. | `ErroProtocoloException` separa erro de protocolo de falha de transporte. Uma resposta completa inválida fecha a conexão e é informada ao usuário, sem reenvio. |
| Resposta genérica de erro recusada | `{"op":"error","status":"400","message":"Requisicao invalida"}` era tratada como falha de conexão. | Aceitação das três mensagens de erro de protocolo definidas no contrato, sem reenvio. |
| Chaves JSON duplicadas | Gson aceitava duas chaves `op` e usava o último valor. Bibliotecas de outros grupos poderiam interpretar o primeiro valor. | Rejeição de nomes repetidos, inclusive em objetos aninhados, com `error` 400. É uma decisão para evitar interpretação ambígua; os grupos devem emitir objetos com chaves únicas. |

Proteções adicionais implementadas e verificadas:

- Chamadas simultâneas no mesmo `ClienteTcp` aguardam a resposta anterior, preservando a correlação posicional.
- Dados extras já pendentes na conexão são rejeitados antes do próximo envio, evitando consumir uma resposta antiga como resposta nova.
- Requisição local acima do limite falha antes de abrir conexão ou transmitir dados.
- UTF-8 inválido e resposta acima do limite são erros de protocolo e não provocam reenvio.
- O console preserva o token depois de 403 e depois de uma resposta 401 inválida. Um 401 válido continua encerrando a sessão conforme o contrato.
- A infraestrutura de testes impede execuções simultâneas no mesmo banco. A investigação inicial teve duas verificações afetadas por baterias concorrentes; elas foram descartadas e repetidas em sequência, antes de registrar os oito casos de falha reais.

## Cobertura

| Área | Casos exercitados |
|---|---|
| Cadastro e conta | Criação, nome/e-mail duplicados, consultas próprias, atualização, e-mail imutável, campos vazios, exclusão, recadastro e senha incorreta. |
| Sessão | Login correto/incorreto, sessão única, token desconhecido, logout repetido, expiração e renovação com relógio controlado, preservação após renomear e reiniciar o serviço. |
| Autorização | Campos extras não mudam autor/perfil; cadastro público não cria administrador; exclusão do último administrador é recusada. |
| MySQL | Commit/rollback, UNIQUE, exclusão de sessão em cascata e leitura atualizada por instâncias distintas do serviço. |
| Concorrência | Dois servidores usando o mesmo banco; 16 cadastros, logins e exclusões simultâneos; duas chamadas simultâneas no mesmo cliente. |
| Framing TCP | Fragmentação, 50 mensagens agrupadas, uma resposta por pedido em ordem, ausência de resposta antes de LF e descarte de mensagem incompleta ao EOF. |
| Limites e codificação | Limite exato de 8.192 bytes, multibyte UTF-8, requisição de 1 MB, resposta acima do limite, bytes inválidos e CRLF rejeitado pela regra de LF. |
| JSON | Sintaxe inválida, tipos incorretos, campos ausentes/null, comentários, chaves repetidas, objetos concatenados, aninhamento extremo, ordem de campos e campos desconhecidos. |
| Cliente e respostas | Status/message incorretos, sucesso sem campos, token/perfil inválidos, op incorreta, erro genérico, resposta fragmentada e dados extras pendentes. |
| Falhas | Timeout, resposta parcial, resposta perdida, erro de persistência 500 e continuidade da conexão após erro. |
| Console | Fluxo das seis operações, entrada redirecionada, manutenção de sessão após senha errada e resposta inválida. |

Timeouts e validade de sessão usam valores reduzidos ou relógio controlado nos testes. Não foi necessário esperar 300 segundos de inatividade ou 30 minutos de expiração reais. Os valores de produção permanecem 300 segundos por conexão e 30 minutos por sessão.

## Limitações do contrato demonstradas

**Resposta de login perdida:** um proxy encaminhou um login válido ao servidor, deixou a sessão ser criada e descartou a resposta antes de entregá-la ao cliente. O cliente reconectou e reenviou; o servidor respondeu 409 porque a sessão já existia. O token original continuou válido no banco, mas o cliente que perdeu a resposta não o recebeu. A implementação segue a regra de reconexão do contrato, que não define recuperação do resultado original. Uma solução exige acordar entre os grupos identificação de requisição e recuperação de resultado, ou recuperação explícita da sessão.

**Resposta de logout perdida:** a repetição recebeu 401 porque o primeiro logout já havia invalidado o token. Esse resultado permite ao cliente concluir a saída conforme a regra 3.10.

**Respostas duplicadas muito atrasadas:** a proteção detecta dados que já estão pendentes antes do próximo envio. Sem identificador de requisição, uma resposta duplicada da mesma operação que chegue somente depois de um novo envio não pode ser distinguida de forma garantida. O servidor próprio foi testado para produzir exatamente uma resposta por pedido.

**Interoperabilidade externa:** estes resultados demonstram conformidade local e resistência aos cenários simulados. A comunicação com os clientes e servidores reais dos outros grupos ainda precisa ser realizada antes da avaliação.

## Como repetir

Na pasta do projeto:

```bash
./mysql-iniciar.sh
./testar.sh
```

O script executa as baterias em sequência. Uma falha retorna código de saída diferente de zero. Arquivos gerados:

- `build/respostas-testes.jsonl`: respostas da bateria funcional.
- `build/respostas-comunicacao.jsonl`: respostas capturadas na bateria adicional.
- `build/relatorio-comunicacao.json`: resultado de cada cenário, contagem e falhas.

Os dados canônicos do teste do cliente estão em `src/test/resources/respostas-ep1.json`, extraídos de `docs/asyncapi.yaml`. Ao mudar a versão do contrato, atualize o catálogo em `ValidacaoResposta.java` e esses dados de teste. A compilação e a execução das baterias Java não dependem de Node/Ajv; a conferência independente com Ajv foi realizada nesta sessão usando as dependências da documentação do protocolo original.
