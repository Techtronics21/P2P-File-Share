package org.arnavthakur.handler;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentHashMap;

import org.arnavthakur.service.FileSharer;
import org.arnavthakur.service.RelaySession;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

public class RelayServer extends WebSocketServer {

    private final FileSharer fileSharer;
    private final ConcurrentHashMap<WebSocket, String> connectionToToken = new ConcurrentHashMap<>();

    public RelayServer(int port, FileSharer fileSharer) {
        super(new InetSocketAddress(port));
        this.fileSharer = fileSharer;
        setReuseAddr(true);
        // Send ping frames every 10 seconds to keep connections alive
        // through cloud proxies (Railway, Render, etc.)
        setConnectionLostTimeout(20);
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        String token = extractToken(handshake.getResourceDescriptor());
        if (token == null || token.isBlank()) {
            conn.close(4001, "Missing token");
            return;
        }
        RelaySession session = fileSharer.getRelaySession(token);
        if (session == null) {
            conn.close(4002, "Invalid token");
            return;
        }
        session.setUploaderSocket(conn);
        session.setUploaderConnected(true);
        connectionToToken.put(conn, token);
        conn.send("{\"type\":\"REGISTERED\",\"token\":\"" + token + "\"}");
        System.out.println("\uD83D\uDD0C [WS RELAY] Uploader connected | Token: " + token);
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        String token = connectionToToken.get(conn);
        if (token == null) return;
        RelaySession session = fileSharer.getRelaySession(token);
        if (session == null) return;
        if (message.contains("TRANSFER_COMPLETE")) {
            try {
                session.getDataQueue().put(RelaySession.END_SENTINEL);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            System.out.println("\u2705 [WS RELAY] Transfer complete | Token: " + token);
        }
    }

    @Override
    public void onMessage(WebSocket conn, ByteBuffer message) {
        String token = connectionToToken.get(conn);
        if (token == null) return;
        RelaySession session = fileSharer.getRelaySession(token);
        if (session == null) return;
        byte[] data = new byte[message.remaining()];
        message.get(data);
        try {
            session.getDataQueue().put(data);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        String token = connectionToToken.remove(conn);
        if (token != null) {
            RelaySession session = fileSharer.getRelaySession(token);
            if (session != null) {
                session.setUploaderConnected(false);
                try {
                    session.getDataQueue().put(RelaySession.END_SENTINEL);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            System.out.println("\uD83D\uDD0C [WS RELAY] Disconnected | Token: " + token);
        }
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        System.err.println("\u274C [WS RELAY] Error: " + ex.getMessage());
        if (conn != null) {
            String token = connectionToToken.get(conn);
            if (token != null) {
                RelaySession session = fileSharer.getRelaySession(token);
                if (session != null) session.setError(ex.getMessage());
            }
        }
    }

    @Override
    public void onStart() {
        System.out.println("\uD83D\uDE80 WebSocket relay server started on port " + getPort());
    }

    private String extractToken(String resourceDescriptor) {
        if (resourceDescriptor == null) return null;
        int idx = resourceDescriptor.indexOf("token=");
        if (idx == -1) return null;
        String token = resourceDescriptor.substring(idx + 6);
        int amp = token.indexOf('&');
        if (amp != -1) token = token.substring(0, amp);
        return token.trim();
    }
}
