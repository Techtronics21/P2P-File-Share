#!/bin/bash
set -e

echo "🚀 Starting Java backend..."

# Start Java in background (HTTP on 8090, WebSocket on 8091)
java -Djava.net.preferIPv4Stack=true -jar /app/app.jar &
JAVA_PID=$!

echo "⏳ Waiting for Java backend..."

# Wait for Java HTTP to be ready (internal port 8090)
for i in $(seq 1 30); do
    if curl -s http://127.0.0.1:8090/health > /dev/null 2>&1; then
        echo "✅ Java backend is ready!"
        break
    fi
    if [ $i -eq 30 ]; then
        echo "❌ Java backend failed to start!"
        exit 1
    fi
    sleep 1
done

echo "🔌 Starting Nginx reverse proxy on port 8080"

# Start Nginx in foreground
echo "═══════════════════════════════════════════"
echo "  🎉 PeerLink is LIVE on port 8080"
echo "  📡 API:       http://localhost:8080/api/"
echo "  🔌 WebSocket: ws://localhost:8080/ws/"
echo "  🌐 UI:        http://localhost:8080/"
echo "═══════════════════════════════════════════"

exec nginx -g "daemon off;"
