# Validação da EP-1 sem banco de dados

Data: 14/10/2026. Windows, JDK 21, alvo Java 17. Comando: `./testar.ps1`, sem serviço de banco e sem driver de banco no classpath.

| Bateria | Verificações |
|---|---:|
| Persistência em arquivo | 11 |
| Funcional e persistência | 214 |
| Comunicação (33 cenários) | 343 |
| Interface Swing | 45 |
| Total | **613** |

A bateria de arquivo verifica inicialização sem arquivo, persistência entre instâncias, ausência de gravação após exceção, rejeição de cinco formatos inválidos sem sobrescrever os dados e 40 incrementos concorrentes entre dois processos Java independentes.

A bateria funcional valida cadastro, login, consulta, atualização, logout, exclusão, expiração de sessão, rejeição de duplicatas sem gravação parcial, novas instâncias, concorrência entre dois servidores e console. A de comunicação mantém os testes adversos de TCP e protocolo, 16 clientes concorrentes e proteção contra limpeza por outra bateria. A GUI verifica as seis operações e logs JSON nos dois lados.

Os testes não utilizam `data/ep1.json`: usam `build/ep1-teste.json` e subpastas temporárias de build. Travas de arquivo são liberadas pelo sistema operacional no encerramento do processo. Os resultados não cobrem falta de energia, disco cheio, sistemas de arquivos de rede ou todas as configurações de DPI/sistemas operacionais.

A substituição atômica exige suporte do sistema de arquivos; caso indisponível, a gravação falha sem substituir intencionalmente o arquivo original. Não há migração automática de cadastros de versões com MongoDB/MySQL.
