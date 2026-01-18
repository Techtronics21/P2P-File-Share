package org.arnavthakur.utils;

public class MultiParser {
    private final byte[] data;
    private final String boundary;

    public MultiParser(byte[] data, String boundary) {
        this.data = data;
        this.boundary = boundary;
    }

    public ParseResult parse() {
        try {
            String dataAsString = new String(data);
            String fileNameMarker = "filename=\"";
            int fileNameStart = dataAsString.indexOf(fileNameMarker);
            if (fileNameStart == -1) return null;

            fileNameStart += fileNameMarker.length();
            int fileNameEnd = dataAsString.indexOf("\"", fileNameStart);
            if (fileNameEnd == -1) return null;
            String filename = dataAsString.substring(fileNameStart, fileNameEnd);

            String contentTypeMaker = "Content-Type: ";
            int contentTypeStart = dataAsString.indexOf(contentTypeMaker, fileNameEnd);
            String contentType = "application/octet-stream";
            if (contentTypeStart != -1) {
                contentTypeStart += contentTypeMaker.length();
                int contentTypeEnd = dataAsString.indexOf("\r\n", contentTypeStart);
                if (contentTypeEnd > contentTypeStart) {
                    contentType = dataAsString.substring(contentTypeStart, contentTypeEnd);
                }
            }

            String headerEndMarker = "\r\n\r\n";
            int headerEnd = dataAsString.indexOf(headerEndMarker);
            if (headerEnd == -1) return null;

            int contentStart = headerEnd + headerEndMarker.length();

            byte[] boundaryBytes = ("\r\n--" + boundary + "--").getBytes();
            int contentEnd = findSequence(data, boundaryBytes, contentStart);

            if (contentEnd == -1) {
                boundaryBytes = ("\r\n--" + boundary).getBytes();
                contentEnd = findSequence(data, boundaryBytes, contentStart);
            }
            if (contentEnd == -1 || contentEnd <= contentStart) {
                return null;
            }

            byte[] fileContent = new byte[contentEnd - contentStart];
            System.arraycopy(data, contentStart, fileContent, 0, fileContent.length);

            return new ParseResult(filename, fileContent, contentType);

        } catch (Exception ex) {
            System.err.println("Error parsing multipart data " + ex.getMessage());
            return null;
        }
    }

    public static class ParseResult {
        public final String fileName;
        public final byte[] fileContent;
        public final String contentType;

        public ParseResult(String fileName, byte[] fileContent, String contentType) {
            this.fileName = fileName;
            this.fileContent = fileContent;
            this.contentType = contentType;
        }
    }

    private static int findSequence(byte[] data, byte[] sequence, int startPosition) {
        outer:
        for (int i = startPosition; i < data.length - sequence.length; i++) {
            for (int j = 0; j < sequence.length; j++) {
                if (data[i + j] != sequence[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
