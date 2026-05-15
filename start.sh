#!/bin/sh
set -e

# Use Railway/Render PORT or default to 3000
PORT="${PORT:-3000}"
echo "🌐 Starting PeerLink on port $PORT"

# Replace the placeholder port in Nginx config
sed -i "s/__PORT__/$PORT/g" /etc/nginx/nginx.conf

# Start Java backend (HTTP on 8080, WebSocket on 8081) in the background
echo "🚀 Starting Java backend..."
java -Djava.net.preferIPv4Stack=true -jar /app/app.jar &
JAVA_PID=$!

# Wait for Java to be ready
echo "⏳ Waiting for Java backend..."
for i in $(seq 1 30); do
    if curl -s http://127.0.0.1:8080/health > /dev/null 2>&1; then
        echo "✅ Java backend is ready!"
        break
    fi
    sleep 1
done

# Start Nginx in the foreground
echo "🔌 Starting Nginx reverse proxy on port $PORT"
nginx -g "daemon off;" &
NGINX_PID=$!

echo "═══════════════════════════════════════════"
echo "  🎉 PeerLink is LIVE on port $PORT"
echo "  📡 API:       http://localhost:$PORT/api/"
echo "  🔌 WebSocket: ws://localhost:$PORT/ws/"
echo "  🌐 UI:        http://localhost:$PORT/"
echo "═══════════════════════════════════════════"

# Wait for either process to exit
wait $JAVA_PID $NGINX_PID
