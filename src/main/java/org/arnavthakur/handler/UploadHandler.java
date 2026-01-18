
package org.arnavthakur.handler;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.arnavthakur.service.FileSharer;
import org.arnavthakur.utils.MultiParser;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

public class UploadHandler implements HttpHandler {
    private final String uploadDir;
    private final FileSharer fileSharer;
    // Maximum file size: 500MB
    private static final long MAX_FILE_SIZE = 500L * 1024 * 1024; // 500MB in bytes

    private static final int MAX_UPLOADS_PER_MINUTE = 10; // Maximum uploads allowed per minute
    private static final long ONE_MINUTE_MS = 60_000; // One minute in milliseconds

    // Allowed file extensions and MIME types (security whitelist)
    private static final String[] ALLOWED_EXTENSIONS = {
        ".txt", ".pdf", ".jpg", ".jpeg", ".png", ".gif", ".zip", ".doc", ".docx", ".csv"
    };
    private static final String[] ALLOWED_MIME_TYPES = {
        "text/plain", "application/pdf", "image/jpeg", "image/png", "image/gif",
        "application/zip", "application/x-zip-compressed", "application/x-zip", "application/octet-stream",
        "application/msword", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "text/csv"
    };

    // This map keeps track of each IP's upload info
    // Key: IP address, Value: UploadInfo object
    private static final ConcurrentHashMap<String, UploadInfo> uploadTracker = new ConcurrentHashMap<>();

    // This class stores info about uploads for one IP
    private static class UploadInfo {
        long minuteWindowStart; // When the current minute started
        int uploadCount;        // How many uploads so far in this minute
        UploadInfo(long minuteWindowStart) {
            this.minuteWindowStart = minuteWindowStart;
            this.uploadCount = 1;
        }
    }

    public UploadHandler(String uploadDir, FileSharer fileSharer) {
        this.uploadDir = uploadDir;
        this.fileSharer = fileSharer;
    }

    // Helper method to check if file extension is allowed
    private boolean isAllowedExtension(String filename) {
        if (filename == null) return false;
        String lower = filename.toLowerCase();
        for (String ext : ALLOWED_EXTENSIONS) {
            if (lower.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }

    // Helper method to check if MIME type is allowed
    private boolean isAllowedMimeType(String mimeType) {
        if (mimeType == null) return false;
        for (String allowed : ALLOWED_MIME_TYPES) {
            if (mimeType.toLowerCase().contains(allowed.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        Headers headers = exchange.getResponseHeaders();
        headers.add("Access-Control-Allow-Origin", "*");
        headers.add("Access-Control-Allow-Methods", "GET,POST,OPTIONS");
        headers.add("Access-Control-Allow-Headers", "Content-Type,Authorization");

        // Handle CORS preflight for this route
        if (exchange.getRequestMethod().equalsIgnoreCase("OPTIONS")) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
            String response = "Method Not Allowed";
            exchange.sendResponseHeaders(405, response.getBytes().length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
            return;
        }

        // Get the user's IP address
        String userIp = exchange.getRemoteAddress().getAddress().getHostAddress();
        long currentTime = System.currentTimeMillis();

        // Look up this IP in our tracker map
        UploadInfo info = uploadTracker.get(userIp);

        if (info == null) {
            // First upload from this IP, start a new minute window
            info = new UploadInfo(currentTime);
            uploadTracker.put(userIp, info);
        } else if (currentTime - info.minuteWindowStart > ONE_MINUTE_MS) {
            // It's a new minute, reset the counter
            info.minuteWindowStart = currentTime;
            info.uploadCount = 1;
        } else {
            // Still in the same minute, increase the count
            info.uploadCount++;
            if (info.uploadCount > MAX_UPLOADS_PER_MINUTE) {
                // Too many uploads! Block this request
                String response = "Rate limit exceeded: Max " + MAX_UPLOADS_PER_MINUTE + " uploads per minute.";
                exchange.sendResponseHeaders(429, response.getBytes().length); // 429 Too Many Requests
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response.getBytes());
                }
                return;
            }
        }

        Headers requestHeaders = exchange.getRequestHeaders();
        // ...existing code...
        String contentType = null;
        for (String key : requestHeaders.keySet()) {
            if (key != null && key.equalsIgnoreCase("Content-Type")) {
                contentType = requestHeaders.getFirst(key);
                break;
            }
        }
        if (contentType == null || !contentType.startsWith("multipart/form-data")) {
            String response = "Bad Request: Content-Type must be multipart/form-data";
            exchange.sendResponseHeaders(400, response.getBytes().length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
            return;
        }

        try {
            int bIdx = contentType.toLowerCase().indexOf("boundary=");
            if (bIdx == -1) {
                String response = "Bad Request: boundary missing in Content-Type";
                exchange.sendResponseHeaders(400, response.getBytes().length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response.getBytes());
                }
                return;
            }
            String boundary = contentType.substring(bIdx + 9).trim();
            int scIdx = boundary.indexOf(';');
            if (scIdx != -1) boundary = boundary.substring(0, scIdx).trim();
            if (boundary.startsWith("\"") && boundary.endsWith("\"")) {
                boundary = boundary.substring(1, boundary.length() - 1);
            }
            
            // Check 2: Read request body with size limit (second line of defense)
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int bytesRead;
            long totalBytesRead = 0;
            
            while ((bytesRead = exchange.getRequestBody().read(buffer)) != -1) {
                totalBytesRead += bytesRead;
                if (totalBytesRead > MAX_FILE_SIZE) {
                    String response = "File too large: Maximum file size is " + (MAX_FILE_SIZE / (1024 * 1024)) + "MB";
                    exchange.sendResponseHeaders(413, response.getBytes().length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(response.getBytes());
                    }
                    return;
                }
                baos.write(buffer, 0, bytesRead);
            }
            byte[] requestData = baos.toByteArray();

            MultiParser multiParser = new MultiParser(requestData, boundary);
            MultiParser.ParseResult result = multiParser.parse();

            if (result == null) {
                String response = "Bad request: Could not parse file content";
                exchange.sendResponseHeaders(400, response.getBytes().length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response.getBytes());
                }
                return;
            }
            
            // Check 3: Validate actual file content size (third line of defense)
            if (result.fileContent != null && result.fileContent.length > MAX_FILE_SIZE) {
                String response = "File too large: Maximum file size is " + (MAX_FILE_SIZE / (1024 * 1024)) + "MB";
                exchange.sendResponseHeaders(413, response.getBytes().length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response.getBytes());
                }
                return;
            }

            String filename = result.fileName;
            if (filename == null || filename.trim().isEmpty()) {
                filename = "unnamed-file.txt";
            }
            
            // Check 4: Validate file extension (block executables and malicious files)
            if (!isAllowedExtension(filename)) {
                String response = "File type not allowed. Allowed extensions: .txt, .pdf, .jpg, .jpeg, .png, .gif, .zip, .doc, .docx, .csv";
                exchange.sendResponseHeaders(415, response.getBytes().length); // 415 Unsupported Media Type
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response.getBytes());
                }
                return;
            }
            
            // Check 5: Validate MIME type from multipart Content-Type (extra safety layer)
            String fileMimeType = result.contentType;
            if (!isAllowedMimeType(fileMimeType)) {
                String response = "MIME type not allowed. Allowed types: text/plain, application/pdf, image/jpeg, image/png, image/gif, application/zip, application/octet-stream, application/msword, text/csv";
                exchange.sendResponseHeaders(415, response.getBytes().length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response.getBytes());
                }
                return;
            }
            
            String uniqueFileName = UUID.randomUUID() + "_" + new File(filename).getName();
            String filePath = uploadDir + File.separator + uniqueFileName;

            try (FileOutputStream fos = new FileOutputStream(filePath)) {
                fos.write(result.fileContent);
            }

            int port = fileSharer.offerFile(filePath);
            String token = fileSharer.getToken(port); // Get the access token
            new Thread(() -> fileSharer.startFileServer(port)).start();

            // Return both port and token in JSON response
            String jsonResponse = "{\"port\": " + port + ", \"token\": \"" + token + "\"}";
            headers.add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, jsonResponse.getBytes().length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(jsonResponse.getBytes());
            }
        } catch (IOException ex) {
            System.err.println("Error processing file upload: " + ex.getMessage());
            String response = "Server error: " + ex.getMessage();
            exchange.sendResponseHeaders(500, response.getBytes().length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
        }
    }
}
