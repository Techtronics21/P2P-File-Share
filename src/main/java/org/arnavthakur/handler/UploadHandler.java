package org.arnavthakur.handler;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.UUID;
import java.util.regex.Pattern;

import org.arnavthakur.service.FileSharer;
import org.arnavthakur.utils.FixedWindowRateLimiter;
import org.arnavthakur.utils.HeaderUtils;
import org.arnavthakur.utils.StreamingMultipartParser;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

public class UploadHandler implements HttpHandler {
    private final FileSharer fileSharer;
    // Maximum file size: 500MB
    private static final long MAX_FILE_SIZE = 500L * 1024 * 1024; // 500MB in bytes

    private static final int MAX_UPLOADS_PER_MINUTE = 10; // Maximum uploads allowed per minute
    private static final long ONE_MINUTE_MS = 60_000; // One minute in milliseconds
    private static final Pattern CONTENT_LENGTH_PATTERN = Pattern.compile("^\\d+$");

    // Allowed file extensions and MIME types (security whitelist)
    private static final String[] ALLOWED_EXTENSIONS = {
            ".txt", ".pdf", ".jpg", ".jpeg", ".png", ".gif", ".zip", ".doc", ".docx", ".csv"
    };
    private static final String[] ALLOWED_MIME_TYPES = {
            "text/plain", "application/pdf", "image/jpeg", "image/png", "image/gif",
            "application/zip", "application/x-zip-compressed", "application/x-zip", "application/octet-stream",
            "application/msword", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "text/csv"
    }; // mime types is different from extensions because some browsers send generic
       // types

    private static final FixedWindowRateLimiter uploadRateLimiter = new FixedWindowRateLimiter(MAX_UPLOADS_PER_MINUTE,
            ONE_MINUTE_MS);

    public UploadHandler(FileSharer fileSharer) {
        this.fileSharer = fileSharer;
    }

    // Helper method to check if file extension is allowed
    private boolean isAllowedExtension(String filename) {
        if (filename == null)
            return false;
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
        if (mimeType == null)
            return false;
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
        if (!uploadRateLimiter.allow(userIp)) {
            String response = "Rate limit exceeded: Max " + MAX_UPLOADS_PER_MINUTE + " uploads per minute.";
            exchange.sendResponseHeaders(429, response.getBytes().length); // 429 Too Many Requests
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
            return;
        }

        Headers requestHeaders = exchange.getRequestHeaders();
        String contentLengthHeader = requestHeaders.getFirst("Content-Length");
        if (contentLengthHeader != null && CONTENT_LENGTH_PATTERN.matcher(contentLengthHeader.trim()).matches()) {
            long contentLength = Long.parseLong(contentLengthHeader.trim());
            if (contentLength > MAX_FILE_SIZE) {
                String response = "File too large: Maximum file size is " + (MAX_FILE_SIZE / (1024 * 1024)) + "MB";
                exchange.sendResponseHeaders(413, response.getBytes().length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response.getBytes());
                }
                return;
            }
        }

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
            if (scIdx != -1)
                boundary = boundary.substring(0, scIdx).trim();
            if (boundary.startsWith("\"") && boundary.endsWith("\"")) {
                boundary = boundary.substring(1, boundary.length() - 1);
            }

            String uploadDir = System.getProperty("java.io.tmpdir") + java.io.File.separator + "peerlink-uploads";
            File dir = new File(uploadDir);
            if (!dir.exists()) {
                dir.mkdirs();
            }

            StreamingMultipartParser parser = new StreamingMultipartParser(exchange.getRequestBody(), boundary,
                    MAX_FILE_SIZE);
            StreamingMultipartParser.PartHeaders partHeaders = parser.readHeaders();

            String filename = partHeaders.getFileName();
            if (filename == null || filename.trim().isEmpty()) {
                filename = "unnamed-file.txt";
            }
            filename = HeaderUtils.sanitizeFilename(filename, "unnamed-file.txt");

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
            String fileMimeType = partHeaders.getContentType();
            if (!isAllowedMimeType(fileMimeType)) {
                String response = "MIME type not allowed. Allowed types: text/plain, application/pdf, image/jpeg, image/png, image/gif, application/zip, application/octet-stream, application/msword, text/csv";
                exchange.sendResponseHeaders(415, response.getBytes().length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response.getBytes());
                }
                return;
            }

            // S3 upload mode

            String token;
            String uniqueFileName = UUID.randomUUID() + "_" + filename;
            File tempFile = new File(dir, uniqueFileName);
            long streamedFileSize;

            try (FileOutputStream fos = new FileOutputStream(tempFile)) {
                streamedFileSize = parser.streamPart(fos);
            } catch (IOException e) {
                if (tempFile.exists()) {
                    tempFile.delete();
                }
                if (e.getMessage() != null && e.getMessage().startsWith("File too large")) {
                    String response = e.getMessage();
                    exchange.sendResponseHeaders(413, response.getBytes().length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(response.getBytes());
                    }
                    return;
                }
                throw e;
            }

            try (FileInputStream fis = new FileInputStream(tempFile)) {
                token = fileSharer.uploadFile(fis, streamedFileSize, filename, fileMimeType);
                System.out.println("✅ [S3 RELAY] Upload successful | IP: " + userIp + " | Token: " + token
                        + " | File: " + filename);
            } finally {
                if (tempFile.exists()) {
                    tempFile.delete();
                }
            }

            // Return token in JSON response
            StringBuilder sb = new StringBuilder();
            sb.append('{');
            sb.append("\"token\": \"").append(token).append('\"');
            sb.append('}');
            String jsonResponse = sb.toString();
            headers.add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, jsonResponse.getBytes().length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(jsonResponse.getBytes());
            }
        } catch (Exception ex) {
            System.err.println("❌ Error processing file upload: " + ex.getMessage());
            ex.printStackTrace();
            String response = "{\"error\": \"" + ex.getMessage().replace("\"", "'") + "\"}";
            headers.add("Content-Type", "application/json");
            try {
                exchange.sendResponseHeaders(500, response.getBytes().length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response.getBytes());
                }
            } catch (IOException e) {
                System.err.println("Failed to send error response: " + e.getMessage());
            }
        }
    }
}
