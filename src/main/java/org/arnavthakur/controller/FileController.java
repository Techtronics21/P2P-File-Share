package org.arnavthakur.controller;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.arnavthakur.handler.CORSHandler;
import org.arnavthakur.handler.DownloadHandler;
import org.arnavthakur.handler.HealthHandler;
import org.arnavthakur.handler.RegisterHandler;
import org.arnavthakur.handler.RelayServer;
import org.arnavthakur.handler.UploadHandler;
import org.arnavthakur.service.FileSharer;
import org.arnavthakur.service.S3Service;

import com.sun.net.httpserver.HttpServer;

public class FileController {
    private final FileSharer fileSharer;
    private final HttpServer httpServer;
    private final ExecutorService executorService;
    private final RelayServer relayServer;

    public FileController(int port) throws IOException {
        // AWS S3 Configuration (OPTIONAL - can run in Socket-only mode without AWS)
        String awsAccessKey = System.getenv("AWS_ACCESS_KEY_ID");
        String awsSecretKey = System.getenv("AWS_SECRET_ACCESS_KEY");
        String awsRegion = System.getenv("AWS_REGION");
        String s3Bucket = System.getenv("S3_BUCKET_NAME");

        // Default values if environment variables not set
        if (awsRegion == null || awsRegion.isEmpty()) {
            awsRegion = "us-east-1";
        }
        if (s3Bucket == null || s3Bucket.isEmpty()) {
            s3Bucket = "peerlink-file-storage";
        }

        S3Service s3Service = null; // creating

        // Check if AWS credentials are provided
        if (awsAccessKey != null && !awsAccessKey.isEmpty() &&
            awsSecretKey != null && !awsSecretKey.isEmpty() &&
            !awsAccessKey.equals("PLACEHOLDER_ACCESS_KEY") &&
            !awsAccessKey.equals("PLACEHOLDER")) {

            System.out.println("🔧 Initializing S3 Service...");
            System.out.println("   Region: " + awsRegion);
            System.out.println("   Bucket: " + s3Bucket);

            try {
                s3Service = new S3Service(awsAccessKey, awsSecretKey, awsRegion, s3Bucket);
                System.out.println("✅ S3 Relay Mode: ENABLED");
            } catch (Exception e) {
                System.err.println("⚠️  S3 initialization failed: " + e.getMessage());
                System.out.println("   Continuing in Socket-only mode...");
            }
        } else {
            System.out.println("⚠️  AWS credentials not provided - Running in WEBSOCKET-ONLY MODE");
            System.out.println("   ✅ Share Now (WebSocket relay): WORKING");
            System.out.println("   ❌ Upload & Share Later (S3): DISABLED (need AWS credentials)");
            System.out.println("   💡 To enable S3: Set AWS_ACCESS_KEY_ID and AWS_SECRET_ACCESS_KEY");
        }

        this.fileSharer = new FileSharer(s3Service);

        // Bind to all network interfaces (0.0.0.0) to allow access from other devices
        this.httpServer = HttpServer.create(new InetSocketAddress(port), 0);
        this.executorService = Executors.newVirtualThreadPerTaskExecutor(); // Java 21: each blocked queue.poll() parks the virtual thread, not an OS thread → no cap on concurrent relay downloads

        // Wire handlers (no longer need uploadDir parameter)
        httpServer.createContext("/upload", new UploadHandler(fileSharer));
        httpServer.createContext("/download", new DownloadHandler(fileSharer));
        httpServer.createContext("/register", new RegisterHandler(fileSharer));
        httpServer.createContext("/health", new HealthHandler(fileSharer));
        httpServer.createContext("/", new CORSHandler());
        httpServer.setExecutor(executorService);

        // WebSocket relay server on port 8081
        this.relayServer = new RelayServer(port + 1, fileSharer);
    }

    public void start() {
        httpServer.start();
        relayServer.start();
        System.out.println("🚀 API server started on port " + httpServer.getAddress().getPort());
        System.out.println("🔌 WebSocket relay on port " + (httpServer.getAddress().getPort() + 1));
    }

    public void stop() {
        httpServer.stop(0);
        executorService.shutdown();
        try { relayServer.stop(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        fileSharer.shutdown();
        System.out.println("🛑 All servers stopped");
    }
}

