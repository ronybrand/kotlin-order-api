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
- Testes unitários sem I/O (piso de cobertura de 80%) e de integração em PostgreSQL real embarcado.
