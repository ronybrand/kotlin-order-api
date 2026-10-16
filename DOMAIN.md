# Domínio: gestão de pedidos

A especificação funcional (entidades, invariantes, eventos e catálogo de erros) é a do
[`fastapi-order-api`](https://github.com/ronybrand/fastapi-order-api/blob/main/DOMAIN.md); este projeto a implementa
em Kotlin e não a duplica, para que haja uma única fonte. Este arquivo mapeia a especificação para o código e registra
onde esta implementação acrescenta ou escolhe diferente.

## Onde cada regra vive

| Regra da especificação | Código |
|---|---|
| Validação de cliente (nome, documento canônico, passaporte, e-mail) | `domain/Customer.kt`, `domain/EmailAddress.kt` |
| Itens, total derivado, máquina de estados OPEN, CONFIRMED, CANCELED | `domain/Order.kt` |
| Evento `OrderStatusChanged` (contrato da fila) | `domain/Events.kt` |
| Criar pedido exige cliente existente; excluir cliente exige zero pedidos ativos | `app/OrderService.kt`, `app/CustomerService.kt` |
| Unicidade de documento e passaporte entre ativos | índices parciais em `db/migration/V1__init.sql` |
| Lock otimista (`version`) | `persistence/PgCustomers.kt`, `persistence/PgOrders.kt` |
| Busca, filtros e limites | `app/Search.kt`, `persistence/SearchPlan.kt`, `persistence/SearchSql.kt` |
| Códigos de erro | `error/AppException.kt` |
| Retry, DLQ e deduplicação das notificações | `messaging/Processor.kt`, `messaging/DeliveryHandler.kt` |
| Contrato HTTP e acesso por rota | `http/Endpoints.kt`, `api/openapi.yaml` |
| Verificação de JWT e papéis | `auth/JwtVerifier.kt` |

## Diferenças em relação à especificação

- **Resposta de cliente**: só `id` e `name`. Documento, passaporte e e-mail são PII e não voltam pela API; `Customer.toString()` mostra só o id.
- **Códigos acrescentados**: `RESOURCE-NOT-FOUND-00` (rota inexistente) e `VALIDATION-13` (método não permitido), para que 404 e 405 saiam no mesmo envelope `{message, code, params, request_id}`.
- **Identificadores**: gerados pela aplicação (`UUID.randomUUID()`), não pelo banco; o default `gen_random_uuid()` continua no schema como rede de segurança. Ver o ADR 0002.
- **Agregados imutáveis**: cada operação devolve o novo estado e deixa o original intacto; uma regra violada nunca deixa um pedido pela metade.
- **Campos pesquisáveis**: `customers` aceita `id, name, tax_id, passport_number, email, version, created_at, updated_at, created_by, updated_by`; `orders` aceita `id, customer_id, status, version, created_at, updated_at, created_by, updated_by`. Colunas de exclusão lógica não são pesquisáveis nem ordenáveis. Um teste compara a lista com as colunas do schema real.
- **Total do pedido** tem sempre duas casas (`BigDecimal`), nunca ponto flutuante.
- **Notificação**: publicada depois do commit, sem outbox (ADR 0003). O valor do evento viaja como texto (`"total_amount": "10.30"`), para que nenhum consumidor o leia como ponto flutuante.
- **Catálogo ampliado, como no `go-order-api`**: `VALIDATION-09` (limite de itens), `VALIDATION-12` (corpo grande demais, 413), `CONFLICT-04` (violação de integridade do banco), `AUTH-01` (401) e `AUTH-02` (403).
