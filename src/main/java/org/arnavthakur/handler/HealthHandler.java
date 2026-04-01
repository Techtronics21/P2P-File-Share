package org.arnavthakur.handler;

import java.io.IOException;
import java.io.OutputStream;

import org.arnavthakur.service.FileSharer;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

public class HealthHandler implements HttpHandler {
    private final FileSharer fileSharer;

    public HealthHandler(FileSharer fileSharer) {
        this.fileSharer = fileSharer;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        Headers headers = exchange.getResponseHeaders();
        headers.add("Access-Control-Allow-Origin", "*");
        headers.add("Access-Control-Allow-Methods", "GET,OPTIONS");
        headers.add("Access-Control-Allow-Headers", "Content-Type");

        if (exchange.getRequestMethod().equalsIgnoreCase("OPTIONS")) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        if (!exchange.getRequestMethod().equalsIgnoreCase("GET")) {
            String response = "{\"error\":\"Method Not Allowed\"}";
            headers.add("Content-Type", "application/json");
            exchange.sendResponseHeaders(405, response.getBytes().length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
            return;
        }

        String response = String.format(
                "{\"status\":\"UP\",\"activeShares\":%d,\"sharesBreakdown\":\"%s\",\"s3Available\":%s,\"timestamp\":%d}",
                fileSharer.getActiveShareCount(),
                fileSharer.getSharesBreakdown(),
                fileSharer.isS3Available(),
                System.currentTimeMillis()
        );

        headers.add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.getBytes().length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(response.getBytes());
        }
    }
}
