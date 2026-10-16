# ADR 0001: Layout do projeto e direção das dependências

## Contexto

O domínio é pequeno (dois agregados), mas a mesma especificação já existe em seis stacks. O que torna a comparação útil é ver como cada linguagem separa regra de negócio, casos de uso e infraestrutura, não quantos pacotes existem.

## Decisão

Um único módulo Gradle, com pacotes em camadas e a dependência sempre apontando para dentro:

- `error`: `AppException` e o catálogo de códigos. Todas as camadas a conhecem.
- `domain`: entidades e regras. Kotlin puro, sem I/O, sem biblioteca de infraestrutura.
- `app`: casos de uso e as interfaces (`CustomerRepository`, `OrderRepository`, `Store`, `EventPublisher`) que eles consomem. As interfaces ficam aqui, onde são usadas, e não junto da implementação.
- `persistence`, HTTP e mensageria: implementam as interfaces de `app` e conhecem `domain`. Nada em `domain` ou `app` os conhece.

As interfaces são bloqueantes de propósito: o JDBC é, e a camada HTTP executa os casos de uso no dispatcher de I/O em vez de fingir que são suspensos.

## Alternativas

- **Vários módulos Gradle** (`domain`, `app`, `infra`): impõem a regra de dependência na compilação. Descartado por ora: o custo de build e de configuração não se paga com dois agregados, e o `ArchitectureTest` falha se um import apontar para fora.
- **Tudo em suspend**: Exposed tem API suspensa, mas ela só move o bloqueio de lugar e contamina o domínio de `suspend`.

## Consequências

A regra de dependência é verificada por um teste (`ArchitectureTest`), não pelo compilador. Se o projeto crescer, os pacotes viram módulos sem mudar o código.
