//package org.arnavthakur.client;
//
//import java.io.*;
//import java.net.HttpURLConnection;
//import java.net.Socket;
//import java.net.URL;
//import java.nio.file.Files;
//import java.nio.file.Paths;
//import java.util.regex.Matcher;
//import java.util.regex.Pattern;
//
///**
// * CLI client for PeerLink demonstrating Socket P2P mode
// *
// * Usage:
// *   java -cp target/classes org.arnavthakur.client.SocketClient upload /path/to/file.pdf
// *   java -cp target/classes org.arnavthakur.client.SocketClient download 123456
// *
// * This demonstrates:
// * - Dedicated TCP transfer connections using ephemeral ports
// * - A server-mediated alternative to the S3 relay path
// * - Lower protocol overhead than browser-compatible relay flows
// */
//public class SocketClient {
//    private static final String SERVER_URL = "http://localhost:8080";
//    private static final Pattern TOKEN_PATTERN = Pattern.compile("\"token\"\\s*:\\s*\"([^\"]+)\"");
//    private static final Pattern PORT_PATTERN = Pattern.compile("\"port\"\\s*:\\s*(\\d+)");
//    private static final Pattern HOST_PATTERN = Pattern.compile("\"host\"\\s*:\\s*\"([^\"]+)\"");
//    private static final Pattern FILENAME_PATTERN = Pattern.compile("\"filename\"\\s*:\\s*\"([^\"]+)\"");
//
//    public static void main(String[] args) {
//        if (args.length < 2) {
//            printUsage();
//            return;
//        }
//
//        String command = args[0].toLowerCase();
//
//        try {
//            if ("upload".equals(command)) {
//                uploadFile(args[1]);
//            } else if ("download".equals(command)) {
//                downloadFile(args[1]);
//            } else {
//                printUsage();
//            }
//        } catch (Exception e) {
//            System.err.println("❌ Error: " + e.getMessage());
//            e.printStackTrace();
//        }
//    }
//
//    private static void uploadFile(String filePath) throws IOException {
//        System.out.println("📤 Uploading file via Socket P2P mode: " + filePath);
//
//        File file = new File(filePath);
//        if (!file.exists()) {
//            throw new IOException("File not found: " + filePath);
//        }
//
//        // Read file content
//        byte[] fileContent = Files.readAllBytes(Paths.get(filePath));
//
//        // Prepare multipart request
//        String boundary = "----WebKitFormBoundary" + System.currentTimeMillis();
//        ByteArrayOutputStream baos = new ByteArrayOutputStream();
//
//        // Build multipart body
//        String header = "--" + boundary + "\r\n" +
//                       "Content-Disposition: form-data; name=\"file\"; filename=\"" + file.getName() + "\"\r\n" +
//                       "Content-Type: application/octet-stream\r\n\r\n";
//        baos.write(header.getBytes());
//        baos.write(fileContent);
//        baos.write(("\r\n--" + boundary + "--\r\n").getBytes());
//
//        byte[] requestBody = baos.toByteArray();
//
//        // Send upload request with X-Transfer-Mode header
//        URL url = new URL(SERVER_URL + "/upload");
//        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
//        conn.setDoOutput(true);
//        conn.setRequestMethod("POST");
//        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
//        conn.setRequestProperty("X-Transfer-Mode", "socket"); // Request socket mode
//        conn.setRequestProperty("Content-Length", String.valueOf(requestBody.length));
//
//        try (OutputStream os = conn.getOutputStream()) {
//            os.write(requestBody);
//        }
//
//        int responseCode = conn.getResponseCode();
//        if (responseCode == 200) {
//            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
//            String response = reader.readLine();
//            reader.close();
//
//            String token = extractJsonString(response, TOKEN_PATTERN, "token");
//            System.out.println("✅ Upload successful!");
//            System.out.println("🔑 Access Token: " + token);
//            System.out.println("📋 Share this token to allow downloads via Socket P2P");
//        } else {
//            throw new IOException("Upload failed: HTTP " + responseCode);
//        }
//    }
//
//    private static void downloadFile(String token) throws IOException {
//        System.out.println("📥 Downloading file with token: " + token);
//
//        // Get socket transfer info from server
//        URL url = new URL(SERVER_URL + "/download?token=" + token);
//        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
//        conn.setRequestMethod("GET");
//        conn.setRequestProperty("X-Download-Mode", "socket");
//
//        int responseCode = conn.getResponseCode();
//        if (responseCode != 200) {
//            throw new IOException("Failed to get download info: HTTP " + responseCode);
//        }
//
//        BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
//        String response = reader.readLine();
//        reader.close();
//
//        int port = Integer.parseInt(extractJsonString(response, PORT_PATTERN, "port"));
//        String host = extractJsonString(response, HOST_PATTERN, "host");
//        String advertisedFilename = extractJsonString(response, FILENAME_PATTERN, "filename");
//
//        System.out.println("🔌 Connecting to TCP transfer socket on " + host + ":" + port + "...");
//
//        try (Socket socket = new Socket(host, port);
//             InputStream in = socket.getInputStream()) {
//
//            // Read filename header
//            ByteArrayOutputStream headerBaos = new ByteArrayOutputStream();
//            int b;
//            while ((b = in.read()) != -1 && b != '\n') {
//                headerBaos.write(b);
//            }
//            String header = headerBaos.toString().trim();
//            String filename = header.startsWith("Filename: ")
//                    ? header.substring("Filename: ".length())
//                    : advertisedFilename;
//
//            System.out.println("📄 Downloading: " + filename);
//
//            // Download file content
//            File outputFile = new File(filename);
//            try (FileOutputStream fos = new FileOutputStream(outputFile)) {
//                byte[] buffer = new byte[8192];
//                int bytesRead;
//                long totalBytes = 0;
//                while ((bytesRead = in.read(buffer)) != -1) {
//                    fos.write(buffer, 0, bytesRead);
//                    totalBytes += bytesRead;
//                }
//                System.out.println("✅ Download complete: " + filename + " (" + totalBytes + " bytes)");
//                System.out.println("💾 Saved to: " + outputFile.getAbsolutePath());
//            }
//        }
//    }
//
//    private static void printUsage() {
//        System.out.println("PeerLink Socket P2P Client");
//        System.out.println("===========================");
//        System.out.println();
//        System.out.println("Usage:");
//        System.out.println("  Upload:   java -cp target/classes org.arnavthakur.client.SocketClient upload <file-path>");
//        System.out.println("  Download: java -cp target/classes org.arnavthakur.client.SocketClient download <token>");
//        System.out.println();
//        System.out.println("Examples:");
//        System.out.println("  java -cp target/classes org.arnavthakur.client.SocketClient upload document.pdf");
//        System.out.println("  java -cp target/classes org.arnavthakur.client.SocketClient download 123456");
//        System.out.println();
//        System.out.println("Note: Socket mode requires server running on localhost:8080");
//    }
//
//    private static String extractJsonString(String response, Pattern pattern, String fieldName) throws IOException {
//        Matcher matcher = pattern.matcher(response);
//        if (!matcher.find()) {
//            throw new IOException("Missing field in server response: " + fieldName);
//        }
//        return matcher.group(1);
//    }
//}
package org.arnavthakur.client;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SocketClient {
    // Removed the hardcoded SERVER_URL constant
    private static final Pattern TOKEN_PATTERN = Pattern.compile("\"token\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern PORT_PATTERN = Pattern.compile("\"port\"\\s*:\\s*(\\d+)");
    private static final Pattern HOST_PATTERN = Pattern.compile("\"host\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern FILENAME_PATTERN = Pattern.compile("\"filename\"\\s*:\\s*\"([^\"]+)\"");

    public static void main(String[] args) {
        String serverUrl = "http://localhost:8080"; // Default fallback
        String command = null;
        String param = null;

        // Parse command line arguments to make the client portable
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--host") && i + 1 < args.length) {
                serverUrl = "http://" + args[i + 1];
                i++; // Skip the next argument since we just consumed it
            } else if (command == null) {
                command = args[i];
            } else if (param == null) {
                param = args[i];
            }
        }

        if (command == null || param == null) {
            printUsage();
            return;
        }

        try {
            if ("upload".equalsIgnoreCase(command)) {
                uploadFile(param, serverUrl);
            } else if ("download".equalsIgnoreCase(command)) {
                downloadFile(param, serverUrl);
            } else {
                System.err.println("❌ Unknown command: " + command);
                printUsage();
            }
        } catch (Exception e) {
            System.err.println("❌ Error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void uploadFile(String filePath, String serverUrl) throws Exception {
        File file = new File(filePath);
        if (!file.exists()) {
            throw new FileNotFoundException("File not found: " + filePath);
        }

        System.out.println("📡 Contacting API at " + serverUrl + "...");
        URL url = new URL(serverUrl + "/upload");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setDoOutput(true);
        conn.setRequestMethod("POST");
        String boundary = "----PeerLinkBoundary" + System.currentTimeMillis();
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

        try (OutputStream outputStream = conn.getOutputStream();
             PrintWriter writer = new PrintWriter(new OutputStreamWriter(outputStream, "UTF-8"), true);
             FileInputStream inputStream = new FileInputStream(file)) {

            writer.append("--").append(boundary).append("\r\n");
            writer.append("Content-Disposition: form-data; name=\"file\"; filename=\"").append(file.getName()).append("\"\r\n");
            writer.append("Content-Type: application/octet-stream\r\n\r\n");
            writer.flush();

            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }
            outputStream.flush();

            writer.append("\r\n").append("--").append(boundary).append("--\r\n");
            writer.flush();
        }

        int responseCode = conn.getResponseCode();
        if (responseCode == 200) {
            String response = new String(conn.getInputStream().readAllBytes());
            String token = extractJsonString(response, TOKEN_PATTERN, "token");
            System.out.println("✅ Upload successful!");
            System.out.println("🔑 Share this token to download: " + token);
        } else {
            System.err.println("❌ Upload failed. HTTP Code: " + responseCode);
            System.err.println("Server Response: " + new String(conn.getErrorStream().readAllBytes()));
        }
    }

    private static void downloadFile(String token, String serverUrl) throws Exception {
        System.out.println("📡 Requesting download info for token: " + token + " from " + serverUrl);
        URL url = new URL(serverUrl + "/download?token=" + token);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");

        int responseCode = conn.getResponseCode();
        if (responseCode != 200) {
            System.err.println("❌ Failed to get download info. HTTP Code: " + responseCode);
            return;
        }

        String response = new String(conn.getInputStream().readAllBytes());
        String mode = response.contains("\"port\"") ? "SOCKET" : "S3";

        if ("SOCKET".equals(mode)) {
            int port = Integer.parseInt(extractJsonString(response, PORT_PATTERN, "port"));
            String host = extractJsonString(response, HOST_PATTERN, "host");
            String filename = extractJsonString(response, FILENAME_PATTERN, "filename");

            System.out.println("🔄 [SOCKET MODE] Connecting directly to peer at " + host + ":" + port);

            File outputFile = new File("downloaded_" + filename);
            try (Socket socket = new Socket(host, port);
                 InputStream in = socket.getInputStream();
                 FileOutputStream out = new FileOutputStream(outputFile)) {

                byte[] buffer = new byte[8192];
                int bytesRead;
                long totalBytes = 0;

                System.out.println("⬇️ Downloading file data...");
                while ((bytesRead = in.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                    totalBytes += bytesRead;
                }
                System.out.println("✅ Download complete: " + filename + " (" + totalBytes + " bytes)");
                System.out.println("💾 Saved to: " + outputFile.getAbsolutePath());
            }
        }
    }

    private static void printUsage() {
        System.out.println("PeerLink Socket P2P Client");
        System.out.println("===========================");
        System.out.println("Usage:");
        System.out.println("  Upload:   java -cp target/classes org.arnavthakur.client.SocketClient upload <file-path> [--host <ip:port>]");
        System.out.println("  Download: java -cp target/classes org.arnavthakur.client.SocketClient download <token> [--host <ip:port>]");
        System.out.println("\nExamples:");
        System.out.println("  Local:  java -cp target/classes org.arnavthakur.client.SocketClient upload doc.pdf");
        System.out.println("  Remote: java -cp target/classes org.arnavthakur.client.SocketClient download 123456 --host 192.168.1.15:8080");
    }

    private static String extractJsonString(String response, Pattern pattern, String fieldName) throws IOException {
        Matcher matcher = pattern.matcher(response);
        if (!matcher.find()) {
            throw new IOException("Missing field in server response: " + fieldName);
        }
        return matcher.group(1);
    }
}