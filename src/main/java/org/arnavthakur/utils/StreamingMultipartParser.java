package org.arnavthakur.utils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A small streaming multipart parser for the single-file upload case used by this service.
 *
 * The parser only buffers multipart headers plus a small rolling boundary window, so memory
 * usage stays bounded with respect to the uploaded file size.
 */
public class StreamingMultipartParser {
    private static final int HEADER_BUFFER_LIMIT = 16 * 1024;
    private static final Pattern FILENAME_PATTERN = Pattern.compile("filename=\"([^\"]*)\"");
    private static final Pattern CONTENT_TYPE_PATTERN = Pattern.compile("(?im)^Content-Type:\\s*(.+)$");

    private final InputStream inputStream;
    private final byte[] boundaryBytes;
    private final long maxFileSize;
    private boolean headersRead;

    public StreamingMultipartParser(InputStream inputStream, String boundary, long maxFileSize) {
        this.inputStream = inputStream;
        this.boundaryBytes = ("\r\n--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
        this.maxFileSize = maxFileSize;
    }

    public PartHeaders readHeaders() throws IOException {
        ByteArrayOutputStream headerBuffer = new ByteArrayOutputStream();
        int matched = 0;
        byte[] terminator = "\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);

        while (matched < terminator.length) {
            int next = inputStream.read();
            if (next == -1) {
                throw new IOException("Unexpected end of stream while reading multipart headers");
            }

            headerBuffer.write(next);
            if (headerBuffer.size() > HEADER_BUFFER_LIMIT) {
                throw new IOException("Multipart headers exceed maximum supported size");
            }

            if ((byte) next == terminator[matched]) {
                matched++;
            } else {
                matched = ((byte) next == terminator[0]) ? 1 : 0;
            }
        }

        String headerText = headerBuffer.toString(StandardCharsets.ISO_8859_1);
        Matcher filenameMatcher = FILENAME_PATTERN.matcher(headerText);
        if (!filenameMatcher.find()) {
            throw new IOException("Multipart upload missing filename");
        }

        String filename = filenameMatcher.group(1);
        String contentType = "application/octet-stream";
        Matcher contentTypeMatcher = CONTENT_TYPE_PATTERN.matcher(headerText);
        if (contentTypeMatcher.find()) {
            contentType = contentTypeMatcher.group(1).trim();
        }

        headersRead = true;
        return new PartHeaders(filename, contentType);
    }

    public long streamPart(OutputStream outputStream) throws IOException {
        if (!headersRead) {
            throw new IllegalStateException("Multipart headers must be read before streaming the file part");
        }

        byte[] readBuffer = new byte[8192];
        byte[] carry = new byte[0];
        long totalBytesWritten = 0;
        int keepTail = boundaryBytes.length + 4;

        while (true) {
            int bytesRead = inputStream.read(readBuffer);
            if (bytesRead == -1) {
                throw new IOException("Unexpected end of stream while reading multipart body");
            }

            byte[] combined = new byte[carry.length + bytesRead];
            System.arraycopy(carry, 0, combined, 0, carry.length);
            System.arraycopy(readBuffer, 0, combined, carry.length, bytesRead);

            int boundaryIndex = indexOf(combined, boundaryBytes);
            if (boundaryIndex >= 0) {
                outputStream.write(combined, 0, boundaryIndex);
                totalBytesWritten += boundaryIndex;
                ensureWithinLimit(totalBytesWritten);
                return totalBytesWritten;
            }

            int safeLength = combined.length - keepTail;
            if (safeLength > 0) {
                outputStream.write(combined, 0, safeLength);
                totalBytesWritten += safeLength;
                ensureWithinLimit(totalBytesWritten);

                int tailLength = combined.length - safeLength;
                carry = new byte[tailLength];
                System.arraycopy(combined, safeLength, carry, 0, tailLength);
            } else {
                carry = combined;
            }
        }
    }

    private void ensureWithinLimit(long totalBytesWritten) throws IOException {
        if (totalBytesWritten > maxFileSize) {
            throw new IOException("File too large: Maximum file size is " + (maxFileSize / (1024 * 1024)) + "MB");
        }
    }

    private static int indexOf(byte[] data, byte[] target) {
        outer:
        for (int i = 0; i <= data.length - target.length; i++) {
            for (int j = 0; j < target.length; j++) {
                if (data[i + j] != target[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    public static class PartHeaders {
        private final String fileName;
        private final String contentType;

        public PartHeaders(String fileName, String contentType) {
            this.fileName = fileName;
            this.contentType = contentType;
        }

        public String getFileName() {
            return fileName;
        }

        public String getContentType() {
            return contentType;
        }
    }
}
