package org.arnavthakur.service;

import java.util.concurrent.LinkedBlockingQueue;
import org.java_websocket.WebSocket;

/**
 * Represents an active WebSocket relay session between an uploader and a downloader.
 *
 * Lifecycle:
 * 1. Created when uploader POSTs to /register (metadata only, no file data)
 * 2. Uploader opens WebSocket → uploaderSocket is set
 * 3. Downloader requests file → server sends SEND_FILE to uploader
 * 4. Uploader sends binary frames → dataQueue → DownloadHandler streams to HTTP response
 * 5. Uploader sends TRANSFER_COMPLETE → END_SENTINEL put in queue
 * 6. Cleanup: token removed, WebSocket closed
 *
 * Thread safety: dataQueue is a LinkedBlockingQueue (producer = WS thread, consumer = HTTP thread).
 * Volatile fields ensure visibility across threads without locks.
 */
public class RelaySession {

    /** Sentinel value placed in the queue to signal end-of-stream. */
    public static final byte[] END_SENTINEL = new byte[0];

    private final String token;
    private final String filename;
    private final long fileSize;
    private final String contentType;

    /**
     * Bounded queue bridging the WebSocket thread (producer) and HTTP handler thread (consumer).
     * Capacity of 100 chunks × 64KB = ~6.4MB max buffered, provides natural backpressure.
     */
    private final LinkedBlockingQueue<byte[]> dataQueue = new LinkedBlockingQueue<>(100);

    private volatile WebSocket uploaderSocket;
    private volatile boolean uploaderConnected;
    private volatile String error;

    public RelaySession(String token, String filename, long fileSize, String contentType) {
        this.token = token;
        this.filename = filename;
        this.fileSize = fileSize;
        this.contentType = (contentType == null || contentType.isBlank())
                ? "application/octet-stream" : contentType;
    }

    public String getToken() { return token; }
    public String getFilename() { return filename; }
    public long getFileSize() { return fileSize; }
    public String getContentType() { return contentType; }
    public LinkedBlockingQueue<byte[]> getDataQueue() { return dataQueue; }

    public WebSocket getUploaderSocket() { return uploaderSocket; }
    public void setUploaderSocket(WebSocket socket) { this.uploaderSocket = socket; }

    public boolean isUploaderConnected() { return uploaderConnected; }
    public void setUploaderConnected(boolean connected) { this.uploaderConnected = connected; }

    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
}
