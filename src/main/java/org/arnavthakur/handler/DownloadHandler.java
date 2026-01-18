package org.arnavthakur.handler;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

import org.arnavthakur.service.FileSharer;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

public class DownloadHandler implements HttpHandler {
    private final FileSharer fileSharer;

    public DownloadHandler(FileSharer fileSharer) {
        this.fileSharer = fileSharer;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        Headers headers = exchange.getResponseHeaders();
        headers.add("Access-Control-Allow-Origin", "*");
        headers.add("Access-Control-Allow-Methods", "GET,POST,OPTIONS");
        headers.add("Access-Control-Allow-Headers", "Content-Type,Authorization");

        if (exchange.getRequestMethod().equalsIgnoreCase("OPTIONS")) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        if (!exchange.getRequestMethod().equalsIgnoreCase("GET")) {
            String response = "Method Not Allowed";
            exchange.sendResponseHeaders(405, response.getBytes().length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
            return;
        }

        // Get token from query parameter
        String query = exchange.getRequestURI().getQuery();
        String token = null;
        if (query != null) {
            String[] params = query.split("&");
            for (String param : params) {
                if (param.startsWith("token=")) {
                    token = param.substring(6);
                    break;
                }
            }
        }

        try {
            // Ignore port in path, use only token for lookup
            Integer port = fileSharer.getPortByToken(token);
            if (port == null) {
                String response = "Access denied: Invalid or missing token";
                headers.add("Content-Type", "text/plain");
                exchange.sendResponseHeaders(403, response.getBytes().length); // 403 Forbidden
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response.getBytes());
                }
                return;
            }
            
            try (Socket socket = new Socket("localhost", port)) {
                InputStream socketInput = socket.getInputStream();
                File tempFile = File.createTempFile("download-", ".tmp");
                tempFile.deleteOnExit(); // Extra safety: delete if JVM exits
                String fileName = "downloaded-file";
                try {
                    try (FileOutputStream fileOutputStream = new FileOutputStream(tempFile)) {
                        byte[] buffer = new byte[4096];
                        int byteRead;
                        ByteArrayOutputStream headerBaos = new ByteArrayOutputStream();
                        int b;
                        while ((b = socketInput.read()) != -1) {
                            if (b == '\n') break;
                            headerBaos.write(b);
                        }

                        String header = headerBaos.toString().trim();

                        if (header.startsWith("Filename: ")) {
                            fileName = header.substring("Filename: ".length());
                        }
                        while ((byteRead = socketInput.read(buffer)) != -1) {
                            fileOutputStream.write(buffer, 0, byteRead);
                        }
                    }

                    // Send the file to the client
                    headers.add("Content-Disposition", "attachment; filename=\"" + fileName + "\"");
                    headers.add("Content-Type", "application/octet-stream");
                    exchange.sendResponseHeaders(200, tempFile.length());
                    try (OutputStream os = exchange.getResponseBody();
                         FileInputStream fis = new FileInputStream(tempFile)) {
                        byte[] buffer = new byte[4096];
                        int bytesRead;
                        while ((bytesRead = fis.read(buffer)) != -1) {
                            os.write(buffer, 0, bytesRead);
                        }
                    }
                    
                    fileSharer.cleanupAfterDownload(port);
                    
                } finally {
                    // Always delete temp file
                    if (tempFile.exists()) {
                        tempFile.delete();
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("Error downloading file from peer: " + e.getMessage());
            String response = "Error downloading file: " + e.getMessage();
            headers.add("Content-Type", "text/plain");
            exchange.sendResponseHeaders(500, response.getBytes().length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
        }
    }
}