package com.company.aiqa.util;

import com.company.aiqa.model.SourceLanguage;

import java.nio.file.Files;
import java.nio.file.Path;

public final class FileUtils {

    private FileUtils() {
    }

    public static boolean isSupportedSource(String path) {
        return SourceLanguage.isSupported(path);
    }

    public static void ensureDirectory(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (Exception e) {
            throw new IllegalStateException("Could not create directory " + dir + ": " + e.getMessage(), e);
        }
    }
}
