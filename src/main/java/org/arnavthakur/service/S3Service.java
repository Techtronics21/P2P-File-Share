package org.arnavthakur.service;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

import java.io.InputStream;
import java.time.Duration;
import java.util.Map;

public class S3Service {
    private final S3Client s3Client;
    private final S3Presigner s3Presigner;
    private final String bucketName;

    public S3Service(String accessKeyId, String secretAccessKey, String region, String bucketName) {
        AwsBasicCredentials credentials = AwsBasicCredentials.create(accessKeyId, secretAccessKey);

        // Explicitly use URL connection HTTP client to avoid classpath conflicts
        this.s3Client = S3Client.builder()
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(credentials))
                .httpClientBuilder(software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient.builder())
                .build();

        this.s3Presigner = S3Presigner.builder()
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(credentials))
                .build();

        this.bucketName = bucketName;
    }

    /**
     * Upload file to S3 with token-based key
     * @param token 6-digit access token
     * @param fileContent File bytes
     * @param originalFilename Original file name
     * @return S3 object key (token_uuid_filename)
     */
    public String uploadFile(String token, InputStream fileContent, long contentLength, String originalFilename, String contentType) {
        String s3Key = token + "_" + java.util.UUID.randomUUID() + "_" + originalFilename;
        String resolvedContentType = (contentType == null || contentType.isBlank())
                ? "application/octet-stream"
                : contentType;

        PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(s3Key)
                .contentType(resolvedContentType)
                .serverSideEncryption(ServerSideEncryption.AES256) // Encrypt at rest
                .metadata(Map.of(
                        "original-filename", originalFilename,
                        "access-token", token
                ))
                .build();

        s3Client.putObject(putObjectRequest, RequestBody.fromInputStream(fileContent, contentLength));

        System.out.println("✅ Uploaded file to S3: " + s3Key);
        return s3Key;
    }

    public DownloadedObject downloadFile(String s3Key) {
        GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                .bucket(bucketName)
                .key(s3Key)
                .build();

        ResponseInputStream<GetObjectResponse> s3Stream = s3Client.getObject(getObjectRequest);
        GetObjectResponse response = s3Stream.response();
        Map<String, String> metadata = response.metadata();
        String filename = metadata.getOrDefault("original-filename", extractFilenameFromKey(s3Key));
        String contentType = response.contentType() == null || response.contentType().isBlank()
                ? "application/octet-stream"
                : response.contentType();
        long contentLength = response.contentLength() == null ? -1 : response.contentLength();

        return new DownloadedObject(filename, contentType, contentLength, s3Stream);
    }

    /**
     * Generate a pre-signed URL for downloading (valid for 10 minutes)
     * @param s3Key S3 object key
     * @return Pre-signed download URL
     */
    public String generatePresignedUrl(String s3Key) {
        GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                .bucket(bucketName)
                .key(s3Key)
                .build();

        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(Duration.ofMinutes(10)) // URL expires in 10 minutes
                .getObjectRequest(getObjectRequest)
                .build();

        PresignedGetObjectRequest presignedRequest = s3Presigner.presignGetObject(presignRequest);
        String url = presignedRequest.url().toString();
        System.out.println("🔗 Generated pre-signed URL (expires in 10 min)");
        return url;
    }

    /**
     * Find S3 key by token (searches bucket metadata)
     * @param token 6-digit access token
     * @return S3 object key or null if not found
     */
    public String findS3KeyByToken(String token) {
        try {
            ListObjectsV2Request listRequest = ListObjectsV2Request.builder()
                    .bucket(bucketName)
                    .prefix(token + "_") // Files start with token
                    .build();

            ListObjectsV2Response listResponse = s3Client.listObjectsV2(listRequest);

            if (listResponse.contents().isEmpty()) {
                return null;
            }

            // Return the first matching file
            return listResponse.contents().get(0).key();
        } catch (Exception e) {
            System.err.println("❌ Error searching S3: " + e.getMessage());
            return null;
        }
    }

    /**
     * Delete file from S3 after download
     * @param s3Key S3 object key
     */
    public void deleteFile(String s3Key) {
        try {
            DeleteObjectRequest deleteRequest = DeleteObjectRequest.builder()
                    .bucket(bucketName)
                    .key(s3Key)
                    .build();

            s3Client.deleteObject(deleteRequest);
            System.out.println("🗑️  Deleted file from S3: " + s3Key);
        } catch (Exception e) {
            System.err.println("❌ Error deleting from S3: " + e.getMessage());
        }
    }

    /**
     * Check if file exists in S3
     * @param s3Key S3 object key
     * @return true if exists
     */
    public boolean fileExists(String s3Key) {
        try {
            HeadObjectRequest headRequest = HeadObjectRequest.builder()
                    .bucket(bucketName)
                    .key(s3Key)
                    .build();
            s3Client.headObject(headRequest);
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (Exception e) {
            System.err.println("❌ Error checking S3: " + e.getMessage());
            return false;
        }
    }

    public void shutdown() {
        try {
            s3Client.close();
            s3Presigner.close();
            System.out.println("🔌 S3 Service shutdown complete");
        } catch (Exception e) {
            System.err.println("❌ Error shutting down S3 service: " + e.getMessage());
        }
    }

    private String extractFilenameFromKey(String s3Key) {
        String[] parts = s3Key.split("_", 3);
        return parts.length == 3 ? parts[2] : s3Key;
    }

    public static class DownloadedObject implements AutoCloseable {
        private final String filename;
        private final String contentType;
        private final long contentLength;
        private final InputStream inputStream;

        public DownloadedObject(String filename, String contentType, long contentLength, InputStream inputStream) {
            this.filename = filename;
            this.contentType = contentType;
            this.contentLength = contentLength;
            this.inputStream = inputStream;
        }

        public String getFilename() {
            return filename;
        }

        public String getContentType() {
            return contentType;
        }

        public long getContentLength() {
            return contentLength;
        }

        public InputStream getInputStream() {
            return inputStream;
        }

        @Override
        public void close() throws Exception {
            inputStream.close();
        }
    }
}
