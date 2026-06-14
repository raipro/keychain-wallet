# Keychain OS — Prepaid Wallet Service: Execution Plan

> Take-home assignment (Staff Engineer / Tech Lead). This document captures the agreed
> architecture, data model, and phased task breakdown **before any code is written**.

## Assignment Summary

Build the **Wallet Service** for a logistics platform: customers prepay into a wallet,
₹100 is deducted per order placed by the (black-box) Order Service. Requirements:

- `POST /wallets` — create a wallet
- `POST /wallets/:id/topup` — add funds
- `POST /wallets/:id/deduct` — deduct ₹100 per order (**must be idempotent**)
- `GET /wallets/:id/balance` — current balance
- `GET /wallets/:id/transactions` — ledger history
- **Hard invariant:** balance must never go negative; deduct only succeeds if balance ≥ ₹100
- Deliverables: working API, schema, tests, README with decisions, Order Service stub

Evaluation priorities: correctness (balance constraint + idempotency under concurrency),
data model clarity, engineering judgment, test quality.

---

## 1. High-Level Architecture

**Stack:** Java 17 · Spring Boot 3.5 · Maven · Spring Data JPA · hand-written
`schema.sql` applied via Spring SQL init (Hibernate set to `validate`, never to
generate DDL) · H2 (default profile) with PostgreSQL-compatible SQL and a `postgres`
profile. Testcontainers optional for Postgres-backed integration tests.

```
                       ┌──────────────────────────────────────────────┐
 Order Service stub ──▶│                Wallet Service                │
 (script, calls        │                                              │
  POST /deduct)        │  WalletController        (REST, validation)  │
                       │        │                                     │
 Customer/frontend ───▶│  WalletService           (business rules:    │
                       │        │                  balance constraint,│
                       │        │                  idempotency)       │
                       │  WalletRepository /                          │
                       │  LedgerRepository        (JPA + atomic SQL)  │
                       └────────┼─────────────────────────────────────┘
                                ▼
                         H2 / PostgreSQL
                  (wallets + append-only ledger)
```

Classic three-layer design — no over-engineering — with three deliberate correctness
decisions:

1. **Money as `BIGINT` minor units (paise).** No floating point, no `DECIMAL` rounding
   ambiguity. API accepts/returns integer paise with explicit field naming
   (`amountPaise`).
2. **Balance correctness via atomic conditional update, not read-then-write.** The
   deduct path runs
   `UPDATE wallets SET balance = balance - :amt WHERE id = :id AND balance >= :amt`
   inside a DB transaction; 0 rows updated → insufficient balance. A
   `CHECK (balance >= 0)` constraint is defense-in-depth so the invariant holds even if
   application code regresses. Race-free without pessimistic locks; survives concurrent
   deducts.
3. **Idempotency via a unique key on the ledger.** The Order Service sends an
   idempotency key (naturally the `orderId`). A unique constraint on
   `(wallet_id, idempotency_key)` makes the DB the arbiter: a retry hits the
   constraint, we load and return the original result (same response, no double
   deduction). A stored request hash lets us reject *same key, different payload* as a
   409 conflict.

The ledger is **append-only**: every money movement is a row, `balance_after` is
recorded per entry, and `SUM(ledger) == wallets.balance` is an auditable invariant
asserted in tests.

---

## 2. Database ER Design

Two tables, H2- and PostgreSQL-compatible. Relationship:
`wallets 1 ─── N wallet_transactions`.

```sql
-- schema.sql (single file, idempotent via IF NOT EXISTS)
CREATE TABLE IF NOT EXISTS wallets (
    id              UUID         PRIMARY KEY,
    customer_id     VARCHAR(64)  NOT NULL,
    balance_paise   BIGINT       NOT NULL DEFAULT 0,
    currency        VARCHAR(3)   NOT NULL DEFAULT 'INR',
    version         BIGINT       NOT NULL DEFAULT 0,          -- optimistic-lock guard for non-deduct paths
    created_at      TIMESTAMP    NOT NULL,
    updated_at      TIMESTAMP    NOT NULL,
    CONSTRAINT chk_balance_non_negative CHECK (balance_paise >= 0)
);

-- the ledger
CREATE TABLE IF NOT EXISTS wallet_transactions (
    id                  UUID         PRIMARY KEY,
    wallet_id           UUID         NOT NULL REFERENCES wallets(id),
    type                VARCHAR(16)  NOT NULL,                -- TOPUP | DEDUCT
    amount_paise        BIGINT       NOT NULL,
    balance_after_paise BIGINT       NOT NULL,                -- audit snapshot
    idempotency_key     VARCHAR(128),                         -- required for DEDUCT, optional for TOPUP
    request_hash        VARCHAR(64),                          -- detect key reuse with different payload
    created_at          TIMESTAMP    NOT NULL,
    CONSTRAINT chk_amount_positive CHECK (amount_paise > 0),
    CONSTRAINT uq_wallet_idempotency UNIQUE (wallet_id, idempotency_key)
);
```

**Design notes**

- **INR-only (decided 2026-06-12):** amount columns keep `_paise` naming and
  `currency` stays fixed at `'INR'`. Going multi-currency later means renaming the
  amount columns to `_minor` and validating amounts against the currency's ISO 4217
  exponent (`java.util.Currency#getDefaultFractionDigits`).

- **Indexes deferred (deliberate):** no explicit secondary indexes for now. When read
  volume justifies them, the candidates are
  `wallet_transactions (wallet_id, created_at DESC)` for the history endpoint and
  `wallets (customer_id)` for customer lookups. The unique constraint
  `uq_wallet_idempotency` already gives the deduct path an index on
  `(wallet_id, idempotency_key)` for free, so idempotency replay lookups stay fast.

- Balance is stored (fast reads) *and* derivable from the ledger (auditability) — the
  redundancy is intentional and test-asserted.
- Idempotency on top-ups: the assignment only requires it on deduct, but an optional
  key on top-up is accepted too — retried top-ups double-crediting is the same class
  of bug. Flagged in the README.

---

## 3. Phased Task Breakdown

Each task is independently codeable and reviewable; work stops after each for review.

| Task | Scope | Done means |
|---|---|---|
| **1. Project skeleton** | Maven project, Spring Boot 3, H2 default + `postgres` profile, base package layout (`api`, `domain`, `service`, `repository`, `config`), health check boots | `mvn spring-boot:run` starts, schema applies |
| **2. Schema + domain** | `schema.sql` above, `Wallet` + `WalletTransaction` entities, repositories | Schema review; entities map cleanly |
| **3. Wallet create + balance** | `POST /wallets`, `GET /wallets/:id/balance`, DTOs, global error handler (RFC 7807 problem details), validation | Endpoints work; 404 shape agreed |
| **4. Top-up** | `POST /wallets/:id/topup` — transactional credit + ledger entry, amount validation | Balance and ledger move together |
| **5. Deduct (the core)** | `POST /wallets/:id/deduct` — idempotency key handling, atomic conditional update, 422/409 semantics for insufficient balance / key conflict, replay returns original response | Concurrency-safe deduct; review hardest here |
| **6. Transactions endpoint** | `GET /wallets/:id/transactions` with pagination | Ledger readable, newest-first |
| **7. Tests** | Unit tests for service rules; integration tests incl. the hard cases: N parallel deducts against a balance that covers only some of them, idempotent replay under concurrency, key-reuse-with-different-body, ledger-sum == balance invariant | Tests pass; concurrency test genuinely exercises races |
| **8. Order Service stub + README** | Small script simulating order placement with retries against `/deduct`; README covering decisions, trade-offs, "with more time" | Demo runnable end-to-end |

**Review checkpoints:** after Task 2 (schema), after Task 5 (deduct semantics), and
after Task 7 (test coverage). Tasks 3, 4, 6 are low-risk.

---

## 4. API Conventions (pre-agreed defaults)

- **Amounts in integer paise** on the wire (`amountPaise`) — no decimals.
- **Idempotency key in the request body as `orderId`** rather than an
  `Idempotency-Key` header — the order is the natural idempotency unit for deduct.
  Header accepted as an alternative.
- **Error semantics:** `404` unknown wallet · `422` insufficient balance ·
  `409` idempotency key reuse with a different payload · `400` validation failures.
  Errors returned as RFC 7807 problem details.
