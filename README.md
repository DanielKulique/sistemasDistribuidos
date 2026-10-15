# EP-1 — Reserva de salas sem banco de dados

Cliente gráfico Swing e servidor Java TCP. Implementa cadastro, login, consulta, atualização, exclusão e logout conforme o contrato v2.4.0. Usa somente JDK 17+ e Gson (incluído em `lib/`). Não precisa de MongoDB, MySQL, Docker, instalação de serviços ou credenciais de banco.

## Executar

Na pasta do projeto, abra dois terminais. Primeiro, inicie o servidor:

```powershell
.\servidor.ps1 5000
```

Depois, abra a interface gráfica:

```powershell
.\cliente.ps1 127.0.0.1 5000
```

No Git Bash/Linux/macOS, use `./servidor.sh 5000` e `./cliente.sh 127.0.0.1 5000`. O servidor fica no terminal; o cliente abre uma janela Swing do Java padrão. Para o modo console: `./cliente.sh --console 127.0.0.1 5000`.

Na primeira execução, nenhum arquivo de configuração é necessário. Após uma operação, o servidor cria automaticamente `data/ep1.json`. Para escolher outro arquivo: `.\servidor.ps1 5000 data/outros-dados.json` (ou o equivalente `.sh`). Os scripts usam a pasta do projeto como diretório de trabalho.

Exemplo de cadastro na GUI: usuário `joao`, e-mail `joao@email.com` e senha `senha123`. Depois clique em Entrar usando o mesmo e-mail e senha. Deixe os campos de atualização vazios para manter seus valores atuais. A exclusão exige confirmação. Ao fechar autenticado, a janela envia logout.

Na GUI, informe o IP da máquina que executa o servidor Java e a mesma porta TCP. Se cliente e servidor estão na mesma máquina, use `127.0.0.1`. O erro Connection refused indica que não há servidor escutando nesse endereço/porta.

No Windows, os scripts usam as ferramentas do JDK encontrado por `javac`, evitando conflito com Java antigo. Se a política local permitir execução por processo, pode usar `powershell -ExecutionPolicy Bypass -File .\servidor.ps1 5000` e o equivalente para o cliente. Os scripts executam classes compiladas para evitar bloqueio do JAR ao abrir cliente e servidor simultaneamente.

## Dados locais e concorrência

Usuários e sessões ficam em `data/ep1.json`, em UTF-8. As senhas são hashes PBKDF2 com salt individual. Os dados sobrevivem ao fechamento e reinício do servidor. A pasta `data/` é ignorada pelo Git, exceto `.gitkeep`.

Cada operação bloqueia um arquivo `.lock`, lê o estado mais recente, aplica e valida as regras e grava um arquivo temporário. Após sincronizar o conteúdo, substitui o JSON original por movimento atômico. Uma trava adicional coordena threads no mesmo processo. Dois servidores na mesma máquina podem usar o mesmo arquivo sem sobrescrever alterações simultâneas. O sistema de arquivos deve oferecer travas e movimento atômico; prefira disco local, fora de pastas de rede/sincronização. Não há coordenação de servidores em máquinas diferentes por cópias do arquivo.

Se o JSON existente estiver vazio, corrompido ou inconsistente, a aplicação apresenta erro e preserva o arquivo. Ela não apaga os dados nem reinicia silenciosamente o cadastro. Erros antes da substituição não alteram o arquivo original. A proteção não substitui backup e não oferece garantia adicional contra falha física ou falta de energia.

O repositório valida unicidade de usuário, e-mail, ID, token e sessão por usuário, além das referências das sessões. A exclusão pelo serviço remove a sessão da conta. Evite editar o arquivo manualmente enquanto o servidor estiver rodando. Arquivos `.lock` podem permanecer no disco após o encerramento; as travas do sistema operacional são liberadas automaticamente.

Dados anteriores do MongoDB/MySQL não são importados automaticamente: esta versão inicia um conjunto de cadastros separado. Não é necessário excluir dados nem configurações antigos para executar. Pare o servidor da versão anterior antes de iniciar o novo na mesma porta.

## Mensagens no terminal

As requisições e respostas JSON completas aparecem nos terminais de cliente e servidor como `C -> S` e `S -> C`, inclusive reenvios. Conexões e falhas também são registradas. UTF-8 inválido é registrado em Base64 e mensagens acima do limite têm o prefixo registrado. As chamadas de rede da GUI ocorrem em segundo plano, sem bloquear a janela.

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

```powershell
.\build.ps1
.\testar.ps1
```

No Bash: `./build.sh` e `./testar.sh`. Compilar não baixa dependências. Para empacotar o JAR, use build sem a opção de somente classes.

Os testes usam exclusivamente `build/ep1-teste.json` e rejeitam outros caminhos para impedir limpeza dos dados da aplicação. Uma trava de bateria impede execuções simultâneas. Os testes adicionais de arquivo criam pastas temporárias isoladas dentro de `build/`. A bateria gráfica exige uma sessão com ambiente gráfico; não há dependência de serviço externo ou JUnit.

Resultado: **613 verificações aprovadas**, incluindo 11 de persistência em arquivo, 214 funcionais, 343 de comunicação e 45 da GUI. Veja o [relatório sem banco](docs/RELATORIO_TESTES_ARQUIVO.md). Os relatórios de MySQL/MongoDB são históricos.

Fontes em `src/main/java/` e `src/test/java/`; dependência e licença em `lib/`; resultados e classes em `build/`. Para executar classes sem scripts no Windows: `java -cp 'build/classes;lib/*' br.edu.utfpr.sd.ep1.Cliente 127.0.0.1 5000`. Use Java 17+; em Linux/macOS, o separador do classpath é `:`.
