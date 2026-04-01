package org.arnavthakur.service;

import org.arnavthakur.utils.UploadUtils;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Hybrid FileSharer supporting both S3 relay and direct socket P2P transfers.
 *
 * Architecture:
 * - Mode 1 (S3_RELAY): For browser clients, uses AWS S3 with pre-signed URLs
 * - Works everywhere (NAT traversal solved)
 * - Browser-compatible
 * - Costs ~$0.70/month
 *
 * - Mode 2 (SOCKET_P2P): For CLI/desktop clients on same network
 * - Server-mediated TCP transfer using ephemeral port allocation (49152-65535)
 * - Heavy file I/O isolated from main HTTP loop via ExecutorService
 * - Zero server cost
 * - Requires network reachability to the backend transfer socket
 *
 * This hybrid approach demonstrates:
 * - Dynamic ephemeral port allocation strategy (resume claim ✓)
 * - Heavy I/O isolation from main application loop (resume claim ✓)
 * - Thread-safe concurrent architecture (resume claim ✓)
 * - Real-world architectural trade-off analysis
 */
public class FileSharer {
    // S3 relay mode components
    private final ConcurrentHashMap<String, String> tokenToS3Key; // Token -> S3 object key
    private final S3Service s3Service;

    // Socket P2P mode components (demonstrates ephemeral port allocation)
    private final ConcurrentHashMap<Integer, String> availableFiles; // Port -> File path
    private final ConcurrentHashMap<Integer, String> portToToken; // Port -> Token
    private final ConcurrentHashMap<String, TransferMode> tokenMode; // Token -> Transfer mode
    private final ExecutorService socketExecutor; // Isolates heavy I/O from main loop

    /**
     * Transfer mode enumeration
     */
    public enum TransferMode {
        S3_RELAY,        // Uses AWS S3 — async (upload now, download later)
        WEBSOCKET_RELAY, // Uses WebSocket relay — real-time P2P (uploader stays online)
        SOCKET_P2P       // Uses direct TCP sockets with ephemeral ports (CLI only)
    }

    // WebSocket relay mode components
    private final ConcurrentHashMap<String, RelaySession> relaySessions; // Token -> RelaySession

    public FileSharer(S3Service s3Service) {
        // S3 mode initialization (can be null for socket-only mode)
        this.tokenToS3Key = new ConcurrentHashMap<>();
        this.s3Service = s3Service;

        // Socket mode initialization (always available)
        this.availableFiles = new ConcurrentHashMap<>();
        this.portToToken = new ConcurrentHashMap<>();
        this.tokenMode = new ConcurrentHashMap<>();
        this.socketExecutor = Executors.newCachedThreadPool();

        // WebSocket relay initialization
        this.relaySessions = new ConcurrentHashMap<>();

        System.out.println("🔧 FileSharer initialized | S3: " + (s3Service != null ? "ENABLED" : "DISABLED")
                + " | WebSocket Relay: ENABLED | Socket P2P: ENABLED");
    }

    /**
     * Generate a random 6-digit access token
     */
    private String generateAccessToken() {
        // URL-safe character set (64 characters)
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
        java.security.SecureRandom random = new java.security.SecureRandom();
        StringBuilder sb = new StringBuilder(12);
        for (int i = 0; i < 12; i++) {
            sb.append(chars.charAt(random.nextInt(chars.length())));
        }
        return sb.toString();
    }

    /**
     * Check if S3 mode is available
     * 
     * @return true if S3 service is configured and available
     */
    public boolean isS3Available() {
        return s3Service != null;
    }

    /**
     * Upload file via S3 relay (for browser clients)
     * 
     * @param fileContent File bytes
     * @param filename    Original filename
     * @return Access token
     */
    public String uploadFile(InputStream fileContentStream, long contentLength, String filename, String contentType) {
        return uploadFileViaS3(fileContentStream, contentLength, filename, contentType);
    }

    /**
     * Upload file via S3 relay (for browser clients)
     * 
     * @param fileContent File bytes
     * @param filename    Original filename
     * @return Access token
     */
    public String uploadFileViaS3(InputStream fileContentStream, long contentLength, String filename,
            String contentType) {
        if (s3Service == null) {
            throw new RuntimeException(
                    "S3 mode not available - AWS credentials not provided. Use Socket P2P mode instead.");
        }

        String token = generateAccessToken();
        String s3Key = s3Service.uploadFile(token, fileContentStream, contentLength, filename, contentType);
        tokenToS3Key.put(token, s3Key);
        tokenMode.put(token, TransferMode.S3_RELAY);
        System.out.println("📤 [S3 RELAY] File uploaded. Token: " + token);
        return token;
    }

    /**
     * Register a WebSocket relay share (metadata only, no file stored).
     * File streams directly from uploader's browser through server to downloader.
     */
    public String registerWebSocketRelay(String filename, long fileSize, String contentType) {
        String token = generateAccessToken();
        RelaySession session = new RelaySession(token, filename, fileSize, contentType);
        relaySessions.put(token, session);
        tokenMode.put(token, TransferMode.WEBSOCKET_RELAY);
        return token;
    }

    /**
     * Get relay session by token (WebSocket mode)
     */
    public RelaySession getRelaySession(String token) {
        return relaySessions.get(token);
    }

    /**
     * Upload file via socket P2P (for CLI/desktop clients)
     * Demonstrates dynamic ephemeral port allocation strategy
     * 
     * @param filePath Local file path
     * @return Access token
     */
    public String uploadFileViaSocket(String filePath) {
        String safePath = normalizeAndValidatePath(filePath);
        String token = generateAccessToken();
        int port = allocateEphemeralPort();

        availableFiles.put(port, safePath);
        portToToken.put(port, token);
        tokenMode.put(token, TransferMode.SOCKET_P2P);

        socketExecutor.submit(() -> startFileServer(port));
        System.out.println("📤 [SOCKET P2P] File offered. Token: " + token + " | Ephemeral Port: " + port);
        return token;
    }

    /**
     * Allocate ephemeral port in range 49152-65535 (IANA recommended)
     * This demonstrates dynamic port allocation strategy from resume
     */
    private int allocateEphemeralPort() {
        int port;
        int attempts = 0;
        while (attempts < 100) {
            port = UploadUtils.generatePort(); // Returns port in 49152-65535 range
            if (!availableFiles.containsKey(port)) {
                return port;
            }
            attempts++;
        }
        throw new RuntimeException("Could not allocate ephemeral port after 100 attempts");
    }

    /**
     * Get transfer mode for token
     */
    public TransferMode getTransferMode(String token) {
        return tokenMode.getOrDefault(token, TransferMode.S3_RELAY);
    }

    /**
     * Generate download URL/info based on transfer mode
     * 
     * @param token Access token
     * @return Download URL or connection info
     */
    public String generateDownloadUrl(String token) {
        TransferMode mode = getTransferMode(token);

        if (mode == TransferMode.S3_RELAY) {
            // S3 mode: Return pre-signed URL
            String s3Key = getS3Key(token);
            if (s3Key == null) {
                System.out.println("❌ Token not found: " + token);
                return null;
            }
            return s3Service.generatePresignedUrl(s3Key);
        } else {
            // Socket mode: Return connection info for CLI client
            Integer port = getPortByToken(token);
            if (port == null) {
                System.out.println("❌ Token not found: " + token);
                return null;
            }
            return "socket://localhost:" + port;
        }
    }

    /**
     * Get S3 key by token (S3 mode)
     * 
     * @param token 6-digit access token
     * @return S3 object key or null
     */
    private String getS3Key(String token) {
        if (s3Service == null) {
            return null; // S3 not available
        }

        // First check in-memory cache
        String s3Key = tokenToS3Key.get(token);
        if (s3Key != null) {
            return s3Key;
        }

        // Fallback: search S3 bucket (in case server restarted)
        s3Key = s3Service.findS3KeyByToken(token);
        if (s3Key != null) {
            tokenToS3Key.put(token, s3Key); // Cache for future requests
            System.out.println("📥 Found token in S3: " + token);
        }
        return s3Key;
    }

    /**
     * Get port by token (Socket mode)
     */
    public Integer getPortByToken(String token) {
        for (var entry : portToToken.entrySet()) {
            if (entry.getValue().equals(token)) {
                return entry.getKey();
            }
        }
        return null;
    }

    /**
     * Get file path for socket transfer
     */
    public String getFilePath(int port) {
        return availableFiles.get(port);
    }

    /**
     * Validate token exists (mode-aware)
     * 
     * @param token Access token
     * @return true if valid
     */
    public boolean validateToken(String token) {
        TransferMode mode = getTransferMode(token);
        if (mode == TransferMode.S3_RELAY) {
            return getS3Key(token) != null;
        } else if (mode == TransferMode.WEBSOCKET_RELAY) {
            return relaySessions.containsKey(token);
        } else {
            return getPortByToken(token) != null;
        }
    }

    public S3Service.DownloadedObject downloadFileFromS3(String token) {
        String s3Key = getS3Key(token);
        if (s3Key == null || s3Service == null) {
            return null;
        }
        return s3Service.downloadFile(s3Key);
    }

    private static final java.nio.file.Path SAFE_BASE_DIR = java.nio.file.Paths.get("uploads").toAbsolutePath()
            .normalize();

    private String normalizeAndValidatePath(String inputPath) {

        if (inputPath == null || inputPath.isBlank()) {
            throw new SecurityException("Invalid file path");
        }

        java.nio.file.Path input = java.nio.file.Paths.get(inputPath).normalize();

        // If absolute path is provided (e.g., temp uploaded file), allow it only if it
        // exists and is a file
        if (input.isAbsolute()) {
            if (!java.nio.file.Files.exists(input) || !java.nio.file.Files.isRegularFile(input)) {
                throw new SecurityException("Invalid file path");
            }
            return input.toString();
        }
        java.nio.file.Path resolved = SAFE_BASE_DIR.resolve(inputPath).normalize();
        if (!resolved.startsWith(SAFE_BASE_DIR)) {
            throw new SecurityException("Invalid file path");
        }
        if (!java.nio.file.Files.exists(resolved) || !java.nio.file.Files.isRegularFile(resolved)) {
            throw new SecurityException("Invalid file path");
        }
        return resolved.toString();
    }

    /**
     * Start socket server for P2P transfer on ephemeral port
     * Runs in separate thread to isolate heavy file I/O from main HTTP loop
     */
    // java
    private void startFileServer(int port) {
        String filePath = availableFiles.get(port);
        if (filePath == null) {
            System.err.println("❌ No file associated with port: " + port);
            return;
        }

        String expectedToken = portToToken.get(port);
        if (expectedToken == null) {
            System.err.println("❌ No token associated with port: " + port);
            return;
        }

        try (ServerSocket serverSocket = new ServerSocket(port)) {
            serverSocket.setSoTimeout(300_000); // 5 minute accept timeout
            System.out.println("🔌 [TCP TRANSFER] Listening on ephemeral port " + port);

            try (Socket clientSocket = serverSocket.accept()) {
                clientSocket.setSoTimeout(10_000); // 10s for handshake/read

                try (java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(clientSocket.getInputStream(),
                                java.nio.charset.StandardCharsets.UTF_8));
                        java.io.OutputStream out = clientSocket.getOutputStream()) {

                    // Expect a single-line token handshake from client
                    String receivedToken = reader.readLine();
                    if (receivedToken == null || !expectedToken.equals(receivedToken.trim())) {
                        out.write(("ERROR: Unauthorized\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        out.flush();
                        System.err.println("❌ Unauthorized connection attempt on port " + port);
                        return;
                    }

                    System.out.println("🤝 Authorized client connected from: " + clientSocket.getInetAddress());

                    // Send filename header then stream file
                    java.io.File file = new java.io.File(filePath);
                    String header = "Filename: " + file.getName() + "\n";
                    out.write(header.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    out.flush();

                    try (java.io.FileInputStream fis = new java.io.FileInputStream(file)) {
                        byte[] buffer = new byte[8192];
                        int bytesRead;
                        long totalBytes = 0;
                        while ((bytesRead = fis.read(buffer)) != -1) {
                            out.write(buffer, 0, bytesRead);
                            totalBytes += bytesRead;
                        }
                        out.flush();
                        System.out.println("✅ [SOCKET P2P] File sent successfully: " + file.getName() + " ("
                                + totalBytes + " bytes)");
                    }
                }
            }
        } catch (java.io.IOException e) {
            System.err.println("❌ Socket server error on port " + port + ": " + e.getMessage());
        } finally {
            String token = portToToken.get(port);
            if (token != null) {
                cleanupAfterDownload(token);
            }
        }
    }

    /**
     * Cleanup after download (mode-aware)
     * 
     * @param token Access token
     */
    public void cleanupAfterDownload(String token) {
        TransferMode mode = tokenMode.remove(token);

        if (mode == TransferMode.S3_RELAY) {
            String s3Key = tokenToS3Key.remove(token);
            if (s3Key != null && s3Service != null) {
                s3Service.deleteFile(s3Key);
                System.out.println("🧹 [S3 RELAY] Cleanup complete for token: " + token);
            }
        } else if (mode == TransferMode.WEBSOCKET_RELAY) {
            RelaySession session = relaySessions.remove(token);
            if (session != null && session.getUploaderSocket() != null && session.getUploaderSocket().isOpen()) {
                session.getUploaderSocket().close();
            }
            System.out.println("🧹 [WS RELAY] Cleanup complete for token: " + token);
        } else if (mode == TransferMode.SOCKET_P2P) {
            Integer port = getPortByToken(token);
            if (port != null) {
                String filePath = availableFiles.remove(port);
                portToToken.remove(port);
                if (filePath != null) {
                    String safePath = normalizeAndValidatePath(filePath);
                    java.io.File file = new java.io.File(safePath);
                    if (file.exists() && file.delete()) {
                        System.out.println("🧹 [SOCKET P2P] Deleted file: " + file.getName());
                    }
                }
                System.out.println("🧹 [SOCKET P2P] Cleanup complete | Token: " + token + " | Port: " + port);
            }
        }
    }

    /**
     * Get count of active shares
     * 
     * @return Number of active file shares
     */
    public int getActiveShareCount() {
        return tokenToS3Key.size() + relaySessions.size() + availableFiles.size();
    }

    /**
     * Get mode breakdown for monitoring
     */
    public String getSharesBreakdown() {
        return String.format("Active shares - S3: %d, WebSocket: %d, Socket: %d",
                tokenToS3Key.size(), relaySessions.size(), availableFiles.size());
    }

    public void shutdown() {
        socketExecutor.shutdownNow();
        if (s3Service != null) {
            s3Service.shutdown();
        }
        System.out.println("🛑 FileSharer shutdown complete");
    }
}
