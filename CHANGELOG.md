# Changelog

Formato baseado em [Keep a Changelog](https://keepachangelog.com/pt-BR/1.0.0/). O projeto não versiona
releases (`main` é a linha de entrega); as entradas agrupam o histórico por tema, não por tag.

## [Unreleased]

### Added

- Domínio de clientes e pedidos portado da especificação do `fastapi-order-api`: agregados imutáveis, total
  derivado em `BigDecimal`, máquina de estados e evento de mudança de status.
- Casos de uso sobre interfaces, com busca validada (limites de página, de filtros e de valores).
- Persistência em PostgreSQL com Flyway e Exposed: índices únicos parciais, lock otimista por `version`,
  `FOR UPDATE`/`FOR SHARE` contra a corrida entre excluir cliente e criar pedido.
- API HTTP em Ktor: tabela única de endpoints com o acesso de cada rota, envelope de erro, JWT RS256 só de verificação, limites de taxa (por IP, por usuário e por escrita), limite de corpo que conta *chunked*, CORS por origem exata e contrato OpenAPI com teste de deriva.
- Worker de notificação: RabbitMQ com confirmações do publisher, retry, DLQ e deduplicação, e-mail por SMTP com STARTTLS obrigatório em produção.
- Teste de fumaça ponta a ponta da pilha do compose (`scripts/smoke.sh`), rodado no CI.
- Análise estática com detekt e varredura de vulnerabilidades do SBOM CycloneDX do classpath de runtime (a cada mudança e semanalmente).
- Dockerfile (API e worker), `docker-compose.yml`, Makefile e a verificação das imagens com Trivy no CI.
- Testes unitários sem I/O (piso de cobertura de 80%) e de integração em PostgreSQL real embarcado.

### Security

- Actions fixadas por SHA, Dependabot semanal com auto-merge de patch e minor, revisão de dependências em cada pull request e CodeQL.
- A produção recusa subir com audience ou issuer ausentes, CORS vazio ou `*`, ou broker sem `amqps://`.
- A resposta de cliente expõe só `id` e `name`; documento, passaporte e e-mail ficam fora da API e dos logs.
