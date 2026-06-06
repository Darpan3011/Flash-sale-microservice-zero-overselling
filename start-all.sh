#!/bin/bash

# Base directory
BASE_DIR="/home/darpan/Projects/FlashSale"

echo "=================================================="
echo "🚀 Flash Sale System - Startup Script"
echo "=================================================="

cd "$BASE_DIR" || exit

echo "1️⃣ Starting Infrastructure (Docker Compose)..."
echo "🚢 Starting Docker Compose (requires sudo)"
sudo docker-compose up -d

echo "2️⃣ Building the project (skipping tests)..."
mvn clean install -U -DskipTests

# Define services to start in logical order
SERVICES="sale-service order-service waitlist-service notification-service api-gateway"

echo "3️⃣ Starting Microservices in the background..."

# Create a logs directory
mkdir -p logs

for SERVICE in $SERVICES; do
  echo "▶️ Starting $SERVICE..."
  cd "$BASE_DIR/$SERVICE" || exit
  # Run maven spring-boot:run in the background and redirect output to a log file
  nohup mvn spring-boot:run > "$BASE_DIR/logs/$SERVICE.log" 2>&1 &
  echo "   PID: $!"
  echo "   Logs: tail -f logs/$SERVICE.log"
  # Wait a few seconds to let it initialize slightly before starting the next one
  sleep 5
done

cd "$BASE_DIR" || exit

echo "=================================================="
echo "✅ All services have been instructed to start!"
echo "📄 You can monitor the logs in the FlashSale/logs/ directory."
echo "   Example: tail -f logs/api-gateway.log"
echo "🛑 To stop all running microservices, use the command:"
echo "   pkill -f 'spring-boot:run'"
echo "=================================================="
