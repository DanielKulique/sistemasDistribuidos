> Histórico anterior à persistência em arquivo. Para a versão atual, consulte RELATORIO_TESTES_ARQUIVO.md.

# Validação da migração para MongoDB

Data: 14/10/2026.

- Compilação com JDK 21, alvo Java 17, incluindo testes: aprovada.
- MongoDB 8.0.5 standalone temporário, isolado em 127.0.0.1:27018.
- Bateria funcional: 214 verificações, 63 respostas registradas.
- Comunicação: 33 casos, 343 verificações, zero falhas.
- Total: **557 verificações aprovadas**.

Comando: `./testar.ps1 build/mongo-validacao.properties`. A configuração de validação usa exclusivamente `ep1_reserva_salas_teste`. Foram testados persistência entre instâncias, rejeição de e-mail e sessão duplicados sem gravação parcial, exclusão de conta/sessão, servidores concorrentes, 16 clientes simultâneos, bloqueio de uma segunda bateria, falhas de persistência, TCP e console.

Não foi repetida a validação externa com Ajv do relatório histórico. Os testes Java validaram o protocolo e suas variantes canônicas.

A instância usada na validação foi encerrada. Não foi encontrada uma instância respondendo em localhost:27017 durante a migração; o endereço do MongoDB do usuário deve ser configurado em config/mongo.properties.

Limites: estado em um documento (16 MiB), sem teste de volume até esse limite. A validação foi em standalone local; não incluiu autenticação remota ou teste de interrupção abrupta do banco. As regras de integridade são do repositório, não índices separados. A trava de testes exige remoção manual após encerramento abrupto, conforme o README.
