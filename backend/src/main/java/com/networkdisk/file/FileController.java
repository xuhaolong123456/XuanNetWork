package com.networkdisk.file;

import com.networkdisk.common.Result;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("/api/v1/files")
public class FileController {
    private final FileService files;
    private final FileTrashService trash;
    private DownloadArchiveService archives;

    public FileController(FileService files, FileTrashService trash) {
        this.files = files;
        this.trash = trash;
    }

    @org.springframework.beans.factory.annotation.Autowired
    public void setArchives(DownloadArchiveService archives) { this.archives = archives; }

    @GetMapping
    public Result<FileListResponse> list(@AuthenticationPrincipal Long userId,
            @RequestParam(required = false) Long parentId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return Result.success(files.list(userId, parentId, page, size));
    }

    @GetMapping("/file/tree")
    public org.springframework.http.ResponseEntity<Result<List<FolderTreeNodeResponse>>> folderTree(
            @AuthenticationPrincipal Long userId) {
        List<FolderTreeNodeResponse> tree = files.folderTree(userId);
        if (tree.isEmpty()) return org.springframework.http.ResponseEntity.noContent().build();
        return org.springframework.http.ResponseEntity.ok(Result.success(tree));
    }

    @GetMapping("/file/preview")
    public org.springframework.http.ResponseEntity<Result<FilePreviewResponse>> preview(
            @AuthenticationPrincipal Long userId, @RequestParam Long fileId) {
        return org.springframework.http.ResponseEntity.ok()
                .cacheControl(org.springframework.http.CacheControl.noStore())
                .body(Result.success(files.preview(userId, fileId)));
    }

    @GetMapping("/file/download")
    public org.springframework.http.ResponseEntity<FileSystemResource> download(
            @AuthenticationPrincipal Long userId, @RequestParam(required = false) String filename,
            @RequestParam(required = false) String fileId) {
        if (filename == null || filename.isBlank()) {
            throw new FileBusinessException("INVALID_PARAM", "请求参数错误", 401);
        }
        Long parsedId = null;
        if (fileId != null) {
            try {
                parsedId = Long.valueOf(fileId);
            } catch (NumberFormatException exception) {
                throw new FileBusinessException("INVALID_PARAM", "请求参数错误", 401);
            }
        }
        FileService.FileDownload download = files.download(userId, filename, parsedId);
        return org.springframework.http.ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(download.name(), StandardCharsets.UTF_8).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(download.path().toFile().length())
                .body(new FileSystemResource(download.path()));
    }

    @PostMapping("/files/download")
    public org.springframework.http.ResponseEntity<StreamingResponseBody> downloadMany(
            @AuthenticationPrincipal Long userId, @Valid @RequestBody DownloadRequest request) {
        List<FileService.FileDownload> downloads = files.downloadMany(userId, request.ids());
        DownloadArchiveService.PreparedArchive archive = archives.create(userId, request.downloadName(), downloads);
        StreamingResponseBody body = output -> {
            try (var input = Files.newInputStream(archive.path())) { input.transferTo(output); }
            finally { archives.cleanup(archive.taskDirectory()); }
        };
        return org.springframework.http.ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(archive.filename(), StandardCharsets.UTF_8).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(MediaType.parseMediaType("application/zip"))
                .contentLength(archive.path().toFile().length())
                .body(body);
    }

    @PostMapping("/directories")
    @ResponseStatus(HttpStatus.CREATED)
    public Result<FileItemResponse> createDirectory(@AuthenticationPrincipal Long userId,
            @Valid @RequestBody CreateDirectoryRequest request) {
        return Result.success(files.createDirectory(userId, request.parentId(), request.folderName()));
    }

    @PatchMapping("/directories/{directoryId}")
    public Result<FileItemResponse> renameDirectory(@AuthenticationPrincipal Long userId,
            @PathVariable Long directoryId, @Valid @RequestBody RenameDirectoryRequest request) {
        return Result.success(files.renameDirectory(userId, directoryId, request.folderName()));
    }

    @DeleteMapping("/directories/{directoryId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteDirectory(@AuthenticationPrincipal Long userId,
            @PathVariable Long directoryId) {
        trash.delete(userId, java.util.List.of(directoryId));
    }

    @PutMapping("/file/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteFile(@AuthenticationPrincipal Long userId, @PathVariable Long id) {
        trash.delete(userId, java.util.List.of(id));
    }

    @PutMapping("/files")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteFiles(@AuthenticationPrincipal Long userId, @Valid @RequestBody FileIdsRequest request) {
        trash.delete(userId, request.ids());
    }

    @PostMapping("/move")
    public Result<List<FileItemResponse>> move(@AuthenticationPrincipal Long userId,
            @Valid @RequestBody MoveFilesRequest request) {
        return Result.success(files.move(userId, request.fileIds(), request.targetParentId()));
    }

    @GetMapping("/recycle-bin")
    public Result<TrashListResponse> recycleBin(@AuthenticationPrincipal Long userId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        return Result.success(trash.list(userId, page, size));
    }

    @PutMapping("/recover/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void restoreFile(@AuthenticationPrincipal Long userId, @PathVariable Long id) {
        trash.restore(userId, java.util.List.of(id));
    }

    @PutMapping("/recover/batch")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void restoreFiles(@AuthenticationPrincipal Long userId, @Valid @RequestBody FileIdsRequest request) {
        trash.restore(userId, request.ids());
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Result<FileItemResponse> upload(@AuthenticationPrincipal Long userId,
            @RequestParam(required = false) Long parentId,
            @RequestPart("file") MultipartFile file) {
        return Result.success(files.upload(userId, parentId, file));
    }

    @PostMapping(value = "/quick-check", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Result<QuickCheckResponse> quickCheck(@AuthenticationPrincipal Long userId,
            @Valid @RequestBody QuickCheckRequest request) {
        QuickCheckResponse response = files.quickCheck(userId, request);
        // HTTP 请求成功不代表一定命中秒传，外层消息应直接说明本次业务判断结果。
        return Result.success(response.exist() ? "秒传命中" : "秒传未命中", response);
    }
}
