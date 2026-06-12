# Keychain OS — Prepaid Wallet Service

Wallet Service for a logistics platform: customers prepay into a wallet, the Order
Service deducts ₹100 per order, and the wallet must never go negative. Built with
Java 17, Spring Boot 3.5, and Maven. See [PLAN.md](PLAN.md) for the architecture and
task plan this was built against.

## Running it

```bash
mvn spring-boot:run                      # H2 in-memory (PostgreSQL mode), port 8080
SERVER_PORT=8081 mvn spring-boot:run     # if 8080 is busy
mvn test                                 # full suite, incl. concurrency tests
```

Against a real PostgreSQL:

```bash
SPRING_PROFILES_ACTIVE=postgres POSTGRES_HOST=localhost POSTGRES_DB=walletdb \
POSTGRES_USER=wallet POSTGRES_PASSWORD=wallet mvn spring-boot:run
```

The schema (`src/main/resources/schema.sql`) is idempotent and applies on boot;
Hibernate runs in `validate`-only mode and never generates DDL.

### Order Service integration demo

With the service running:

```bash
./scripts/order-service-stub.sh
```

The stub creates a wallet, tops up ₹350, places three orders, retries one (showing
the idempotent replay), and has a fourth rejected for insufficient balance.

## API

| Endpoint | Purpose | Errors |
|---|---|---|
| `POST /wallets` `{customerId}` | Create wallet → 201 | 400 |
| `GET /wallets/:id/balance` | Current balance | 404 |
| `POST /wallets/:id/topup` `{amountPaise, idempotencyKey?}` | Add funds | 400, 404, 409 |
| `POST /wallets/:id/deduct` `{orderId}` | Deduct ₹100 (idempotent on `orderId`) | 400, 404, 409, 422 |
| `GET /wallets/:id/transactions?page&size` | Ledger, newest first | 404 |

Errors are RFC 7807 problem details: `404` unknown wallet, `422` insufficient
balance, `409` idempotency key reused with a different payload, `400` validation.
Amounts are integer **paise** everywhere — no floating point, no decimals on the wire.

## Key decisions

**1. Balance constraint by atomic conditional update, not read-then-write.**
The deduct path is a single statement:
`UPDATE wallets SET balance_paise = balance_paise - :amt WHERE id = :id AND balance_paise >= :amt`.
Zero rows updated means insufficient balance. No interleaving of concurrent requests
can drive the balance negative, without pessimistic locks or serializable isolation.
A `CHECK (balance_paise >= 0)` constraint is defense-in-depth: even a future buggy
code path cannot break the invariant.

**2. Idempotency is owned by the database, in two layers.**
The Order Service sends `orderId` as the idempotency key. A pre-check inside the
transaction handles ordinary retries: the original response is replayed
(`replayed: true`, same transaction id, no money moved). Two *truly concurrent*
requests with the same key can both pass the pre-check — then the unique constraint
on `(wallet_id, idempotency_key)` lets exactly one commit; the loser's entire
transaction (including its balance update) rolls back and the winner's committed
result is returned. This is why `WalletService` uses an explicit
`TransactionTemplate`: the constraint-violation catch must sit *outside* the
transaction boundary, which `@Transactional` self-invocation cannot express.

**3. `request_hash` distinguishes a retry from a conflict.**
A retry (same key, same payload) replays; a key reused with a *different* payload
returns `409` instead of silently returning a result that doesn't match what the
caller asked for (Stripe's behavior, for the same reason). Without it, a buggy
caller would believe a ₹250 charge succeeded when ₹100 moved.

**4. Append-only ledger + stored balance, redundantly and on purpose.**
Every money movement is an immutable `wallet_transactions` row recording
`balance_after_paise`. The stored `wallets.balance_paise` makes reads O(1); the
ledger makes the system auditable. `SUM(ledger) == balance` is an invariant the
test suite asserts, including after concurrent races.

**5. Money as `BIGINT` paise, INR-only — explicitly.**
Integer minor units avoid floating point and rounding ambiguity. The `_paise`
naming honestly commits to INR (a deliberate decision over half-supporting
multi-currency); going international means renaming to `_minor` and validating
against each currency's ISO 4217 exponent.

**6. The ₹100 is configuration, not code** (`wallet.deduct-amount-paise`), so the
business rule is visible and changeable without a deploy of new code.

Smaller calls: idempotency is also *accepted* (optionally) on top-ups — a retried
top-up double-crediting is the same class of bug; secondary indexes are deferred
until read volume justifies them (the unique constraint already indexes the
idempotency lookup); `walletId` is a plain column on the ledger rather than a JPA
association — the FK exists in the database, but inserts shouldn't load an entity
graph.

## Test methodology

Three layers, all runnable with `mvn test` (18 tests):

1. **Business rules** (`WalletServiceTest`): top-up/deduct move balance and ledger
   together; insufficient balance fails atomically leaving no trace; replays return
   the original transaction; key-reuse-with-different-payload is rejected; the same
   `orderId` on *different* wallets deducts both (key is scoped per wallet).
2. **Concurrency — the hard cases** (`WalletServiceTest`): (a) 20 threads race
   distinct orders against a balance that covers exactly 5 — exactly 5 succeed, 15
   get insufficient-balance, final balance is 0; (b) 10 threads race the *same*
   `orderId` — exactly one transaction exists, everyone receives its id, money moves
   once. Both assert the ledger-sum invariant afterwards. (Expected: H2 logs unique
   constraint violations during (b) — that *is* the mechanism resolving the race.)
3. **HTTP contract** (`WalletApiTest`): status codes (201/200/400/404/409/422),
   problem-detail shapes, pagination, newest-first ordering.

The reasoning: the bugs that matter in a wallet are not in the happy path — they
live in races and retries. Tests use real threads against the real database path
(no mocks around the transactional core), so a regression to read-then-write or
in-memory locking would fail the suite, not just code review.

## What I'd do with more time

- **Testcontainers** to run the same suite against real PostgreSQL in CI (H2's
  PostgreSQL mode is close, but lock/constraint semantics deserve the real thing).
- **Cursor-based pagination** for the ledger (offset pagination degrades on large
  histories and can skip/duplicate rows across pages under concurrent writes).
- **Authentication/authorization** — wallets are currently open; at minimum
  service-to-service auth for `/deduct` and customer-scoped tokens elsewhere.
- **Observability**: metrics for deduct latency/failure rates, idempotency replay
  counts (a spike means a sick caller), and balance-vs-ledger reconciliation alarms.
- **Refunds/adjustments** as new ledger entry types (`REFUND`, `ADJUSTMENT`) —
  the append-only model extends naturally; never `UPDATE` history.
- **Outbox events** (`wallet.debited`, `wallet.credited`) so downstream systems can
  consume money movements without polling the ledger.
- **Hot-wallet scale**: a single wallet taking thousands of deducts/sec serializes
  on its row lock; the escape hatch is balance sharding or an event-sourced balance
  projection. Deliberately out of scope — correctness first.
