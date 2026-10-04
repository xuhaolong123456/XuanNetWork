package com.networkdisk.file;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.NoSuchFileException;
import java.nio.file.InvalidPathException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CharacterCodingException;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class FileStorageService {
    private static final String DEFAULT_MIME_TYPE = "application/octet-stream";
    private static final Set<String> UPLOAD_FORMATS = Set.of(
            "txt", "docx", "csv", "xlsx", "pdf", "md", "html", "pptx");
    private static final Set<String> PREVIEW_FORMATS = Set.of("md", "txt");

    private final Path root;
    private final long maxFileSizeBytes;

    public FileStorageService(@Value("${app.storage.root:./uploads}") Path root,
                              @Value("${app.storage.max-file-size-bytes:2147483648}") long maxFileSizeBytes) {
        this.root = root.toAbsolutePath().normalize();
        this.maxFileSizeBytes = Math.min(maxFileSizeBytes, 2147483648L);
        if (this.maxFileSizeBytes < 1) throw new IllegalArgumentException("文件大小上限必须大于零");
    }

    public StoredFile store(long ownerId, MultipartFile file) {
        if (file == null) {
            throw new FileBusinessException("INVALID_FILE", "请选择文件");
        }
        if (file.isEmpty()) {
            // 空文件按接口约定返回 401，以独立业务码区别于登录失效。
            throw new FileBusinessException("EMPTY_FILE", "不能上传空文件", 401);
        }
        if (file.getSize() > maxFileSizeBytes) {
            throw new FileBusinessException("FILE_TOO_LARGE", "文件大小超过允许上限", 413);
        }

        String name = validateUploadName(file.getOriginalFilename());
        uploadFormatOf(name);
        FileHashes hashes = hashes(file);
        String storageKey = "blobs/" + UUID.randomUUID();
        Path ownerDirectory = root.resolve("blobs").normalize();
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
        return new StoredFile(storageKey, name, file.getSize(), safeMimeType(file.getContentType()), hashes.sha256(), hashes.md5());
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

    public static String formatOf(String name) {
        String format = extensionOf(name);
        if (!PREVIEW_FORMATS.contains(format)) {
            throw new FileBusinessException("UNSUPPORTED_FILE_TYPE", "仅支持 md、txt 文件预览", 415);
        }
        return format;
    }

    public static String uploadFormatOf(String name) {
        String format = extensionOf(name);
        if (!UPLOAD_FORMATS.contains(format)) {
            throw new FileBusinessException("UNSUPPORTED_FILE_TYPE",
                    "仅支持 txt、docx、csv、xlsx、pdf、md、html、pptx 文件", 415);
        }
        return format;
    }

    static String validateUploadName(String name) {
        return safeFileName(name);
    }

    private static String extensionOf(String name) {
        int extensionStart = name.lastIndexOf('.');
        return extensionStart < 0 ? "" : name.substring(extensionStart + 1).toLowerCase(Locale.ROOT);
    }

    public String readText(long ownerId, String storageKey) {
        if (storageKey == null || storageKey.isBlank()) throw notFound();
        try {
            Path key = Path.of(storageKey);
            Path path = root.resolve(key).normalize();
            if (!isReadableKey(ownerId, key) || !isReadablePath(ownerId, path)) throw notFound();
            // 数据库只保存相对存储键；同时限制归属目录和真实路径，防止越界或符号链接读取。
            if (key.isAbsolute() || !path.startsWith(root)) throw notFound();
            Path realRoot = root.toRealPath();
            Path realPath = path.toRealPath();
            if (!realPath.startsWith(realRoot)
                    || !Files.isRegularFile(realPath)) throw notFound();
            long previewLimit = Math.min(maxFileSizeBytes, 104857600L);
            if (Files.size(realPath) > previewLimit) {
                throw new FileBusinessException("FILE_TOO_LARGE", "文件大小超过允许上限", 413);
            }
            byte[] bytes;
            // 有界读取也检查实际字节数，避免读取期间文件增长绕过大小限制；成功后才返回完整内容。
            try (var input = Files.newInputStream(realPath)) {
                bytes = input.readNBytes((int) previewLimit + 1);
            }
            if (bytes.length > previewLimit) {
                throw new FileBusinessException("FILE_TOO_LARGE", "文件大小超过允许上限", 413);
            }
            String content = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
            return content.startsWith("\uFEFF") ? content.substring(1) : content;
        } catch (CharacterCodingException exception) {
            throw new FileBusinessException("UNSUPPORTED_ENCODING", "仅支持 UTF-8 编码的文本文件", 415);
        } catch (NoSuchFileException | InvalidPathException exception) {
            throw notFound();
        } catch (IOException exception) {
            throw new FileBusinessException("FILE_STORAGE_UNAVAILABLE", "文件读取失败，请稍后重试", 503);
        }
    }

    public Path resolveDownload(long ownerId, String storageKey) {
        if (storageKey == null || storageKey.isBlank()) throw notFound();
        try {
            Path key = Path.of(storageKey);
            Path path = root.resolve(key).normalize();
            if (!isReadableKey(ownerId, key) || !isReadablePath(ownerId, path)) throw notFound();
            // 下载只能读取当前用户目录中的真实普通文件，拒绝路径穿越和符号链接越界。
            if (key.isAbsolute() || !path.startsWith(root)) throw notFound();
            Path realRoot = root.toRealPath();
            Path realPath = path.toRealPath();
            if (!realPath.startsWith(realRoot)
                    || !Files.isRegularFile(realPath)) throw notFound();
            if (Files.size(realPath) > maxFileSizeBytes) {
                throw new FileBusinessException("FILE_TOO_LARGE", "文件大小超过允许上限", 413);
            }
            return realPath;
        } catch (NoSuchFileException | InvalidPathException exception) {
            throw notFound();
        } catch (IOException exception) {
            throw new FileBusinessException("FILE_STORAGE_UNAVAILABLE", "文件读取失败，请稍后重试", 503);
        }
    }

    private static FileBusinessException notFound() {
        return new FileBusinessException("FILE_NOT_FOUND", "资源不存在", 404);
    }

    private static boolean isReadableKey(long ownerId, Path key) {
        if (key.getNameCount() == 0) return false;
        String firstSegment = key.getName(0).toString();
        return firstSegment.equals("blobs") || firstSegment.equals(String.valueOf(ownerId));
    }

    private boolean isReadablePath(long ownerId, Path path) {
        return path.startsWith(root.resolve("blobs").normalize())
                || path.startsWith(root.resolve(String.valueOf(ownerId)).normalize());
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

    private static FileHashes hashes(MultipartFile file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            // 完整文件 MD5 不加盐，与既有 SHA-256 在一次流式读取中共同计算。
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            InputStream input = file.getInputStream();
            if (input == null) {
                byte[] bytes = file.getBytes();
                byte[] content = bytes == null ? new byte[0] : bytes;
                return new FileHashes(hex(digest.digest(content)), hex(md5.digest(content)));
            }
            try (input) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read > 0) {
                        digest.update(buffer, 0, read);
                        md5.update(buffer, 0, read);
                    }
                }
            }
            return new FileHashes(hex(digest.digest()), hex(md5.digest()));
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new FileBusinessException("FILE_HASH_FAILED", "文件指纹计算失败，请稍后重试", 503);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format("%02x", value));
        return result.toString();
    }

    private static String safeMimeType(String mimeType) {
        if (mimeType == null || mimeType.isBlank() || mimeType.length() > 255
                || mimeType.chars().anyMatch(Character::isISOControl)) {
            return DEFAULT_MIME_TYPE;
        }
        return mimeType;
    }

    private record FileHashes(String sha256, String md5) { }

    public record StoredFile(String storageKey, String name, long sizeBytes, String mimeType, String contentHash, String fileMd5) {
        public StoredFile(String storageKey, String name, long sizeBytes, String mimeType, String contentHash) {
            this(storageKey, name, sizeBytes, mimeType, contentHash, null);
        }
        public StoredFile(String storageKey, String name, long sizeBytes, String mimeType) {
            this(storageKey, name, sizeBytes, mimeType, null, null);
        }
    }
}
