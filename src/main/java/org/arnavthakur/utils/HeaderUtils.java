package org.arnavthakur.utils;

public final class HeaderUtils {
    private HeaderUtils() {
    }

    public static String sanitizeFilename(String filename, String fallback) {
        if (filename == null || filename.isBlank()) {
            return fallback;
        }

        String sanitized = filename
                .replace('\\', '/')
                .replace("\r", "")
                .replace("\n", "");

        int lastSlash = sanitized.lastIndexOf('/');
        if (lastSlash >= 0 && lastSlash < sanitized.length() - 1) {
            sanitized = sanitized.substring(lastSlash + 1);
        }

        sanitized = sanitized
                .replace("\"", "_")
                .replace(";", "_");

        if (sanitized.isBlank()) {
            return fallback;
        }

        return sanitized;
    }

    public static String buildAttachmentDisposition(String filename) {
        String safeFilename = sanitizeFilename(filename, "downloaded-file");
        return "attachment; filename=\"" + safeFilename + "\"";
    }
}
