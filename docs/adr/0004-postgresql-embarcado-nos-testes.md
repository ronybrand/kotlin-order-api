# ADR 0004: PostgreSQL embarcado nos testes de integração

## Contexto

Os testes de persistência precisam de um PostgreSQL de verdade: índices únicos parciais, `FOR UPDATE`/`FOR SHARE`, `ILIKE ... ESCAPE` e os códigos SQLSTATE traduzidos para erros do catálogo não existem em um banco em memória como H2. As outras implementações usam Testcontainers, que exige um daemon Docker.

## Decisão

Os testes de persistência e de HTTP ponta a ponta usam o PostgreSQL embarcado de `io.zonky.test:embedded-postgres`: os binários vêm do Maven Central, o servidor sobe uma vez por JVM e cada classe de teste ganha um banco novo e migrado. O teste do broker (RabbitMQ), que não tem equivalente embarcado, usa Testcontainers e é ignorado quando não há Docker.

## Alternativas

- **Testcontainers para tudo**: igual às outras stacks, mas nenhum teste de banco roda numa máquina sem Docker em execução, e o ciclo local fica mais lento e frágil.
- **H2 em modo PostgreSQL**: rápido, mas aceitaria SQL que o PostgreSQL recusa e esconderia exatamente as garantias que os testes existem para provar.

## Consequências

O servidor é o PostgreSQL real, na versão dos binários (18 hoje), não a versão de produção; se a produção ficar para trás, vale um job de CI com a versão exata. O primeiro teste baixa ~25 MB uma vez (cache do Gradle).
