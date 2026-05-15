package org.arnavthakur;

import java.io.IOException;

import org.arnavthakur.controller.FileController;

public class App 
{
    public static void main( String[] args )
    {
        try {
            // Read port from environment (cloud providers like Railway set PORT)
            // Internally, Java always uses 8080 for HTTP and 8081 for WebSocket
            // Nginx sits in front and maps the external PORT to these internal ones
            int port = 8080;
            String envPort = System.getenv("JAVA_HTTP_PORT");
            if (envPort != null && !envPort.isEmpty()) {
                try { port = Integer.parseInt(envPort); } catch (NumberFormatException ignored) {}
            }

            FileController fileController = new FileController(port);
            fileController.start();

            System.out.println("PeerLink server started on port " + port);
            System.out.println("WebSocket relay on port " + (port + 1));

            Runtime.getRuntime().addShutdownHook(new Thread(() -> { // new thread is created to run the shutdown hook by
                System.out.println("Shutting down server...");
                fileController.stop();
            }));

            // Keep the server running (don't wait for input)
            System.out.println("Server is running. Press Ctrl+C to stop.");

            // Block indefinitely to keep server alive
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
