package org.arnavthakur;

import java.io.IOException;

import org.arnavthakur.controller.FileController;

public class App 
{
    public static void main( String[] args )
    {
        try {
            // Internal ports — Nginx sits in front on the external port
            // HTTP API on 8090, WebSocket relay on 8091
            int port = 8090;

            FileController fileController = new FileController(port);
            fileController.start();

            System.out.println("PeerLink server started on port " + port);
            System.out.println("WebSocket relay on port " + (port + 1));

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("Shutting down server...");
                fileController.stop();
            }));

            System.out.println("Server is running. Press Ctrl+C to stop.");
            Thread.currentThread().join();

        } catch (IOException e) {
            System.err.println("Error starting server: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        } catch (InterruptedException e) {
            System.out.println("Server interrupted.");
        }
    }
}
