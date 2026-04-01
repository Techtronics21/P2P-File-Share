package org.arnavthakur.handler;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import org.arnavthakur.service.FileSharer;
import org.arnavthakur.utils.FixedWindowRateLimiter;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

/**
 * Handles POST /register — registers a WebSocket relay share (metadata only, no file upload).
 * The client sends JSON with filename, size, and contentType.
 * Returns a token that the client uses to open a WebSocket connection.
 */
public class RegisterHandler implements HttpHandler {
    private final FileSharer fileSharer;
    private static final FixedWindowRateLimiter rateLimiter =
            new FixedWindowRateLimiter(10, 60_000);

    public RegisterHandler(FileSharer fileSharer) {
        this.fileSharer = fileSharer;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        Headers headers = exchange.getResponseHeaders();
        headers.add("Access-Control-Allow-Origin", "*");
        headers.add("Access-Control-Allow-Methods", "POST,OPTIONS");
        headers.add("Access-Control-Allow-Headers", "Content-Type");

        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method Not Allowed");
            return;
        }

        String userIp = exchange.getRemoteAddress().getAddress().getHostAddress();
        if (!rateLimiter.allow(userIp)) {
            sendError(exchange, 429, "Rate limit exceeded");
            return;
        }

        String body;
        try (InputStream is = exchange.getRequestBody()) {
            body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }

        String filename = extractJsonString(body, "filename");
        long fileSize = extractJsonLong(body, "size");
        String contentType = extractJsonString(body, "contentType");

        if (filename == null || filename.isBlank()) {
            sendError(exchange, 400, "Missing filename");
            return;
        }
        if (fileSize <= 0 || fileSize > 500L * 1024 * 1024) {
            sendError(exchange, 400, "Invalid file size (max 500MB)");
            return;
        }

        String token = fileSharer.registerWebSocketRelay(filename, fileSize, contentType);

        String response = "{\"token\":\"" + token + "\"}";
        headers.add("Content-Type", "application/json");
        byte[] responseBytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, responseBytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(responseBytes);
        }
        System.out.println("📋 [WS RELAY] Registered | Token: " + token + " | File: " + filename);
    }

    private void sendError(HttpExchange exchange, int code, String msg) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "text/plain");
        exchange.sendResponseHeaders(code, msg.getBytes().length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(msg.getBytes());
        }
    }

    private String extractJsonString(String json, String key) {
        String pattern = "\"" + key + "\"";
        int idx = json.indexOf(pattern);
        if (idx == -1) return null;
        int colon = json.indexOf(':', idx + pattern.length());
        if (colon == -1) return null;
        int start = json.indexOf('"', colon + 1);
        if (start == -1) return null;
        int end = json.indexOf('"', start + 1);
        if (end == -1) return null;
        return json.substring(start + 1, end);
    }

    private long extractJsonLong(String json, String key) {
        String pattern = "\"" + key + "\"";
        int idx = json.indexOf(pattern);
        if (idx == -1) return -1;
        int colon = json.indexOf(':', idx + pattern.length());
        if (colon == -1) return -1;
        StringBuilder sb = new StringBuilder();
        for (int i = colon + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (Character.isDigit(c)) sb.append(c);
            else if (sb.length() > 0) break;
        }
        try { return Long.parseLong(sb.toString()); }
        catch (NumberFormatException e) { return -1; }
    }
}
