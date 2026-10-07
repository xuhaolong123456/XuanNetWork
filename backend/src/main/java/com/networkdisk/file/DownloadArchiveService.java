package com.networkdisk.file;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;

@Service
public class DownloadArchiveService {
    private final Path root;

    public DownloadArchiveService(@Value("${app.storage.download-temp-root:./download-tmp}") Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public PreparedArchive create(long ownerId, String requestedName, List<FileService.FileDownload> files) {
        String downloadName = safeDownloadName(requestedName);
        Path ownerDirectory = root.resolve(Long.toString(ownerId)).normalize();
        Path taskDirectory = ownerDirectory.resolve(downloadName + "-" + UUID.randomUUID()).normalize();
        if (!taskDirectory.startsWith(ownerDirectory) || !ownerDirectory.startsWith(root)) {
            throw new FileBusinessException("INVALID_PARAM", "下载临时目录无效", 400);
        }
        try {
            Files.createDirectories(taskDirectory);
            Path extracted = taskDirectory.resolve("files");
            Path archive = taskDirectory.resolve(downloadName + ".zip");
            Set<String> entryNames = new HashSet<>();
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive), StandardCharsets.UTF_8)) {
                for (FileService.FileDownload file : files) {
                    String entryName = uniqueFileName(file, entryNames);
                    Path relative = Path.of(entryName).normalize();
                    if (relative.isAbsolute() || relative.startsWith("..")) {
                        throw new FileBusinessException("INVALID_FILE_NAME", "压缩包内路径无效");
                    }
                    Path staged = extracted.resolve(relative).normalize();
                    if (!staged.startsWith(extracted)) throw new FileBusinessException("INVALID_FILE_NAME", "压缩包内路径无效");
                    if (file.path() == null) {
                        Files.createDirectories(staged);
                        zip.putNextEntry(new ZipEntry(entryName.replace('\\', '/')));
                        zip.closeEntry();
                        continue;
                    }
                    Files.createDirectories(staged.getParent());
                    Files.copy(file.path(), staged, StandardCopyOption.REPLACE_EXISTING);
                    zip.putNextEntry(new ZipEntry(entryName.replace('\\', '/')));
                    try (InputStream input = Files.newInputStream(staged)) { input.transferTo(zip); }
                    zip.closeEntry();
                }
            }
            return new PreparedArchive(archive, taskDirectory, downloadName + ".zip");
        } catch (IOException | RuntimeException exception) {
            deleteTree(taskDirectory);
            if (exception instanceof FileBusinessException business) throw business;
            throw new FileBusinessException("DOWNLOAD_ARCHIVE_FAILED", "压缩包生成失败，请稍后重试", 503);
        }
    }

    public void cleanup(Path taskDirectory) { deleteTree(taskDirectory); }

    @Scheduled(fixedDelayString = "${app.storage.download-cleanup-delay-ms:3600000}")
    public void cleanupExpired() {
        if (!Files.isDirectory(root)) return;
        Instant cutoff = Instant.now().minus(Duration.ofHours(24));
        try (var owners = Files.list(root)) {
            owners.filter(Files::isDirectory).forEach(owner -> {
                try (var tasks = Files.list(owner)) {
                    tasks.filter(Files::isDirectory).forEach(task -> {
                        try {
                            FileTime modified = Files.getLastModifiedTime(task);
                            if (modified.toInstant().isBefore(cutoff)) deleteTree(task);
                        } catch (IOException ignored) { }
                    });
                } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }

    private static String safeDownloadName(String name) {
        String value = name == null ? "files" : name.trim();
        if (value.isBlank()) value = "files";
        if (value.length() > 120 || value.equals(".") || value.equals("..")
                || value.chars().anyMatch(character -> character < 32 || "/\\<>:\"|?*".indexOf(character) >= 0)) {
            throw new FileBusinessException("INVALID_PARAM", "压缩包名称无效", 400);
        }
        return value;
    }

    private static String uniqueFileName(FileService.FileDownload file, Set<String> names) {
        String requested = file.name();
        if (names.add(requested)) return requested;
        if (file.path() == null) throw new FileBusinessException("DUPLICATE_ARCHIVE_ENTRY", "压缩包目录名称冲突", 409);
        int slash = requested.lastIndexOf('/');
        String parent = slash < 0 ? "" : requested.substring(0, slash + 1);
        String leaf = slash < 0 ? requested : requested.substring(slash + 1);
        int dot = leaf.lastIndexOf('.');
        String base = dot <= 0 ? leaf : leaf.substring(0, dot);
        String extension = dot <= 0 ? "" : leaf.substring(dot);
        for (int suffix = 1; ; suffix++) {
            String candidate = parent + base + "（" + suffix + "）" + extension;
            if (names.add(candidate)) return candidate;
        }
    }

    private static void deleteTree(Path directory) {
        if (directory == null || !Files.exists(directory)) return;
        try (var paths = Files.walk(directory)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); }
                catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }

    public record PreparedArchive(Path path, Path taskDirectory, String filename) { }
}
