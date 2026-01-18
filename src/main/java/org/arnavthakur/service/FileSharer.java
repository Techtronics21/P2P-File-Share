package org.arnavthakur.service;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

import org.arnavthakur.utils.UploadUtils;

public class FileSharer {
    private final ConcurrentHashMap<Integer,String> availableFiles;
    private final ConcurrentHashMap<Integer,String> accessTokens; // Port -> Token mapping

    public FileSharer(){
        availableFiles = new ConcurrentHashMap<>();
        accessTokens = new ConcurrentHashMap<>();
    }

    // Generate a random 6-digit PIN
    private String generateAccessToken() {
        Random random = new Random();
        int pin = 100000 + random.nextInt(900000); // 6-digit PIN (100000-999999)
        return String.valueOf(pin);
    }

    public int offerFile(String filePath) {
        int port;
        while (true) {
            port = UploadUtils.generatePort();
            if (!availableFiles.containsKey(port)) {
                availableFiles.put(port,filePath);
                // Generate and store access token for this port
                String token = generateAccessToken();
                accessTokens.put(port, token);
                return port;
            }
        }
    }
    
    public boolean isPortAvailable(int port) {
        return availableFiles.containsKey(port);
    }
    
    // Validate token for a given port
    public boolean validateToken(int port, String token) {
        if (token == null || !accessTokens.containsKey(port)) {
            return false;
        }
        return accessTokens.get(port).equals(token);
    }
    
    // Get token for a given port (used to return in upload response)
    public String getToken(int port) {
        return accessTokens.get(port);
    }
    
    // Find port by token
    public Integer getPortByToken(String token) {
        for (var entry : accessTokens.entrySet()) {
            if (entry.getValue().equals(token)) {
                return entry.getKey();
            }
        }
        return null;
    }
    
    // Get file path for a given port
    public String getFilePath(int port) {
        return availableFiles.get(port);
    }
    
    // Cleanup file after successful download
    public void cleanupAfterDownload(int port) {
        String filePath = availableFiles.get(port);
        if (filePath != null) {
            // Delete the physical file
            File file = new File(filePath);
            if (file.exists()) {
                if (file.delete()) {
                    System.out.println("File deleted after download: " + file.getName());
                } else {
                    System.err.println("Failed to delete file: " + file.getName());
                }
            }
            
            // Remove from maps
            availableFiles.remove(port);
            accessTokens.remove(port);
            System.out.println("Cleaned up port " + port + " and associated token");
        }
    }
    
    public void startFileServer(int port){
        String filePath = availableFiles.get(port);
        if(filePath == null){
            System.out.println("No files is associated with this port: " + port);
            return;
        }
        try (ServerSocket serverSocket = new ServerSocket(port)){
            serverSocket.setSoTimeout(50000); // 50 seconds timeout for accept()
            System.out.println("Serving File" + new File(filePath).getName() + "on port " + port);
            Socket clientScoket = serverSocket.accept();
            clientScoket.setSoTimeout(50000); // 50 seconds timeout for client socket
            System.out.println("Client connection: "+clientScoket.getInetAddress());
            new Thread(new FileSenderHandler(clientScoket,filePath)).start();
        }catch (IOException e){
            System.err.println("Error Handling file server on port : " + port);
        }
    }

    private static class FileSenderHandler implements Runnable{
        private final Socket clientSocket;
        private final String filePath;

        public FileSenderHandler(Socket clientSocket, String filePath) {
            this.clientSocket = clientSocket;
            this.filePath = filePath;
        }

        @Override
        public void run() {
            try {
                clientSocket.setSoTimeout(30000); // 30 seconds timeout for client socket
                try(FileInputStream fis = new FileInputStream(filePath)) {
                    OutputStream oos = clientSocket.getOutputStream();
                    String fileName = new File(filePath).getName();
                    String header = "Filename: " + fileName + "\n";
                    oos.write(header.getBytes());

                    byte[] buffer = new byte[4096];
                    int byteRead;
                    while((byteRead = fis.read(buffer)) != -1){
                        oos.write(buffer,0,byteRead);
                    }
                    System.out.println("File "+fileName+ " sent to " + clientSocket.getInetAddress());
                }
            } catch (IOException ex) {
                System.err.println("Error sending file to the client " + ex.getMessage());
            } finally {
                try {
                    clientSocket.close();
                } catch (IOException e) {
                    System.err.println("Error closing socket " + e.getMessage());
                }
            }
        }
    }
}
