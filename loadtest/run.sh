#!/bin/sh
# Runs the HTTP load test against a local app: sets the hot dish's stock, starts nothing else.
# Start the app first with rate limiting off, e.g.
#   RATE_LIMIT_ENABLED=false java -jar OnlineOrder/build/libs/OnlineOrder-*.jar
set -e
HOT_STOCK=${HOT_STOCK:-400}
PGPASSWORD=${DATABASE_PASSWORD:-secret} psql -q -h ${DATABASE_URL:-localhost} -U ${DATABASE_USERNAME:-postgres} -d onlineorder \
  -c "UPDATE inventory SET available = $HOT_STOCK WHERE menu_item_id = 2"
k6 run -e BASE_URL=${BASE_URL:-http://localhost:8080} -e HOT_STOCK=$HOT_STOCK "$(dirname "$0")/checkout.js"
