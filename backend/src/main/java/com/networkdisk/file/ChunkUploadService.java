package com.networkdisk.file;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.function.BiFunction;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class ChunkUploadService {
    public static final long CHUNK_SIZE = 8 * 1024 * 1024L;
    private final ChunkUploadSessionRepository sessions;
    private final ChunkUploadPartRepository parts;
    private final FileStorageService storage;
    private final Path root;

    @Autowired
    public ChunkUploadService(ChunkUploadSessionRepository sessions, ChunkUploadPartRepository parts,
            FileStorageService storage, @Value("${app.storage.root:./uploads}") Path storageRoot) {
        this.sessions = sessions;
        this.parts = parts;
        this.storage = storage;
        this.root = storageRoot.toAbsolutePath().normalize().resolve("chunks");
    }

    ChunkUploadService(ChunkUploadSessionRepository sessions, ChunkUploadPartRepository parts,
            @Value("${app.storage.root:./uploads}") Path storageRoot) {
        this(sessions, parts, new FileStorageService(storageRoot, 2147483648L), storageRoot);
    }

    // parentFolderId 记录目标目录，合并出最终文件时据此创建 UserFile。
    @Transactional
    public ChunkUploadSession createSession(long ownerId, String name, long sizeBytes, String fileMd5,
            Long parentFolderId) {
        if (sizeBytes <= 0 || (sizeBytes - 1) / CHUNK_SIZE >= Integer.MAX_VALUE) {
            throw error("40003", "文件大小不合法", 400);
        }
        String md5 = normalizeMd5(fileMd5);
        return sessions.save(new ChunkUploadSession("upload-" + UUID.randomUUID(), ownerId,
                FileStorageService.validateUploadName(name), sizeBytes, md5, CHUNK_SIZE,
                Instant.now().plus(24, ChronoUnit.HOURS), parentFolderId));
    }

    @Transactional
    public ChunkUploadSession resumeSession(long ownerId, String uploadId, String name, long sizeBytes, String fileMd5) {
        return resumeSession(ownerId, uploadId, name, sizeBytes, fileMd5, null);
    }

    @Transactional
    public ChunkUploadSession resumeSession(long ownerId, String uploadId, String name, long sizeBytes,
            String fileMd5, Long parentFolderId) {
        if (uploadId == null || !uploadId.matches("upload-[0-9a-f-]{36}")) throw invalidSession();
        ChunkUploadSession session = sessions.findForUpdate(uploadId).orElseThrow(ChunkUploadService::invalidSession);
        // 秒传未命中后才能恢复会话，且必须重新校验归属和完整文件元数据。
        if (session.getOwnerId() != ownerId) throw error("40301", "用户越权操作，uploadId不属于当前用户", 403);
        if (!session.getExpiresAt().isAfter(Instant.now())) throw invalidSession();
        if (!session.getName().equals(name) || session.getSizeBytes() != sizeBytes
                || !session.getFileMd5().equals(normalizeMd5(fileMd5))
                || !java.util.Objects.equals(session.getParentFolderId(), parentFolderId)) {
            throw error("40001", "分片元数据与上传会话不一致", 400);
        }
        return session;
    }

    @Transactional
    public ChunkUploadResponse upload(Long ownerId, String name, String nodeType, long sizeBytes,
            String uploadId, int partNumber, String fileMd5, MultipartFile chunk) {
        if (ownerId == null) throw error("401", "用户身份校验失败", 401);
        if (uploadId == null || !uploadId.matches("upload-[0-9a-f-]{36}")) throw invalidSession();
        ChunkUploadSession session = sessions.findForUpdate(uploadId).orElseThrow(ChunkUploadService::invalidSession);
        // 在读取分片文件之前验证会话归属，客户端不能通过伪造元数据操作他人会话。
        if (session.getOwnerId() != ownerId) throw error("40301", "用户越权操作，uploadId不属于当前用户", 403);
        if (!session.getExpiresAt().isAfter(Instant.now())) throw invalidSession();
        if (partNumber < 1 || partNumber > session.getTotalParts()) throw error("40002", "partNumber非法", 400);
        if (sizeBytes <= 0 || sizeBytes != session.getSizeBytes()) throw error("40003", "文件大小不合法", 400);
        if (!session.getName().equals(name) || !"FILE".equalsIgnoreCase(nodeType)
                || !session.getFileMd5().equals(normalizeMd5(fileMd5))) {
            throw error("40001", "分片元数据与上传会话不一致", 400);
        }
        long expectedSize = partNumber == session.getTotalParts()
                ? sizeBytes - (partNumber - 1L) * session.getChunkSize() : session.getChunkSize();
        if (chunk == null || chunk.getSize() <= 0) throw error("40003", "文件大小不合法", 400);
        if (chunk.getSize() > session.getChunkSize()) throw error("41301", "分片大小超限", 413);
        if (chunk.getSize() != expectedSize) throw error("40003", "分片长度与会话不一致", 400);

        Path temporary = null;
        try {
            Path directory = directory(uploadId);
            Path destination = directory.resolve(partNumber + ".part");
            // 会话数据库行锁覆盖检查和写入，重复请求不会再次打开请求数据流。
            if (!Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS)) {
                Files.createDirectories(directory);
                temporary = Files.createTempFile(directory, "pending-", ".tmp");
                try (var input = chunk.getInputStream(); var output = Files.newOutputStream(temporary)) {
                    byte[] buffer = new byte[8192];
                    long received = 0;
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        received += count;
                        if (received > expectedSize) throw error("41301", "分片大小超限", 413);
                        output.write(buffer, 0, count);
                    }
                    if (received != expectedSize) throw error("40003", "文件大小不合法", 400);
                }
                // 仅原子发布完整分片，异常中断不会将半个分片计入已完成列表。
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
            }
            if (Files.size(destination) != expectedSize) throw error("50001", "已保存分片长度异常", 500);
            // 完整文件发布之后才写元数据；事务失败留下的孤立完整分片可由重试补录。
            // 分片记录带上会话过期时间，过期后会被当作旧记录清理、不再参与齐全判断。
            if (!parts.existsByUploadIdAndPartNumber(uploadId, partNumber)) {
                parts.saveAndFlush(new ChunkUploadPart(uploadId, partNumber, expectedSize, session.getExpiresAt()));
            }
            // 只统计有效分片，逻辑删除或已过期的分片不计入齐全判断。
            List<Integer> finished = parts.findActivePartNumbers(uploadId, Instant.now());
            return new ChunkUploadResponse(uploadId, partNumber,
                    finished.size() == session.getTotalParts() ? MergeFlag.READY : MergeFlag.INCOMPLETE,
                    List.copyOf(finished));
        } catch (IOException exception) {
            throw error("50001", "分片写入磁盘失败", 500);
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); }
                catch (IOException exception) {
                    // 未完成临时文件仍位于会话目录，由过期清理任务兜底回收。
                    org.slf4j.LoggerFactory.getLogger(getClass()).warn("分片临时文件清理失败", exception);
                }
            }
        }
    }

    @Transactional
    public Long merge(long ownerId, String uploadId,
            BiFunction<ChunkUploadSession, FileStorageService.StoredFile, Long> registerFile) {
        if (uploadId == null || !uploadId.matches("upload-[0-9a-f-]{36}")) throw invalidSession();
        // 会话行锁串行化合并、重试和过期清理，并在读取分片前验证所有权。
        ChunkUploadSession session = sessions.findForUpdate(uploadId).orElseThrow(ChunkUploadService::invalidSession);
        if (session.getOwnerId() != ownerId) throw error("40301", "无权操作该分片会话", 403);
        if (session.getMergedFileId() != null) return session.getMergedFileId();
        if (!session.getExpiresAt().isAfter(Instant.now())) throw invalidSession();

        // 只接受完整连续的有效分片，避免合并遗漏或错序的数据。
        List<Integer> finished = parts.findActivePartNumbers(uploadId, Instant.now());
        if (finished.size() != session.getTotalParts()) throw error("40901", "分片尚未全部上传完成", 409);
        Path directory = directory(uploadId);
        java.util.ArrayList<Path> orderedChunks = new java.util.ArrayList<>(session.getTotalParts());
        for (int number = 1; number <= session.getTotalParts(); number++) {
            if (finished.get(number - 1) != number) throw error("40901", "分片序号不连续，无法合并", 409);
            Path part = directory.resolve(number + ".part");
            long expectedSize = number == session.getTotalParts()
                    ? session.getSizeBytes() - (number - 1L) * session.getChunkSize() : session.getChunkSize();
            try {
                if (!Files.isRegularFile(part, LinkOption.NOFOLLOW_LINKS) || Files.size(part) != expectedSize) {
                    throw error("40901", "分片文件缺失或长度异常，请重新上传", 409);
                }
            } catch (IOException exception) {
                throw error("50001", "读取分片文件失败", 500);
            }
            orderedChunks.add(part);
        }

        FileStorageService.StoredFile merged = storage.mergeChunks(
                session.getName(), session.getSizeBytes(), session.getFileMd5(), orderedChunks);
        try {
            Long fileId = registerFile.apply(session, merged);
            session.markMerged(fileId);
            parts.deleteByUploadId(uploadId);
            // 文件记录与完成标记同事务提交；只在提交后回收分片，回滚时保留续传数据。
            if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
                org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCompletion(int status) {
                            if (status != org.springframework.transaction.support.TransactionSynchronization.STATUS_COMMITTED) {
                                try { storage.delete(merged.storageKey()); }
                                catch (RuntimeException ignored) { }
                            }
                        }

                        @Override
                        public void afterCommit() {
                            try {
                                if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
                                    try (var paths = Files.walk(directory)) {
                                        for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                                            Files.deleteIfExists(path);
                                        }
                                    }
                                }
                            } catch (IOException exception) {
                                org.slf4j.LoggerFactory.getLogger(ChunkUploadService.class)
                                        .warn("合并成功后的分片清理失败，等待会话过期清理：{}", uploadId, exception);
                            }
                        }
                    });
            } else {
                deleteChunkDirectory(directory, uploadId);
            }
            return fileId;
        } catch (RuntimeException exception) {
            try { storage.delete(merged.storageKey()); }
            catch (RuntimeException cleanupException) { exception.addSuppressed(cleanupException); }
            throw exception;
        }
    }

    private void deleteChunkDirectory(Path directory, String uploadId) {
        try {
            if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
                try (var paths = Files.walk(directory)) {
                    for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
                }
            }
        } catch (IOException exception) {
            org.slf4j.LoggerFactory.getLogger(ChunkUploadService.class)
                    .warn("合并成功后的分片清理失败，等待会话过期清理：{}", uploadId, exception);
        }
    }

    @Transactional
    public void cleanExpired(String uploadId) {
        var found = sessions.findForUpdate(uploadId);
        if (found.isEmpty() || found.get().getExpiresAt().isAfter(Instant.now())) return;
        // 完成和未完成的会话都回收；本版本没有合并，不应永久保留已就位的分片。
        try {
            Path directory = directory(uploadId);
            if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
                try (var paths = Files.walk(directory)) {
                    for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
                }
            }
            parts.deleteByUploadId(uploadId);
            sessions.delete(found.get());
        } catch (IOException exception) {
            throw error("50001", "分片清理失败", 500);
        }
    }

    private Path directory(String uploadId) { return root.resolve(uploadId); }

    public static String normalizeMd5(String fileMd5) {
        if (fileMd5 == null || !fileMd5.matches("[0-9a-fA-F]{32}")) throw error("40001", "fileMd5必须是完整文件的32位MD5", 400);
        return fileMd5.toLowerCase(java.util.Locale.ROOT);
    }

    private static FileBusinessException invalidSession() { return error("40001", "uploadId不存在或者非法", 400); }
    private static FileBusinessException error(String code, String message, int status) {
        return new FileBusinessException(code, message, status);
    }
}
