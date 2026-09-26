package com.networkdisk.file;

import com.networkdisk.common.Result;
import jakarta.validation.Valid;
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

@RestController
@RequestMapping("/api/v1/files")
public class FileController {
    private final FileService files;
    private final FileTrashService trash;

    public FileController(FileService files, FileTrashService trash) {
        this.files = files;
        this.trash = trash;
    }

    @GetMapping
    public Result<FileListResponse> list(@AuthenticationPrincipal Long userId,
            @RequestParam(required = false) Long parentId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return Result.success(files.list(userId, parentId, page, size));
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
}
