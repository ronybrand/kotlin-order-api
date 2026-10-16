# Arquitetura técnica: kotlin-order-api

Explicação técnica de como a API de pedidos em Kotlin é montada. O contrato vem do `fastapi-order-api`; aqui está o que é próprio desta implementação. Decisões formais em [`adr/`](adr/README.md) (0001 a 0006).

## 1. Visão geral

Uma imagem, dois processos: a API (`ApiMain`) e o worker de notificação (`WorkerMain`, `./gradlew runWorker`). Kotlin 2.4 em JVM 25, Ktor 3.6 (Netty), Exposed 1.5 sobre JDBC com HikariCP, Flyway para migrations, `kotlinx.serialization`, corrotinas, `java-jwt`, cliente AMQP do RabbitMQ, Angus Mail. `allWarningsAsErrors` ligado.

## 2. Camadas

```
domain       Customer, Order, EmailAddress, OrderStatusChanged: data classes imutáveis, sem I/O
app          CustomerService, OrderService, busca e Ports.kt (as interfaces que os casos de uso consomem)
persistence  Exposed: PostgresStore, PgCustomers, PgOrders, plano e SQL de busca, Tables
http         Ktor: ApiModule, Endpoints, Envelope (erros), Plugins, KeyedLimiter, Wire (DTOs)
messaging    Rabbit, Processor, DeliveryHandler, EmailNotifier, SmtpMailer
auth, config, error
```

A regra de dependência (ADR 0001) é **verificada por teste**: `ArchitectureTest` lê os `import` de cada pacote e falha se `domain` importar `app`, `persistence`, `http`, `messaging` ou Ktor. É o que as outras stacks da família deixam como convenção.

## 3. Domínio (ADR 0002)

Cliente, pedido e item são `data class` imutáveis. Cada operação (`addItem`, `confirm`, `replace`) devolve o novo estado; uma regra violada lança `AppException` antes de qualquer cópia, então nunca existe um pedido pela metade. `confirm` e `cancel` devolvem `StatusChange(order, previous)`, porque o evento precisa do status anterior. O `id` é gerado pela aplicação (`UUID.randomUUID()`), o que evita `UUID?` por toda parte; o `gen_random_uuid()` do schema fica como rede de segurança. O repositório devolve a entidade com a `version` seguinte em vez de mutá-la.

## 4. Fluxo de uma requisição

1. Plugins do Ktor: id de requisição, cabeçalhos de segurança, CORS, limite de corpo (`ktor-server-body-limit`), rate limit (`KeyedLimiter`) e `StatusPages`, que converte `AppException` e falhas inesperadas no envelope `{message, code, params, request_id}`.
2. Autenticação Bearer RS256, só com chave pública (ADR 0005); RBAC binário (ADR 0006).
3. O endpoint chama o caso de uso por `io { ... }` (`withContext(Dispatchers.IO)`): as portas são **bloqueantes de propósito**, porque o JDBC é; o código não finge ser assíncrono.
4. O caso de uso roda dentro de `Store.inTransaction`.

## 5. Persistência

- Exposed com a DSL, não DAO. Uma chamada de repositório dentro de `inTransaction` entra na transação aberta na thread; fora dela, cada chamada abre a sua.
- `lockForUpdate` e `lockForShare` no cliente: excluir cliente toma o lock exclusivo na mesma transação da checagem "tem pedidos?", e criar pedido toma o compartilhado, então as duas operações não se intercalam.
- Escrita com `version`: `update` falha com `CONFLICT-00` se a versão mudou.
- SQLSTATE (inclusive `40001` e `40P01`) traduzidos para códigos do catálogo.
- Busca em duas etapas: `SearchPlan` (validado, sem SQL) e `SearchSql` (gera o SQL com parâmetros). Campos vêm de uma lista por entidade.
- Migrations do Flyway; a imagem usa o driver PostgreSQL em runtime.

## 6. Mensageria

`Rabbit` publica `order.status.changed`; o `Processor` decide ack, retry ou DLQ; `DeliveryHandler` isola a decisão do cliente AMQP para testá-la sem broker; `SmtpMailer` envia. Sem outbox (ADR 0003): um crash entre o commit e a publicação perde o evento.

## 7. Testes

- `test`: unitários e HTTP com `ktor-server-test-host`, sem banco nem rede, usando `MemStore`. Inclui `OpenApiTest` (deriva do contrato), `ArchitectureTest` e `SearchPlanTest`.
- `integrationTest` (suíte separada, roda no `check`): PostgreSQL **embarcado** via `embedded-postgres` em vez de Testcontainers (ADR 0004), então os testes de banco rodam sem Docker; GreenMail para SMTP; RabbitMQ por Testcontainers, ignorado quando não há Docker.
- Kover com piso de 80%, excluindo só `ApiMain` e `WorkerMain`.
- ktlint e detekt no mesmo gate: `./gradlew ktlintCheck detekt check koverVerify`.

## 8. Build, imagem e CI

O Dockerfile resolve dependências numa camada própria, descobre os módulos necessários com `jdeps` e monta um runtime reduzido com `jlink`, copiado para um Alpine fixado por digest, sem root e sem toolchain. O CI mede o tamanho da imagem e roda um smoke ponta a ponta (`scripts/smoke.sh`). Workflows: CI, CodeQL, revisão de dependências, supply chain (SBOM CycloneDX varrido) e auto merge do Dependabot.

Como o Gradle não entrega o grafo ao GitHub sozinho, o grafo resolvido é enviado ao GitHub. Isso expõe advisories do classpath das ferramentas de build (detekt, CycloneDX, ktlint). Em `build.gradle.kts`, o bloco `buildscript { constraints }` fixa `jackson`, `freemarker` e a linha do `logback` do ktlint em versões corrigidas, sem alterar o que vai na imagem.

### Estado da main

Na consulta de hoje (16/10/2026), os três workflows do último push na `main` (CI, CodeQL, Supply chain) estão verdes. A execução vermelha que apareceu é um job do **Dependabot** (`gradle ... logback-core - Update #1631716015`), cujo log exige acesso de escrita ao repositório e não foi lido; a causa não foi investigada. Não é a `main`.

## 9. Trade-offs

| Escolha | Ganho | Custo |
|---|---|---|
| Portas bloqueantes + `Dispatchers.IO` | honesto com o JDBC, simples | threads ocupadas em vez de I/O não bloqueante |
| Agregados imutáveis | sem estado parcial, `id` não anulável | cópias a cada operação |
| `ArchitectureTest` | a direção das dependências é imposta | lê texto de `import`, não o grafo de bytecode |
| PostgreSQL embarcado | testes de banco sem Docker | binários baixados uma vez, versão fixa do servidor |
| `jlink` na imagem | runtime pequeno | lista de módulos extras precisa ser mantida |
| Sem outbox | simples | evento pode se perder |
