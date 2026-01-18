package org.arnavthakur.controller;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.arnavthakur.handler.CORSHandler;
import org.arnavthakur.handler.DownloadHandler;
import org.arnavthakur.handler.UploadHandler;
import org.arnavthakur.service.FileSharer;

import com.sun.net.httpserver.HttpServer;

public class FileController {
    private final FileSharer fileSharer;
    private final HttpServer httpServer;
    private final String uploadDir;
    private final ExecutorService executorService;

    public FileController(int port) throws IOException {
        this.fileSharer = new FileSharer();
        this.httpServer = HttpServer.create(new InetSocketAddress(port), 0);
        this.uploadDir = System.getProperty("java.io.tmpdir") + File.separator + "peerlink-uploads";
        this.executorService = Executors.newFixedThreadPool(10);

        File uploadDirs = new File(uploadDir);
        if (!uploadDirs.exists()) {
            uploadDirs.mkdirs();
        }

        // Wire handlers
        httpServer.createContext("/upload", new UploadHandler(uploadDir, fileSharer));
        httpServer.createContext("/download", new DownloadHandler(fileSharer));
        httpServer.createContext("/", new CORSHandler());
        httpServer.setExecutor(executorService);
    }

    public void start() {
        httpServer.start();
        System.out.println("API server started on port " + httpServer.getAddress().getPort());
    }

    public void stop() {
        httpServer.stop(0);
        executorService.shutdown();
        System.out.println("API Server stopped");
    }
}
 
