package com.networkdisk.file;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class FileStorageService {
    private static final String DEFAULT_MIME_TYPE = "application/octet-stream";

    private final Path root;
    private final long maxFileSizeBytes;

    public FileStorageService(@Value("${app.storage.root:./uploads}") Path root,
                              @Value("${app.storage.max-file-size-bytes:104857600}") long maxFileSizeBytes) {
        this.root = root.toAbsolutePath().normalize();
        this.maxFileSizeBytes = maxFileSizeBytes;
    }

    public StoredFile store(long ownerId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new FileBusinessException("INVALID_FILE", "请选择非空文件");
        }
        if (file.getSize() > maxFileSizeBytes) {
            throw new FileBusinessException("FILE_TOO_LARGE", "文件大小超过允许上限");
        }

        String name = safeFileName(file.getOriginalFilename());
        String storageKey = ownerId + "/" + UUID.randomUUID();
        Path ownerDirectory = root.resolve(String.valueOf(ownerId)).normalize();
        Path destination = root.resolve(storageKey).normalize();
        if (!destination.startsWith(ownerDirectory) || !ownerDirectory.startsWith(root)) {
            throw new FileBusinessException("INVALID_FILE", "文件路径无效");
        }

        try {
            Files.createDirectories(ownerDirectory);
            file.transferTo(destination);
        } catch (IOException | IllegalStateException exception) {
            try {
                Files.deleteIfExists(destination);
            } catch (IOException cleanupException) {
                exception.addSuppressed(cleanupException);
            }
            throw new FileBusinessException("FILE_STORAGE_UNAVAILABLE", "文件保存失败，请稍后重试", 503);
        }
        return new StoredFile(storageKey, name, file.getSize(), safeMimeType(file.getContentType()));
    }

    public void delete(String storageKey) {
        Path file = root.resolve(storageKey).normalize();
        if (!file.startsWith(root)) {
            throw new FileBusinessException("INVALID_FILE", "文件路径无效");
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException exception) {
            throw new FileBusinessException("FILE_STORAGE_UNAVAILABLE", "临时文件清理失败", 503);
        }
    }

    private static String safeFileName(String originalFilename) {
        String normalized = originalFilename == null ? "" : originalFilename.replace('\\', '/');
        String name = normalized.substring(normalized.lastIndexOf('/') + 1).trim();
        if (name.isBlank() || name.equals(".") || name.equals("..") || name.length() > 255
                || name.chars().anyMatch(character -> character < 32 || "<>:\"|?*".indexOf(character) >= 0)) {
            throw new FileBusinessException("INVALID_FILE_NAME", "文件名无效或超过 255 个字符");
        }
        return name;
    }

    private static String safeMimeType(String mimeType) {
        if (mimeType == null || mimeType.isBlank() || mimeType.length() > 255
                || mimeType.chars().anyMatch(Character::isISOControl)) {
            return DEFAULT_MIME_TYPE;
        }
        return mimeType;
    }

    public record StoredFile(String storageKey, String name, long sizeBytes, String mimeType) {
    }
}
