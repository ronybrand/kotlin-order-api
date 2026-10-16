# kotlin-order-api

API de pedidos em Kotlin, implementando a especificação do [`fastapi-order-api`](https://github.com/ronybrand/fastapi-order-api) (`DOMAIN.md` e catálogo de erros daquele projeto) com as convenções da linguagem: Ktor, corrotinas, Exposed, `kotlinx.serialization`, agregados imutáveis (`data class`) e interfaces pequenas declaradas por quem as consome.

O mesmo domínio já existe em FastAPI, Nest, .NET, Spring, Go e PHP; este repositório existe para comparar a mesma especificação em mais uma stack, com o mesmo contrato HTTP e os mesmos gates de CI.

## Status

| Parte | Estado |
|---|---|
| Domínio (cliente, pedido, itens, máquina de estados, evento) | pronto, testado |
| Casos de uso e busca | pronto, testado contra um store em memória |
| Persistência PostgreSQL (Flyway, Exposed, locks, lock otimista) | pronta, testada em banco real |
| HTTP (Ktor), autenticação JWT RS256, rate limit | em andamento |
| Worker de notificação (RabbitMQ, SMTP) | em andamento |

## Arquitetura

```
com.ronybrand.orderapi
├── error        AppException e o catálogo de códigos
├── domain       Customer, Order, Item, eventos: Kotlin puro, sem I/O
├── app          casos de uso e as interfaces (ports) que eles consomem
└── persistence  Flyway, Exposed, PostgresStore (implementa os ports)
```

A dependência aponta sempre para dentro: `persistence` e a camada HTTP conhecem `app` e `domain`; o contrário nunca acontece. Detalhes e motivos em [`docs/adr/`](docs/adr/README.md).

## Desenvolvimento

Requer JDK 25. Nada além disso: o PostgreSQL dos testes de integração é embarcado e baixado do Maven Central.

```sh
./gradlew ktlintCheck test koverVerify   # lint, testes unitários e piso de cobertura (80%)
./gradlew integrationTest                # PostgreSQL real (embarcado); o teste do broker precisa de Docker
./gradlew check                          # tudo
```

Os testes unitários não usam banco, broker nem rede. O piso de cobertura mede só eles.
