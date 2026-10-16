# kotlin-order-api

API de pedidos em Kotlin, implementando a especificação do [`fastapi-order-api`](https://github.com/ronybrand/fastapi-order-api) (`DOMAIN.md` e catálogo de erros daquele projeto) com as convenções da linguagem: Ktor, Exposed, `kotlinx.serialization`, agregados imutáveis (`data class`) e interfaces pequenas declaradas por quem as consome.

O mesmo domínio já existe em FastAPI, Nest, .NET, Spring, Go e PHP; este repositório existe para comparar a mesma especificação em mais uma stack, com o mesmo contrato HTTP e os mesmos gates de CI.

- **API** (`ApiMain`): clientes e pedidos, PostgreSQL, JWT RS256.
- **Worker** (`WorkerMain`): consome `order.status.changed` no RabbitMQ e envia e-mail por SMTP.

## Rodando

```sh
mkdir keys   # coloque aqui jwt_public.pem (chave pública RSA do emissor dos tokens)
docker compose up --build
```

API em `http://127.0.0.1:8080`, e-mails capturados no Mailpit em `http://127.0.0.1:8025`. Todas as portas escutam apenas em `127.0.0.1`.

Sem Docker: `DATABASE_URL=... JWT_PUBLIC_KEY_PATH=... JWT_AUDIENCE=... JWT_ISSUER=... ./gradlew run` (API) e `./gradlew runWorker` (worker). As migrations são aplicadas na partida (o Flyway usa lock consultivo, seguro com várias réplicas).

## Configuração

| Variável | Padrão | Observação |
|---|---|---|
| `APP_ENV` | `development` | `production` liga as validações estritas abaixo |
| `HTTP_ADDR` | `:8080` | |
| `DATABASE_URL` | | obrigatória; `postgres://usuário:senha@host:5432/banco?sslmode=disable` ou `jdbc:postgresql:...` |
| `JWT_PUBLIC_KEY_PATH` | | obrigatória na API |
| `JWT_AUDIENCE`, `JWT_ISSUER` | | obrigatórias na API; ver ADR 0005 |
| `CORS_ORIGINS` | vazio | lista separada por vírgula; em produção não pode ser vazia nem `*` |
| `MAX_BODY_BYTES` | `1048576` | conta também corpos *chunked* |
| `RATE_LIMIT_PER_USER` / `_WRITES` / `_PER_IP` | `120` / `30` / `600` | por minuto; `/health` é isento |
| `BROKER_URL` | | sem ela a API não publica eventos; em produção precisa ser `amqps://` |
| `SMTP_HOST`, `SMTP_PORT`, `SMTP_USER`, `SMTP_PASSWORD`, `SMTP_FROM` | porta `587` | worker; em produção STARTTLS é obrigatório |
| `SHUTDOWN_TIMEOUT_SECONDS` | `15` | |

Em produção a API se recusa a iniciar com audience/issuer ausentes, CORS vazio ou com `*`, ou broker sem TLS, e lista todos os problemas de uma vez.

## Endpoints

| Método e rota | Quem pode |
|---|---|
| `GET /health` | público (verifica o banco) |
| `POST /customers`, `PUT /customers/{id}`, `DELETE /customers/{id}` | papel `admin` |
| `GET /customers/{id}`, `GET`/`POST /customers/search` | qualquer usuário autenticado |
| `POST /orders`, `GET`/`DELETE /orders/{id}`, `GET`/`POST /orders/search` | qualquer usuário autenticado |
| `POST /orders/{id}/items`, `PATCH`/`DELETE /orders/{id}/items/{item_id}` | qualquer usuário autenticado |
| `POST /orders/{id}/confirm`, `POST /orders/{id}/cancel` | qualquer usuário autenticado |

O contrato completo está em [`api/openapi.yaml`](api/openapi.yaml), e um teste falha se ele e o roteador deixarem de descrever a mesma API.

Busca: `page` (base 0), `size` (1 a 100), `sort` (`-campo` para decrescente). A forma `POST` aceita `filters`: `{"campo": [{"op": "gte", "value": 2}]}` com `eq, neq, gt, gte, lt, lte, in, between, lk`. Valores são convertidos para o tipo da coluna (tipo errado é `VALIDATION-05`), `lk` escapa `%` e `_`, campo de filtro desconhecido é ignorado e campo de ordenação desconhecido é `VALIDATION-06`.

Erros: `{"message", "code", "params", "request_id"}`. Catálogo: `VALIDATION-01..09`, `VALIDATION-12`, `RESOURCE-NOT-FOUND-01..03`, `CONFLICT-00..04`, `AUTH-01/02`, `RATE-00`, `INTERNAL-00`. Acrescentados aqui, para rotas e métodos inexistentes: `RESOURCE-NOT-FOUND-00` e `VALIDATION-13`.

A resposta de cliente expõe apenas `id` e `name`: documento, passaporte e e-mail são PII e nunca saem na API nem nos logs.

## Garantias de concorrência

- Pedido e cliente têm coluna `version`; escrita com versão antiga falha com `CONFLICT-00`.
- Criar pedido toma `FOR SHARE` no cliente e excluir cliente toma `FOR UPDATE`, na mesma transação da verificação de pedidos ativos: nunca nasce pedido sob um cliente excluído. Um teste repete a corrida 20 vezes contra um PostgreSQL real.
- Unicidade de documento e passaporte é garantida por índices únicos parciais (`WHERE deleted_at IS NULL`); a checagem no serviço só dá a mensagem amigável.

## Arquitetura

```
com.ronybrand.orderapi
├── error        AppException e o catálogo de códigos
├── domain       Customer, Order, Item, eventos: Kotlin puro, sem I/O
├── app          casos de uso e as interfaces (ports) que eles consomem
├── persistence  Flyway, Exposed, PostgresStore (implementa os ports)
├── auth         verificação de JWT RS256
├── http         Ktor: tabela de endpoints, plugins, envelope de erro
├── messaging    publisher e consumer RabbitMQ, e-mail, SMTP
└── config       variáveis de ambiente e as regras de produção
```

A dependência aponta sempre para dentro; um teste (`ArchitectureTest`) falha se um import apontar para fora. Decisões e alternativas descartadas em [`docs/adr/`](docs/adr/README.md).

## Desenvolvimento

Requer JDK 25. Nada além disso: o PostgreSQL dos testes de integração é embarcado e baixado do Maven Central.

```sh
make test          # ktlint, detekt e testes unitários (rápido, sem I/O)
make integration   # PostgreSQL real (embarcado) e SMTP em processo; o teste do broker precisa de Docker
make verify        # tudo mais o piso de cobertura (80%), igual ao CI
```

Os testes unitários não usam banco, broker nem rede. O piso de cobertura (80%) mede os unitários e os de integração juntos, como no `go-order-api`: só a suíte unitária cobre cerca de 57% das linhas, porque os repositórios, o cliente do broker e o SMTP só são alcançados pela integração. O CI também roda o detekt, builda as imagens (`api` e `worker`) e roda o Trivy nelas, e gera o SBOM CycloneDX do classpath de runtime (`./gradlew cyclonedxDirectBom`) e o varre em busca de vulnerabilidades conhecidas, a cada mudança e toda semana: achados HIGH ou CRITICAL com correção disponível quebram o build.

`bash scripts/smoke.sh` sobe o `docker-compose.yml` inteiro (PostgreSQL, RabbitMQ, Mailpit, API e worker), assina tokens RS256 como o emissor faria, cria um cliente e um pedido, confirma o pedido e espera o e-mail chegar no Mailpit. É o único teste que exercita os dois processos dentro dos containers, e o CI o roda a cada push. Foi ele que achou o bug do vhost vazio em `amqp://host/`, que os testes de integração não viam.
