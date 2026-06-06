#!/bin/bash

# =============================================
# Flash Sale Load Test Helper Script
# Usage:
#   ./flash-test.sh create     → Create a new sale and save ID
#   ./flash-test.sh run        → Run k6 load test with saved sale ID
#   ./flash-test.sh status     → Check current stock/status
#   ./flash-test.sh restock    → Restock 100 units and re-run test
#   ./flash-test.sh all        → Create + Run in one shot
# =============================================

BASE_URL="http://localhost:8081/api/sales"
SALE_ID_FILE=".sale_id"
RESTOCK_QTY=${RESTOCK_QTY:-100}

# How many minutes from now the sale ends
ENDS_IN_MINUTES=60

# Load saved sale ID if exists
load_sale_id() {
  if [ -f "$SALE_ID_FILE" ]; then
    SALE_ID=$(cat "$SALE_ID_FILE")
  else
    echo "❌ No sale ID found. Run './flash-test.sh create' first."
    exit 1
  fi
}

# ---- CREATE ----
create_sale() {
  NOW=$(date -d "+1 minute" +"%Y-%m-%dT%H:%M:%S" 2>/dev/null || date -v+1M +"%Y-%m-%dT%H:%M:%S")
  ENDS=$(date -d "+${ENDS_IN_MINUTES} minutes" +"%Y-%m-%dT%H:%M:%S" 2>/dev/null || date -v+${ENDS_IN_MINUTES}M +"%Y-%m-%dT%H:%M:%S")
  # Use past startsAt so it auto-activates within 10s
  STARTED=$(date -d "-1 minute" +"%Y-%m-%dT%H:%M:%S" 2>/dev/null || date -v-1M +"%Y-%m-%dT%H:%M:%S")

  echo "🚀 Creating Flash Sale..."
  RESPONSE=$(curl -s -X POST "$BASE_URL" \
    -H "Content-Type: application/json" \
    -d "{
      \"title\": \"Load Test Sale\",
      \"productId\": \"PROD-TEST-001\",
      \"productName\": \"Test Gadget\",
      \"price\": 99.99,
      \"totalStock\": 200,
      \"maxPerUser\": 3,
      \"startsAt\": \"$STARTED\",
      \"endsAt\": \"$ENDS\"
    }")

  echo "$RESPONSE" | python3 -m json.tool 2>/dev/null || echo "$RESPONSE"

  SALE_ID=$(echo "$RESPONSE" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

  if [ -z "$SALE_ID" ]; then
    echo "❌ Failed to parse sale ID from response. Is sale-service running?"
    exit 1
  fi

  echo "$SALE_ID" > "$SALE_ID_FILE"
  echo ""
  echo "✅ Sale created! ID: $SALE_ID"
  echo "⏳ Waiting 12 seconds for auto-activation..."
  sleep 12
  check_status
}

# ---- STATUS ----
check_status() {
  load_sale_id
  echo "📊 Checking status for sale: $SALE_ID"
  curl -s "$BASE_URL/$SALE_ID/status" | python3 -m json.tool 2>/dev/null
}

# ---- RESTOCK ----
restock() {
  load_sale_id
  echo "🔄 Restocking sale $SALE_ID with $RESTOCK_QTY units..."
  curl -s -X PATCH "$BASE_URL/$SALE_ID/restock?quantity=$RESTOCK_QTY"
  echo ""
  echo "✅ Restocked! New status:"
  check_status
}

# ---- RUN LOAD TEST ----
run_test() {
  load_sale_id
  echo "🔥 Running k6 load test against sale: $SALE_ID"
  k6 run -e SALE_ID="$SALE_ID" load-test.js
}

# ---- DISPATCH ----
case "${1:-help}" in
  create)   create_sale ;;
  run)      run_test ;;
  status)   check_status ;;
  restock)  restock ;;
  restock-run)
    restock
    echo ""
    run_test
    ;;
  all)
    create_sale
    echo ""
    run_test
    ;;
  *)
    echo "Flash Sale Load Test Helper"
    echo ""
    echo "Usage: ./flash-test.sh <command>"
    echo ""
    echo "Commands:"
    echo "  create       Create a new sale (saves ID to .sale_id)"
    echo "  run          Run k6 load test with saved sale ID"
    echo "  status       Check stock and status of saved sale"
    echo "  restock      Restock 100 units (override: RESTOCK_QTY=500 ./flash-test.sh restock)"
    echo "  restock-run  Restock then immediately re-run the load test"
    echo "  all          Create + immediately run load test"
    ;;
esac
