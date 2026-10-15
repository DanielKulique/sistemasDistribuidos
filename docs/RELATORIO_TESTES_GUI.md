> Histórico anterior à persistência em arquivo. Para a versão atual, consulte RELATORIO_TESTES_ARQUIVO.md.

# Validação da interface Swing e dos logs JSON

Data: 14/10/2026. JDK 21, alvo Java 17, Windows.

- Compilação de produção e testes sem avisos.
- Regressão MongoDB 8.0.5 standalone: 214 verificações funcionais e 343 de comunicação, sem falhas.
- Interface Swing: 45 verificações aprovadas usando componentes na EDT e servidor TCP real com repositório em memória isolado.
- Total: **602 verificações aprovadas**.

O teste gráfico cobre cadastro, login incorreto/correto, consulta, atualização, exclusão com senha incorreta/correta, confirmação de exclusão, logout, novo login com senha atualizada e logout ao fechar a janela. Verifica também a fixação do servidor durante uma sessão e a presença dos JSONs de envio e resposta das seis operações nos logs de cliente e servidor.

As chamadas de rede usam SwingWorker; as alterações da tela ocorrem na EDT. A inspeção visual das telas de acesso e de conta foi feita a partir de renderizações dos componentes Swing. Não houve teste manual de todas as combinações de redimensionamento, DPI ou sistemas operacionais.

Comando completo: `./testar.ps1 build/mongo-validacao.properties`, com MongoDB de testes isolado em localhost:27018. Resultados gráficos em `build/gui-acesso.png` e `build/gui-conta.png`; logs em `build/gui-json-cliente.log` e `build/gui-json-servidor.log`. Esses arquivos são gerados localmente e ignorados pelo Git.

O console permanece disponível com `--console`; seus testes anteriores continuam aprovados. A GUI exige uma sessão gráfica. Não foi adicionado um painel gráfico ao servidor.
