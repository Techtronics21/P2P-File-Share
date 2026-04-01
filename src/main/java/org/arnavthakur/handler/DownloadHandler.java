package org.arnavthakur.handler;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

import org.arnavthakur.service.FileSharer;
import org.arnavthakur.service.S3Service;
import org.arnavthakur.utils.FixedWindowRateLimiter;
import org.arnavthakur.utils.HeaderUtils;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

public class DownloadHandler implements HttpHandler {
    private final FileSharer fileSharer;

    // Rate Limiting variables
    private static final int MAX_DOWNLOADS_PER_MINUTE = 10;
    private static final long ONE_MINUTE_MS = 60_000;
    private static final Pattern TOKEN_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{12}$");
    private static final FixedWindowRateLimiter downloadRateLimiter =
            new FixedWindowRateLimiter(MAX_DOWNLOADS_PER_MINUTE, ONE_MINUTE_MS);

    public DownloadHandler(FileSharer fileSharer) {
        this.fileSharer = fileSharer;
    }

    @Override // this
    public void handle(HttpExchange exchange) throws IOException {
        Headers headers = exchange.getResponseHeaders();
        headers.add("Access-Control-Allow-Origin", "*");
        headers.add("Access-Control-Allow-Methods", "GET,OPTIONS");
        headers.add("Access-Control-Allow-Headers", "Content-Type");

        // Handle CORS preflight
        if (exchange.getRequestMethod().equalsIgnoreCase("OPTIONS")) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        if (!exchange.getRequestMethod().equalsIgnoreCase("GET")) {
            sendError(exchange, 405, "Method Not Allowed");
            return;
        }

        String userIp = exchange.getRemoteAddress().getAddress().getHostAddress();
        if (!downloadRateLimiter.allow(userIp)) {
            sendError(exchange, 429, "Rate limit exceeded: Max " + MAX_DOWNLOADS_PER_MINUTE + " requests per minute.");
            return;
        }

        // Get token from query parameter
        String query = exchange.getRequestURI().getQuery();
        String token = null;
        if (query != null) {
            for (String param : query.split("&")) {
                if (param.startsWith("token=")) {
                    token = param.substring(6);
                    break;
                }
            }
        }

        if (token == null || token.isEmpty()) {
            sendError(exchange, 400, "Missing token parameter");
            return;
        }

        if (!TOKEN_PATTERN.matcher(token).matches()) {
            sendError(exchange, 400, "Malformed token");
            return;
        }

        // Check transfer mode
        FileSharer.TransferMode mode = fileSharer.getTransferMode(token);

        if (mode == null) {
            sendError(exchange, 403, "Invalid or expired token.");
            return;
        }

        if (mode == FileSharer.TransferMode.SOCKET_P2P) {
            // Socket mode: Stream file directly to browser
            Integer port = fileSharer.getPortByToken(token);
            if (port == null) {
                sendError(exchange, 403, "Invalid or expired token");
                return;
            }

            String filePath = fileSharer.getFilePath(port);
            if (filePath == null) {
                sendError(exchange, 404, "File not found");
                return;
            }

            File file = new File(filePath);
            if (!file.exists()) {
                sendError(exchange, 404, "File not found");
                return;
            }

            // Extract original filename (remove UUID prefix)
            String fileName = file.getName();
            int underscoreIndex = fileName.indexOf('_');
            if (underscoreIndex > 0 && underscoreIndex < fileName.length() - 1) {
                fileName = fileName.substring(underscoreIndex + 1);
            }
            fileName = HeaderUtils.sanitizeFilename(fileName, "downloaded-file");

            String downloadMode = exchange.getRequestHeaders().getFirst("X-Download-Mode");
            if ("socket".equalsIgnoreCase(downloadMode)) {
                String hostHeader = exchange.getRequestHeaders().getFirst("Host");
                String host = "localhost";
                if (hostHeader != null && !hostHeader.isBlank()) {
                    host = hostHeader.split(":", 2)[0];
                }

                String jsonResponse = String.format(
                        "{\"host\":\"%s\",\"port\":%d,\"filename\":\"%s\"}",
                        escapeJson(host),
                        port,
                        escapeJson(fileName)
                );
                headers.add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, jsonResponse.getBytes(StandardCharsets.UTF_8).length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(jsonResponse.getBytes(StandardCharsets.UTF_8));
                }
                return;
            }

            System.out.println("📥 [SOCKET P2P] Browser download | Token: " + token + " | File: " + fileName);

            // Set headers for file download
            headers.add("Content-Type", "application/octet-stream");
            headers.add("Content-Disposition", HeaderUtils.buildAttachmentDisposition(fileName));
            headers.add("Content-Length", String.valueOf(file.length()));

            exchange.sendResponseHeaders(200, file.length());

            // Stream file to browser
            try (FileInputStream fis = new FileInputStream(file);
                 OutputStream os = exchange.getResponseBody()) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = fis.read(buffer)) != -1) {
                    os.write(buffer, 0, bytesRead);
                }
            }

            System.out.println("✅ [SOCKET P2P] File downloaded successfully: " + fileName);

            // Cleanup after download
            fileSharer.cleanupAfterDownload(token);

        } else if (mode == FileSharer.TransferMode.WEBSOCKET_RELAY) {
            // WebSocket relay: stream file directly from uploader's browser

            org.arnavthakur.service.RelaySession session = fileSharer.getRelaySession(token);
            if (session == null) {
                sendError(exchange, 404, "File share not found");
                return;
            }
            if (!session.isUploaderConnected()) {
                sendError(exchange, 404, "Sender is not online. Ask them to share again.");
                return;
            }

            String fileName = HeaderUtils.sanitizeFilename(session.getFilename(), "downloaded-file");

            // Signal the uploader's WebSocket to start sending file data
            session.getUploaderSocket().send("{\"type\":\"SEND_FILE\"}");

            System.out.println("📥 [WS RELAY] Transfer started | Token: " + token + " | File: " + fileName);

            headers.add("Content-Type", session.getContentType());
            headers.add("Content-Disposition", HeaderUtils.buildAttachmentDisposition(fileName));
            // Use chunked transfer (0 = no fixed content-length)
            exchange.sendResponseHeaders(200, 0);

            try (OutputStream os = exchange.getResponseBody()) {
                while (true) {
                    byte[] chunk = session.getDataQueue().poll(30, java.util.concurrent.TimeUnit.SECONDS);
                    if (chunk == null) {
                        throw new IOException("Transfer timeout - sender may have disconnected");
                    }
                    if (chunk == org.arnavthakur.service.RelaySession.END_SENTINEL) break;
                    os.write(chunk);
                    os.flush();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Transfer interrupted");
            }

            System.out.println("✅ [WS RELAY] Transfer complete | Token: " + token + " | File: " + fileName);
            fileSharer.cleanupAfterDownload(token);

        } else {
            S3Service.DownloadedObject downloadedObject = fileSharer.downloadFileFromS3(token);
            if (downloadedObject == null) {
                sendError(exchange, 403, "Invalid or expired token");
                return;
            }

            System.out.println("📥 [S3 RELAY] Download requested | Token: " + token);

            String safeFilename = HeaderUtils.sanitizeFilename(downloadedObject.getFilename(), "downloaded-file");
            headers.add("Content-Type", downloadedObject.getContentType());
            headers.add("Content-Disposition", HeaderUtils.buildAttachmentDisposition(safeFilename));

            long contentLength = downloadedObject.getContentLength();
            if (contentLength >= 0) {
                headers.add("Content-Length", String.valueOf(contentLength));
            }

            exchange.sendResponseHeaders(200, contentLength >= 0 ? contentLength : 0);

            try (S3Service.DownloadedObject ignored = downloadedObject;
                 InputStream inputStream = downloadedObject.getInputStream();
                 OutputStream os = exchange.getResponseBody()) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    os.write(buffer, 0, bytesRead);
                }
            } catch (Exception e) {
                throw new IOException("Failed to stream S3 object", e);
            } finally {
                fileSharer.cleanupAfterDownload(token);
            }

            System.out.println("✅ [S3 RELAY] File streamed successfully");
        }
    }

    private void sendError(HttpExchange exchange, int code, String message) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "text/plain");
        exchange.sendResponseHeaders(code, message.getBytes().length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(message.getBytes());
        }
    }

    private String escapeJson(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"");
    }
}
