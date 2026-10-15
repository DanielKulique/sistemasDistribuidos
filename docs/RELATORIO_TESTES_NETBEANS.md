# Validação da execução NetBeans

Data: 15/10/2026. JDK 21, alvo Java 17, Windows.

- Projeto Ant Free-Form configurado para Run/F6, Build, Clean, Test Project e Run File.
- XML do projeto validado contra os esquemas oficiais distribuídos na instalação local do Apache NetBeans (general/1 e java/5).
- Comando executado com o Ant da instalação local: `ant jar test`.
- Resultado: **BUILD SUCCESSFUL**, 613 verificações anteriores aprovadas, mais o teste integrado de Aplicacao.
- O teste integrado abriu uma janela Swing visível e iniciou servidor TCP em porta temporária. Pelo botão Cadastrar, recebeu resposta 201, conferiu a persistência em arquivo isolado e fechou a janela, encerrando o servidor. As requisições/respostas dos dois lados foram impressas na saída do Ant.

A configuração do Run aponta para o mesmo target Ant e a mesma classe de entrada usados na validação. A execução pelo botão F6 na interface do NetBeans não foi automatizada; foram executados os targets com o Ant incluído na IDE. A inspeção dos esquemas utiliza a definição do [projeto Free-Form do Apache NetBeans](https://github.com/apache/netbeans/blob/master/java/ant.freeform/src/org/netbeans/modules/ant/freeform/resources/freeform-project-general.xsd).

As pastas temporárias de testes ficam em build/. Os testes não alteram data/ep1.json. Nenhum servidor de banco é necessário.
