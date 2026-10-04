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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class ChunkUploadService {
    public static final long CHUNK_SIZE = 8 * 1024 * 1024L;
    private final ChunkUploadSessionRepository sessions;
    private final ChunkUploadPartRepository parts;
    private final Path root;

    public ChunkUploadService(ChunkUploadSessionRepository sessions, ChunkUploadPartRepository parts,
            @Value("${app.storage.root:./uploads}") Path storageRoot) {
        this.sessions = sessions;
        this.parts = parts;
        this.root = storageRoot.toAbsolutePath().normalize().resolve("chunks");
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
        if (uploadId == null || !uploadId.matches("upload-[0-9a-f-]{36}")) throw invalidSession();
        ChunkUploadSession session = sessions.findForUpdate(uploadId).orElseThrow(ChunkUploadService::invalidSession);
        // 秒传未命中后才能恢复会话，且必须重新校验归属和完整文件元数据。
        if (session.getOwnerId() != ownerId) throw error("40301", "用户越权操作，uploadId不属于当前用户", 403);
        if (!session.getExpiresAt().isAfter(Instant.now())) throw invalidSession();
        if (!session.getName().equals(name) || session.getSizeBytes() != sizeBytes
                || !session.getFileMd5().equals(normalizeMd5(fileMd5))) {
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
