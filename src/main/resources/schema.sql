-- Wallet Service schema
-- Idempotent (IF NOT EXISTS) so it can run on every boot, against H2 (PostgreSQL mode)
-- and PostgreSQL alike. Hibernate is configured with ddl-auto=validate and never
-- generates DDL; this file is the single source of truth for the schema.
--
-- Secondary indexes are deliberately deferred (see PLAN.md). The unique constraint
-- uq_wallet_idempotency already backs the idempotency replay lookup with an index.

CREATE TABLE IF NOT EXISTS wallets (
    id              UUID                     PRIMARY KEY,
    customer_id     VARCHAR(64)              NOT NULL,
    balance_paise   BIGINT                   NOT NULL DEFAULT 0,
    currency        VARCHAR(3)               NOT NULL DEFAULT 'INR',
    version         BIGINT                   NOT NULL DEFAULT 0,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    -- Defense-in-depth: the application uses an atomic conditional UPDATE, but the
    -- database is the last line of defense for the never-negative invariant.
    CONSTRAINT chk_balance_non_negative CHECK (balance_paise >= 0)
);

-- Append-only ledger: one row per money movement, never updated or deleted.
CREATE TABLE IF NOT EXISTS wallet_transactions (
    id                  UUID                     PRIMARY KEY,
    wallet_id           UUID                     NOT NULL REFERENCES wallets (id),
    type                VARCHAR(16)              NOT NULL,
    amount_paise        BIGINT                   NOT NULL,
    balance_after_paise BIGINT                   NOT NULL,
    -- Required for DEDUCT (callers retry), optional for TOPUP. NULLs do not collide
    -- under the unique constraint in either PostgreSQL or H2.
    idempotency_key     VARCHAR(128),
    -- SHA-256 of the canonical request payload: lets us return 409 when an
    -- idempotency key is reused with a different request body.
    request_hash        VARCHAR(64),
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT chk_amount_positive CHECK (amount_paise > 0),
    CONSTRAINT chk_balance_after_non_negative CHECK (balance_after_paise >= 0),
    CONSTRAINT uq_wallet_idempotency UNIQUE (wallet_id, idempotency_key)
);
