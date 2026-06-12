#!/usr/bin/env bash
#
# Order Service stub — simulates how the (black-box) Order Service integrates with
# the Wallet Service: deduct ₹100 per order before confirming it, retry on network
# ambiguity, reject the order when the wallet can't cover it.
#
# Usage:  ./scripts/order-service-stub.sh            (service on localhost:8080)
#         WALLET_URL=http://localhost:8081 ./scripts/order-service-stub.sh
set -euo pipefail

BASE="${WALLET_URL:-http://localhost:8080}"
JSON='Content-Type: application/json'

field() { python3 -c "import sys,json; print(json.load(sys.stdin)['$1'])"; }

echo "Order Service stub → Wallet Service at $BASE"
echo

echo "[setup] Creating a wallet for customer cust-1001"
WALLET_ID=$(curl -sf -X POST "$BASE/wallets" -H "$JSON" \
  -d '{"customerId":"cust-1001"}' | field walletId)
echo "        wallet: $WALLET_ID"

echo "[setup] Customer tops up ₹350 (35000 paise) — enough for 3 orders"
curl -sf -X POST "$BASE/wallets/$WALLET_ID/topup" -H "$JSON" \
  -d '{"amountPaise":35000,"idempotencyKey":"TOPUP-1001-1"}' > /dev/null
echo

# place_order <orderId> — returns 0 if the order is confirmed, 1 if rejected
place_order() {
  local order_id="$1"
  local response status
  response=$(curl -s -w '\n%{http_code}' -X POST "$BASE/wallets/$WALLET_ID/deduct" \
    -H "$JSON" -d "{\"orderId\":\"$order_id\"}")
  status=$(echo "$response" | tail -1)
  if [ "$status" = "200" ]; then
    local balance replayed
    balance=$(echo "$response" | head -1 | field balanceAfterPaise)
    replayed=$(echo "$response" | head -1 | field replayed)
    echo "[order] $order_id CONFIRMED (balance after: $balance paise, replayed: $replayed)"
    return 0
  else
    echo "[order] $order_id REJECTED (HTTP $status): $(echo "$response" | head -1 | field detail)"
    return 1
  fi
}

echo "--- Customer places three orders"
place_order "ORD-1001"
place_order "ORD-1002"
place_order "ORD-1003"
echo

echo "--- Network blip: Order Service never saw the ORD-1003 response, so it retries."
echo "    The retry must NOT charge again (replayed: true, balance unchanged):"
place_order "ORD-1003"
echo

echo "--- Wallet now holds ₹50; a fourth order must be rejected:"
place_order "ORD-1004" || true
echo

echo "--- Final state"
curl -sf "$BASE/wallets/$WALLET_ID/balance" | python3 -m json.tool
echo
echo "--- Ledger (newest first)"
curl -sf "$BASE/wallets/$WALLET_ID/transactions" | python3 -m json.tool
