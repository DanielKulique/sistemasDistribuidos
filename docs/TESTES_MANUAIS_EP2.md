# Roteiro manual da EP-2 na VM

Use um terminal **comum**, sem administrador. O MySQL/MariaDB institucional já deve estar disponível com `ep1_reserva_salas`, as sete tabelas de `sql/schema.sql` e uma conta da aplicação com SELECT/INSERT/UPDATE/DELETE. Nenhum passo exige root do MySQL, Apache ou MongoDB. A senha MySQL fica **somente** em `config/mysql.properties`; não fotografar nem publicar esse arquivo.

Execute `preparar-ep2.cmd --diagnostico`. Só prossiga após “Ambiente EP-2 pronto”, versão real do banco, conexão válida, banco selecionado e conexão fechada. Execute `servidor-ep2.cmd 26000` e, em outro terminal, `cliente-ep2.cmd 127.0.0.1 26000`. Para outra VM, substitua o host pelo IP fornecido pelo professor. Não feche os terminais: eles exibem os JSONs. O roteiro usa a GUI Swing existente.

## Convenções e dados locais

- `TA`, `TJ` e `TM`: tokens reais de admin, joao e maria, obtidos no login. Nos exemplos, substitua essas siglas pelo token correspondente; a GUI faz isso automaticamente. Não são tokens literais válidos.
- `SJ`: senha **da conta de demonstração joao**, escolhida no ato, usando somente letras/números. Deve ser diferente da senha MySQL. Ela não é fornecida neste documento. Ao ver `"password":"SJ"`, use o valor escolhido, não a sigla.
- Para maria, use `maria`, `maria@email.com` e outra senha de demonstração alfanumérica. Seu cadastro repete o teste 2. Abra um cliente separado para cada conta. Uma conta tem apenas uma sessão; não faça dois logins simultâneos com o mesmo e-mail.
- `R`: `room_id` retornado pelo cadastro. Será `"1"` se for a primeira sala do banco; **não limpe nem reinicialize o banco para obter esse número**. Nos demais casos substitua `"1"` nos exemplos pelo valor real.
- `D`: segunda-feira futura, **2026-11-09** na apresentação originalmente planejada. Se essa data já passou, escolha outra segunda futura. Horários usam `America/Sao_Paulo`; não use uma data passada para os testes de reserva.
- Toda resposta usa `op` igual à operação acrescida de `_response`. Status e demais escalares são strings. Observe as respostas reais no terminal, além das mensagens/tabelas na GUI.
- Crie somente contas/salas de demonstração. Se joao ou sala A já existirem, reutilize os dados e anote o estado, ou escolha novos identificadores válidos; cadastro duplicado pode retornar 409. Não remova dados institucionais.

## 1. Login do administrador inicial

- **Pré-condição:** servidor iniciado; nenhum admin anterior existia no primeiro início, ou a conta inicial continua disponível.
- **Usuário:** admin.
- **GUI e dados:** E-mail `admin@admin.com`, senha `admin`, botão **Entrar**.
- **JSON/op:** `{"op":"login","email":"admin@admin.com","password":"admin"}`.
- **Status:** `200`.
- **Resultado:** token e `role:"admin"`; aba **Usuários — ADM** habilitada.
- **Demonstra:** bootstrap existente no Java; o script não cria/resetará admin. Se a senha inicial foi alterada, usar a credencial atual.

## 2. Cadastro público

- **Pré-condição:** cliente sem sessão; joao/e-mail ainda livres.
- **Usuário:** novo joao.
- **GUI e dados:** Usuário `joao`, E-mail `joao@email.com`, senha de aplicação `SJ`; **Cadastrar**.
- **JSON/op:** `{"op":"register","user":"joao","email":"joao@email.com","password":"SJ"}`.
- **Status:** `201`.
- **Resultado:** conta criada como `user`; login continua sendo uma ação separada.
- **Demonstra:** cadastro persistente e perfil público sem privilégio administrativo.

## 3. Login e leitura da própria conta

- **Pré-condição:** joao cadastrado e sem outra sessão ativa.
- **Usuário:** joao.
- **GUI e dados:** **Entrar** com seu e-mail/senha; em **Minha conta**, **Consultar cadastro**.
- **JSON/op:** `{"op":"login","email":"joao@email.com","password":"SJ"}` e `{"op":"read_user","token":"TJ"}`.
- **Status:** `200` em ambos.
- **Resultado:** nome, e-mail, perfil `user` e criação; nenhuma resposta retorna senha/hash/salt.
- **Demonstra:** autenticação, sessão e leitura pelo token.

## 4. Espaços não são removidos dos campos do protocolo

- **Pré-condição:** sem sessão para tentativas de login; sala disponível para a consulta de horário.
- **Usuário:** joao/admin no login; usuário autenticado para disponibilidade.
- **GUI e dados:** no login, senha `SJ` **com um espaço final**; outra tentativa com e-mail ` admin@admin.com` **com espaço inicial**. Após o teste 8, em **Salas e agenda → Consultar disponibilidade**, inserir fim `08:20 ` com espaço final.
- **JSON/op:** login com `"password":"SJ "`; login com `"email":" admin@admin.com"`; `{"op":"check_availability","token":"TJ","room_id":"1","date":"2026-11-09","start_time":"07:30","end_time":"08:20 "}`.
- **Status:** `400` nas três tentativas.
- **Resultado:** formato recusado; depois repetir com os dados corretos para continuar.
- **Demonstra:** validação fiel ao contrato, sem trim silencioso de credenciais/horários. As senhas de exemplo são da aplicação, nunca do MySQL.

## 5. Operação administrativa com perfil comum

- **Pré-condição:** joao autenticado e com perfil user.
- **Usuário:** joao.
- **GUI e dados:** **Salas e agenda → Cadastrar sala**; nome `Teste`, capacidade `6`, localização `Bloco B`, um slot `2M1`; recursos vazios.
- **JSON/op:** `{"op":"create_room","token":"TJ","name":"Teste","capacity":"6","location":"Bloco B","resources":[],"slots":["2M1"]}`.
- **Status:** `403`.
- **Resultado:** sala não criada, mensagem `Permissao insuficiente`.
- **Demonstra:** autorização no servidor; a aba de administração de usuários também fica desabilitada para user.

## 6. Promoção e rebaixamento com sessão ativa

- **Pré-condição:** admin e joao em clientes separados; ambos autenticados.
- **Usuário:** admin altera joao; joao observa.
- **GUI e dados:** **Usuários — ADM → Atualizar usuário**; alvo `joao`, novo usuário/senha vazios, perfil `admin`. No cliente joao, **Consultar cadastro**, depois listar usuários. Em seguida admin repete com perfil `user`; antes de atualizar a tela de joao, tente listar usuários na aba ainda visível.
- **JSON/op:** `{"op":"admin_update_user","token":"TA","target_user":"joao","user":"","password":"","role":"admin"}`; `admin_list_users` com TJ; depois atualização com `"role":"user"` e nova listagem com TJ.
- **Status:** promoção/rebaixamento `200`; listagem após promoção `200`, após rebaixamento `403`.
- **Resultado:** o mesmo token ganha/perde autorização imediatamente. Consultar cadastro atualiza também a aba visível.
- **Demonstra:** whitelist reconstruída com o perfil atual, sem exigir logout/login.

## 7. Proteção do último administrador

- **Pré-condição:** joao novamente user; admin é o único administrador (confira a listagem).
- **Usuário:** admin.
- **GUI e dados:** atualizar o próprio alvo `admin` para perfil `user`; depois tentar **Excluir usuário** com alvo `admin`, confirmando o diálogo.
- **JSON/op:** `admin_update_user` com `target_user:"admin",role:"user"` e campos de atualização restantes vazios; `{"op":"admin_delete_user","token":"TA","target_user":"admin"}`.
- **Status:** `403` em ambos.
- **Resultado:** `Nao e possivel remover o ultimo administrador`; conta preservada.
- **Demonstra:** proteção de governança. Se houver outros admins, a pré-condição não é satisfeita; não remova contas reais para forçá-la.

## 8. Cadastro da sala A

- **Pré-condição:** nome A livre.
- **Usuário:** admin.
- **GUI e dados:** **Salas e agenda → Cadastrar sala**; nome `A`, capacidade `6`, localização `Bloco B`; recursos vazios; na coluna Seg, marcar M1–M6 e T1.
- **JSON/op:** `{"op":"create_room","token":"TA","name":"A","capacity":"6","location":"Bloco B","resources":[],"slots":["2M1","2M2","2M3","2M4","2M5","2M6","2T1"]}`.
- **Status:** `201`.
- **Resultado:** `Sala cadastrada com sucesso`, com room_id R.
- **Demonstra:** cadastro administrativo e grade UTFPR. Anote R e use esse número daqui em diante.

## 9. Cadastro vazio de sala

- **Pré-condição:** admin autenticado.
- **Usuário:** admin.
- **GUI e dados:** **Cadastrar sala**, deixar campos vazios/sem slots e confirmar.
- **JSON/op:** `{"op":"create_room","token":"TA","name":"","capacity":"","location":"","resources":[],"slots":[]}`.
- **Status:** `400`.
- **Resultado:** sala não criada.
- **Demonstra:** campos obrigatórios e grade não vazia.

## 10. Listagem das salas

- **Pré-condição:** sala A criada.
- **Usuário:** joao.
- **GUI e dados:** **Salas e agenda → Listar salas**; campos opcionais vazios.
- **JSON/op:** `{"op":"list_rooms","token":"TJ"}`.
- **Status:** `200`.
- **Resultado:** tabela com A, capacidade 6, localização, situação e slots; `count` corresponde à lista. Sem período, `available` não é retornado.
- **Demonstra:** consulta permitida a qualquer autenticado.

## 11. Atualização da sala

- **Pré-condição:** sala A existente.
- **Usuário:** admin.
- **GUI e dados:** **Atualizar sala**; Número R, localização `Bloco B`, recursos `projetor`; demais escalares vazios e nenhum slot marcado.
- **JSON/op:** `{"op":"update_room","token":"TA","room_id":"1","name":"","capacity":"","location":"Bloco B","resources":["projetor"],"room_status":"","slots":[]}`.
- **Status:** `200`.
- **Resultado:** recursos atualizados; nome/capacidade/grade preservados. **Consultar sala e agenda** confirma os valores.
- **Demonstra:** atualização parcial dos escalares/slots; recursos são sempre a lista completa.

## 12. Reserva válida

- **Pré-condição:** sala A ativa, R correto, segunda futura D, intervalo livre.
- **Usuário:** joao.
- **GUI e dados:** **Reservas → Reservar sala**; R, D, início `07:30`, fim `08:20`, assunto `python`, participantes `5`.
- **JSON/op:** `{"op":"create_reservation","token":"TJ","room_id":"1","date":"2026-11-09","start_time":"07:30","end_time":"08:20","topic":"python","participants":"5"}`.
- **Status:** `201`.
- **Resultado:** `Reserva realizada com sucesso`, com reservation_id. Anote-o.
- **Demonstra:** reserva persistente dentro da capacidade/funcionamento.

## 13. Limites exatos dos slots UTFPR

- **Pré-condição:** sala A com 2M1/2M2 habilitados.
- **Usuário:** joao.
- **GUI e dados:** **Consultar disponibilidade** para D, `08:20`–`09:10`, sem uma reserva nesse período.
- **JSON/op:** `{"op":"check_availability","token":"TJ","room_id":"1","date":"2026-11-09","start_time":"08:20","end_time":"09:10"}`.
- **Status:** `200`.
- **Resultado:** `available:"true"` para o segundo slot livre.
- **Demonstra:** 2M1 = segunda 07:30–08:20; 2M2 = 08:20–09:10. Não são intervalos arbitrários de uma hora. Adjacência não é sobreposição.

## 14. Dados inválidos de reserva

- **Pré-condição:** mesma sala/data; usar dados válidos do teste 12, alterando **um campo por tentativa**.
- **Usuário:** joao.
- **GUI e dados:** **Reservar sala**: (a) `07:30`–`08:30`; (b) `08:30`–`09:30`; (c) `07:30`–`08:20 ` com espaço final; (d) assunto vazio; (e) participantes vazio.
- **JSON/op:** `create_reservation` do teste 12, com cada alteração correspondente em `start_time/end_time/topic/participants`.
- **Status:** `400` em todas.
- **Resultado:** nenhuma nova reserva. Essas validações precedem a checagem de conflito.
- **Demonstra:** limites oficiais, campos obrigatórios e ausência de normalização indevida.

## 15. Conflito de reserva

- **Pré-condição:** reserva de joao do teste 12 ainda existe.
- **Usuário:** maria (cadastre-a como no teste 2 e faça login em outro cliente).
- **GUI e dados:** **Reservar sala**; mesma R/D, `07:30`–`08:20`, assunto `abacaxi`, participantes `4`.
- **JSON/op:** `{"op":"create_reservation","token":"TM","room_id":"1","date":"2026-11-09","start_time":"07:30","end_time":"08:20","topic":"abacaxi","participants":"4"}`.
- **Status:** `409`.
- **Resultado:** mensagem exata `Sala ja reservada no periodo solicitado`; permanece apenas a reserva anterior.
- **Demonstra:** proteção contra dupla reserva no mesmo intervalo.

## 16. Agenda e disponibilidade

- **Pré-condição:** reserva de joao existente.
- **Usuário:** joao.
- **GUI e dados:** **Salas e agenda → Consultar sala e agenda**, R/D; depois **Consultar disponibilidade**, R/D, `07:30`–`08:20`.
- **JSON/op:** `{"op":"read_room","token":"TJ","room_id":"1","date":"2026-11-09"}` e `check_availability` com o período reservado.
- **Status:** `200` em ambos.
- **Resultado:** agenda marca 2M1 `indisponivel`, identifica joao e mostra seu reservation_id; disponibilidade `"false"`.
- **Demonstra:** disponibilidade calculada e agenda derivada dos slots/reservas.

## 17. Visibilidade: terceiro, dono e admin

- **Pré-condição:** três clientes autenticados; reserva de joao existente.
- **Usuário:** maria, joao e admin.
- **GUI e dados:** repetir **Consultar sala e agenda** com R/D nos três clientes; tentar **Reservas → Consultar reserva** com seu ID.
- **JSON/op:** `read_room` com TM/TJ/TA; `{"op":"read_reservation","token":"TM","reservation_id":"ID_REAL"}` e equivalentes com TJ/TA.
- **Status:** agenda `200` para todos; leitura direta `403` para maria, `200` para joao/admin.
- **Resultado:** todos veem o responsável `user:"joao"`; somente dono/admin veem reservation_id na agenda.
- **Demonstra:** privacidade do identificador e autorização por propriedade/perfil.

## 18. Inativar sala

- **Pré-condição:** sala A ativa com reserva futura.
- **Usuário:** admin.
- **GUI e dados:** **Atualizar sala**; R, situação `inactive`, recursos `projetor`, demais campos/slots vazios.
- **JSON/op:** `update_room` do teste 11, com `room_status:"inactive"` e localização vazia.
- **Status:** `200`.
- **Resultado:** `read_room` mostra `inactive`; reserva anterior continua existindo.
- **Demonstra:** inativação sem cancelamento dos dados existentes.

## 19. Nova reserva em sala inativa

- **Pré-condição:** sala A inativa; segundo slot ainda livre.
- **Usuário:** maria.
- **GUI e dados:** **Reservar sala**; R/D, `08:20`–`09:10`, assunto `estudo`, participantes `4`.
- **JSON/op:** `create_reservation` com TM e os dados acima.
- **Status:** `409`.
- **Resultado:** `Sala indisponivel para reserva`; nenhuma reserva criada.
- **Demonstra:** bloqueio por situação, mesmo em horário livre. Depois reative com admin, repetindo o teste 18 com `active`.

## 20. Excluir sala com reserva futura

- **Pré-condição:** reserva de joao ainda futura/não encerrada; sala reativada.
- **Usuário:** admin.
- **GUI e dados:** **Salas e agenda → Excluir sala**; R; confirmar exclusão.
- **JSON/op:** `{"op":"delete_room","token":"TA","room_id":"1"}`.
- **Status:** `409`.
- **Resultado:** `Sala possui reservas ativas`; sala e reserva preservadas.
- **Demonstra:** proteção de dependências. Opcional: reduzir capacidade para 4 também retorna 409 com essa mesma mensagem, exceção documentada da pendência oficial 2.8.0.

## 21. Exclusão do usuário e suas reservas

- **Pré-condição:** joao é uma conta de demonstração user, com a reserva do teste 12. Execute primeiro o teste 22 se quiser demonstrar a persistência dessa reserva antes de excluí-la.
- **Usuário:** admin exclui joao; cliente joao observa.
- **GUI e dados:** **Usuários — ADM → Excluir usuário**, alvo `joao`, confirmar; no cliente joao, **Consultar cadastro**; no admin, consultar agenda novamente.
- **JSON/op:** `{"op":"admin_delete_user","token":"TA","target_user":"joao"}`; `read_user` com TJ; `read_room` com TA/R/D.
- **Status:** exclusão `200`, token antigo `401`, agenda `200`.
- **Resultado:** usuário/sessão e todas as reservas associadas são removidos; 2M1 volta a livre se a sala estiver ativa. Nenhum dado de outro usuário é removido.
- **Demonstra:** exclusão referencial e invalidação de sessão. Teste equivalente pela própria conta: **Minha conta → Excluir conta**, senha atual, `delete_user` → 200; use outra conta de demonstração para não repetir a exclusão de joao.

## 22. Persistência após reiniciar

- **Pré-condição:** executar preferencialmente antes do teste 21, com sala e reserva existentes. Para simplificar a apresentação, faça logout dos clientes antes de parar o servidor.
- **Usuário:** admin e joao.
- **GUI e dados:** **Sair da sessão**, fechar clientes; Ctrl+C no terminal do servidor; `servidor-ep2.cmd 26000`; reabrir clientes e entrar; **Listar salas**, **Consultar sala e agenda**, **Reservas → Listar reservas** (`scope:mine` para joao).
- **JSON/op:** `logout`, depois `login`, `list_rooms`, `read_room`, `list_reservations`.
- **Status:** `200` para essas operações.
- **Resultado:** IDs, sala, recursos, grade e reserva continuam iguais no MySQL; nenhum admin/dado foi resetado. Repetir `preparar-ep2.cmd` também preserva os dados/configuração.
- **Demonstra:** persistência entre processos e idempotência. Se mantiver o token em outro cliente sem logout, a sessão também permanece válida até logout/expiração de 30 minutos; não tente um segundo login da mesma conta nesse período.

## 23. Logout

- **Pré-condição:** sessão válida (maria, por exemplo).
- **Usuário:** maria.
- **GUI e dados:** **Minha conta → Sair da sessão**.
- **JSON/op:** `{"op":"logout","token":"TM"}`.
- **Status:** `200`.
- **Resultado:** GUI retorna à tela de acesso; token antigo deixa de autorizar operações (`401`, se reutilizado em um cliente de protocolo).
- **Demonstra:** encerramento explícito da sessão e remoção de privilégios do token.

## 24. JSONs durante toda a apresentação

- **Pré-condição:** manter os terminais dos launchers abertos durante os testes anteriores.
- **Usuário:** todos, conforme cada operação.
- **GUI e dados:** executar cadastro/login/leitura/atualização/exclusão de conta, administração, sala, disponibilidade, reserva, erros e logout. Para `update_user`, use **Minha conta → Salvar alterações**, com um novo nome de demonstração, e depois consulte seu cadastro; não renomeie joao antes dos testes que dependem desse nome.
- **JSON/op:** cada pedido aparece como `C -> S {...}` e a resposta como `S -> C {...}`; atualização própria usa `{"op":"update_user","token":"TM","user":"marianova","password":""}`.
- **Status:** os códigos de cada caso acima; `update_user` válido retorna `200`.
- **Resultado:** logs do protocolo permanecem visíveis, inclusive erros. São os logs já existentes da aplicação, que incluem os campos enviados (inclusive a senha de login da **aplicação**); a senha MySQL nunca faz parte desse protocolo.
- **Demonstra:** comunicação TCP/JSON observável, com Swing e contrato inalterados. Não publique capturas com tokens/credenciais de contas reais.

## Bateria automatizada separada

Este roteiro manual não dispara a bateria automatizada. `testar.ps1`, `testar.sh` e Ant usam exclusivamente `config/mysql-test.properties`/`ep1_reserva_salas_teste` para testes MySQL e recusam o banco principal. As baterias apagam **dados de teste**; não execute testes contra `ep1_reserva_salas`. Se o arquivo/banco de testes não existir, solicite a preparação separada descrita no README. Os launchers não criam contas/bancos/permissões de teste nem pedem elevação.

O teste simples `TesteConexaoMySql` e seu modo `--verificar-ep2` são diagnósticos sem alterações de registros, adequados para o banco principal. Diferem das baterias de integração que criam/removem fixtures no banco separado.
