package org.arnavthakur.service;

import java.io.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hybrid FileSharer supporting both S3 relay and WebSocket relay transfers.
 *
 * Architecture:
 * - Mode 1 (S3_RELAY): For browser clients, uses AWS S3 with pre-signed URLs
 * - Works everywhere (NAT traversal solved)
 * - Browser-compatible
 * - Costs ~$0.70/month
 *
 * - Mode 2 (WEBSOCKET_RELAY): Real-time streaming between browsers
 * - Zero disk I/O, streams binary frames directly
 * - Requires both users to be online simultaneously
 */
public class FileSharer {
    // S3 relay mode components
    private final ConcurrentHashMap<String, String> tokenToS3Key; // Token -> S3 object key
    private final S3Service s3Service;

    private final ConcurrentHashMap<String, TransferMode> tokenMode; // Token -> Transfer mode

    /**
     * Transfer mode enumeration
     */
    public enum TransferMode {
        S3_RELAY,        // Uses AWS S3 — async (upload now, download later)
        WEBSOCKET_RELAY  // Uses WebSocket relay — real-time P2P (uploader stays online)
    }

    // WebSocket relay mode components
    private final ConcurrentHashMap<String, RelaySession> relaySessions; // Token -> RelaySession

    public FileSharer(S3Service s3Service) {
        // S3 mode initialization (can be null for socket-only mode)
        this.tokenToS3Key = new ConcurrentHashMap<>();
        this.s3Service = s3Service;

        this.tokenMode = new ConcurrentHashMap<>();

        // WebSocket relay initialization
        this.relaySessions = new ConcurrentHashMap<>();

        System.out.println("🔧 FileSharer initialized | S3: " + (s3Service != null ? "ENABLED" : "DISABLED")
                + " | WebSocket Relay: ENABLED");
    }

    /**
     * Generate a cryptographically secure, case-insensitive, readable access token.
     * Uses a 12-character format with 32 possible uppercase characters (60 bits of entropy).
     * Excludes similar-looking characters: O, 0, I, 1, L.
     */
    private String generateAccessToken() {
        // Upper-case only, readable character set (32 chars)
        String chars = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
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
        if (token == null) return null;
        return relaySessions.get(token.toUpperCase());
    }


    /**
     * Get transfer mode for token. Returns null if token is invalid or expired.
     */
    public TransferMode getTransferMode(String token) {
        if (token == null) return null;
        return tokenMode.get(token.toUpperCase()); 
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
        }
        return null;
    }

    /**
     * Get S3 key by token (S3 mode)
     * 
     * @param token 6-digit access token
     * @return S3 object key or null
     */
    private String getS3Key(String token) {
        if (token == null || s3Service == null) {
            return null; // S3 not available
        }

        String normalizedToken = token.toUpperCase();
        // First check in-memory cache
        String s3Key = tokenToS3Key.get(normalizedToken);
        if (s3Key != null) {
            return s3Key;
        }

        // Fallback: search S3 bucket (in case server restarted)
        s3Key = s3Service.findS3KeyByToken(normalizedToken);
        if (s3Key != null) {
            tokenToS3Key.put(normalizedToken, s3Key); // Cache for future requests
            System.out.println("📥 Found token in S3: " + normalizedToken);
        }
        return s3Key;
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
        }
        return false;
    }

    public S3Service.DownloadedObject downloadFileFromS3(String token) {
        String s3Key = getS3Key(token);
        if (s3Key == null || s3Service == null) {
            return null;
        }
        return s3Service.downloadFile(s3Key);
    }


    /**
     * Cleanup after download (mode-aware)
     * 
     * @param token Access token
     */
    public void cleanupAfterDownload(String token) {
        if (token == null) return;
        String normalizedToken = token.toUpperCase();
        TransferMode mode = tokenMode.remove(normalizedToken);

        if (mode == TransferMode.S3_RELAY) {
            String s3Key = tokenToS3Key.remove(normalizedToken);
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
        }
    }

    /**
     * Get count of active shares
     * 
     * @return Number of active file shares
     */
    public int getActiveShareCount() {
        return tokenToS3Key.size() + relaySessions.size();
    }

    /**
     * Get mode breakdown for monitoring
     */
    public String getSharesBreakdown() {
        return String.format("Active shares - S3: %d, WebSocket: %d",
                tokenToS3Key.size(), relaySessions.size());
    }

    public void shutdown() {
        if (s3Service != null) {
            s3Service.shutdown();
        }
        System.out.println("🛑 FileSharer shutdown complete");
    }
}
