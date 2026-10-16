# ADR 0002: Agregados imutáveis e identificadores gerados pela aplicação

## Contexto

Nas outras implementações o pedido é um objeto mutável e o `id` nasce no banco (`gen_random_uuid()`), então toda entidade tem um estado "ainda sem id". Em Kotlin isso vira `UUID?` por toda parte, ou um valor sentinela.

## Decisão

- Cliente, pedido e item são `data class` imutáveis. Cada operação de domínio (`addItem`, `confirm`, `replace`...) devolve o novo estado e deixa o original intacto. Uma regra violada lança `AppException` antes de qualquer cópia, então nunca existe um pedido pela metade.
- O `id` é gerado pela aplicação (`UUID.randomUUID()`) na criação do objeto. O default `gen_random_uuid()` permanece no schema como rede de segurança para inserções fora da aplicação.
- `confirm` e `cancel` devolvem `StatusChange(order, previous)`, porque o evento precisa do status anterior e a cópia já traz o novo.

## Alternativas

- **Entidades mutáveis com `id` do banco**: igual às outras stacks, mas obriga `id` anulável ou `lateinit`, e uma exceção no meio de uma operação pode deixar o objeto alterado.
- **UUID v7 (ordenável)**: melhora a localidade do índice primário, mas o Java não o gera nativamente e a ordenação de listagens já usa `created_at`. Pode ser adotado depois sem mudar o contrato.

## Consequências

O repositório devolve a entidade com a `version` seguinte (`save`, `update`), em vez de alterá-la no lugar. A semântica é a mesma do lock otimista, só que explícita. Gerar o id na aplicação permite montar o pedido inteiro, com os itens apontando para ele, antes de tocar no banco.
