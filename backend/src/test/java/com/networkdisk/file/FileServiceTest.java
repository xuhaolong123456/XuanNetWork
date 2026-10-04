package com.networkdisk.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.networkdisk.auth.User;
import com.networkdisk.auth.UserRepository;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

class FileServiceTest {
    private final UserFileRepository files = mock(UserFileRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final FileStorageService storage = mock(FileStorageService.class);
    private final PhysicalFileRepository physicalFiles = mock(PhysicalFileRepository.class);
    private final FileService service = new FileService(files, users, storage);
    private final FileService quickCheckService = new FileService(files, users, storage, physicalFiles);
    @org.junit.jupiter.api.BeforeEach
    void ownerLock() {
        when(users.findByIdForUpdate(7L)).thenReturn(Optional.of(new User("owner@example.com", "owner", "hash")));
    }

    @Test
    void listsOnlyRootChildrenForTheAuthenticatedOwner() {
        User owner = new User("owner@example.com", "owner", "hash");
        ReflectionTestUtils.setField(owner, "id", 7L);
        UserFile folder = new UserFile(owner, null, "Documents", FileNodeType.DIRECTORY);
        UserFile document = new UserFile(owner, null, "notes.txt", FileNodeType.FILE);
        ReflectionTestUtils.setField(folder, "id", 11L);
        ReflectionTestUtils.setField(document, "id", 12L);
        when(files.findByOwner_IdAndParentIsNull(eq(7L), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(folder, document), PageRequest.of(0, 50), 2));

        FileListResponse result = service.list(7L, null, 0, 50);

        assertThat(result.currentDirectory()).isNull();
        assertThat(result.breadcrumbs()).containsExactly(new FileBreadcrumb(null, "我的文件"));
        assertThat(result.items()).extracting(FileItemResponse::name).containsExactly("Documents", "notes.txt");
        assertThat(result.items()).extracting(FileItemResponse::type)
                .containsExactly(FileNodeType.DIRECTORY, FileNodeType.FILE);
        assertThat(result.page().totalElements()).isEqualTo(2);
        verify(files).findByOwner_IdAndParentIsNull(eq(7L), any(PageRequest.class));
    }

    @Test
    void rejectsDirectoryThatDoesNotBelongToTheAuthenticatedOwner() {
        when(files.findByIdAndOwner_Id(99L, 7L)).thenReturn(Optional.empty());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.list(7L, 99L, 0, 50))
                .isInstanceOf(FileBusinessException.class)
                .extracting("code").isEqualTo("FILE_NOT_FOUND");
        verify(files, never()).findByOwner_IdAndParent_Id(eq(7L), eq(99L), any(PageRequest.class));
    }

    @Test
    void listsDirectoryContentsAndBuildsBreadcrumbsFromItsParentChain() {
        User owner = new User("owner@example.com", "owner", "hash");
        UserFile parent = new UserFile(owner, null, "Documents", FileNodeType.DIRECTORY);
        UserFile directory = new UserFile(owner, parent, "Drafts", FileNodeType.DIRECTORY);
        ReflectionTestUtils.setField(parent, "id", 4L);
        ReflectionTestUtils.setField(directory, "id", 8L);
        when(files.findByIdAndOwner_Id(8L, 7L)).thenReturn(Optional.of(directory));
        when(files.findByOwner_IdAndParent_Id(eq(7L), eq(8L), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 50), 0));

        FileListResponse result = service.list(7L, 8L, 0, 50);

        assertThat(result.currentDirectory()).isEqualTo(new FileDirectoryResponse(8L, "Drafts", 4L));
        assertThat(result.breadcrumbs()).containsExactly(
                new FileBreadcrumb(null, "我的文件"),
                new FileBreadcrumb(4L, "Documents"),
                new FileBreadcrumb(8L, "Drafts"));
        verify(files).findByOwner_IdAndParent_Id(eq(7L), eq(8L), any(PageRequest.class));
    }

    @Test
    void validatesPageBoundsBeforeQuerying() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.list(7L, null, 0, 101))
                .isInstanceOf(FileBusinessException.class)
                .extracting("code").isEqualTo("INVALID_PARAM");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.list(7L, null, -1, 50))
                .isInstanceOf(FileBusinessException.class)
                .extracting("code").isEqualTo("INVALID_PARAM");
        verify(files, never()).findByOwner_IdAndParentIsNull(eq(7L), any(PageRequest.class));
    }

    @Test
    void createsDirectoryForAuthenticatedOwnerWithinOwnedParent() {
        User owner = new User("owner@example.com", "owner", "hash");
        UserFile parent = new UserFile(owner, null, "Documents", FileNodeType.DIRECTORY);
        ReflectionTestUtils.setField(parent, "id", 8L);
        when(files.findByIdAndOwner_IdForUpdate(8L, 7L)).thenReturn(Optional.of(parent));
        when(files.existsByOwner_IdAndParent_IdAndName(7L, 8L, "Drafts")).thenReturn(false);
        when(users.findByIdForUpdate(7L)).thenReturn(Optional.of(owner));
        when(files.save(any(UserFile.class))).thenAnswer(invocation -> invocation.getArgument(0));

        FileItemResponse result = service.createDirectory(7L, 8L, " Drafts ");

        assertThat(result.name()).isEqualTo("Drafts");
        assertThat(result.type()).isEqualTo(FileNodeType.DIRECTORY);
        verify(files).findByIdAndOwner_IdForUpdate(8L, 7L);
        verify(files).existsByOwner_IdAndParent_IdAndName(7L, 8L, "Drafts");
    }

    @Test
    void automaticallyNumbersDuplicateDirectoryNamesInTheSameParent() {
        User owner = new User("owner@example.com", "owner", "hash");
        when(users.findByIdForUpdate(7L)).thenReturn(Optional.of(owner));
        when(files.existsByOwner_IdAndParentIsNullAndName(7L, "Reports")).thenReturn(true);
        when(files.existsByOwner_IdAndParentIsNullAndName(7L, "Reports（1）")).thenReturn(true);
        when(files.existsByOwner_IdAndParentIsNullAndName(7L, "Reports（2）")).thenReturn(false);
        when(files.save(any(UserFile.class))).thenAnswer(invocation -> invocation.getArgument(0));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.createDirectory(7L, null, "../private"))
                .isInstanceOf(FileBusinessException.class).extracting("code").isEqualTo("INVALID_PARAM");

        FileItemResponse result = service.createDirectory(7L, null, "Reports");

        assertThat(result.name()).isEqualTo("Reports（2）");
        assertThat(result.type()).isEqualTo(FileNodeType.DIRECTORY);
        verify(users).findByIdForUpdate(7L);
        verify(files).existsByOwner_IdAndParentIsNullAndName(7L, "Reports（2）");
        verify(files).save(any(UserFile.class));
    }

    @Test
    void rejectsRenamingDirectoryToAnotherNodesNameInTheSameParent() {
        User owner = new User("owner@example.com", "owner", "hash");
        UserFile directory = new UserFile(owner, null, "Old name", FileNodeType.DIRECTORY);
        ReflectionTestUtils.setField(directory, "id", 21L);
        when(files.findByIdAndOwner_IdForUpdate(21L, 7L)).thenReturn(Optional.of(directory));
        when(users.findByIdForUpdate(7L)).thenReturn(Optional.of(owner));
        when(files.existsByOwner_IdAndParentIsNullAndNameAndIdNot(7L, "Reports", 21L)).thenReturn(true);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.renameDirectory(7L, 21L, "Reports"))
                .isInstanceOf(FileBusinessException.class)
                .satisfies(error -> {
                    FileBusinessException conflict = (FileBusinessException) error;
                    assertThat(conflict.getStatus()).isEqualTo(409);
                    assertThat(conflict.getCode()).isEqualTo("NAME_CONFLICT");
                });
    }

    @Test
    void renamingDirectoryToItsCurrentNameSucceedsWithoutConflictCheck() {
        User owner = new User("owner@example.com", "owner", "hash");
        UserFile directory = new UserFile(owner, null, "Reports", FileNodeType.DIRECTORY);
        ReflectionTestUtils.setField(directory, "id", 21L);
        when(files.findByIdAndOwner_IdForUpdate(21L, 7L)).thenReturn(Optional.of(directory));
        when(users.findByIdForUpdate(7L)).thenReturn(Optional.of(owner));

        FileItemResponse result = service.renameDirectory(7L, 21L, "Reports");

        assertThat(result.name()).isEqualTo("Reports");
        verify(files, never()).existsByOwner_IdAndParentIsNullAndNameAndIdNot(7L, "Reports", 21L);
    }

    @Test
    void uploadsContentAndPersistsItsMetadataForTheAuthenticatedOwner() {
        User owner = new User("owner@example.com", "owner", "hash");
        when(users.findByIdForUpdate(7L)).thenReturn(Optional.of(owner));
        when(storage.store(eq(7L), any())).thenReturn(
                new FileStorageService.StoredFile("7/abc", "notes.txt", 5, "text/plain"));
        when(files.save(any(UserFile.class))).thenAnswer(invocation -> invocation.getArgument(0));
        MockMultipartFile upload = new MockMultipartFile("file", "notes.txt", "text/plain",
                "hello".getBytes(StandardCharsets.UTF_8));

        FileItemResponse result = service.upload(7L, null, upload);

        assertThat(result.name()).isEqualTo("notes.txt");
        assertThat(result.type()).isEqualTo(FileNodeType.FILE);
        assertThat(result.sizeBytes()).isEqualTo(5);
        org.mockito.ArgumentCaptor<UserFile> saved = org.mockito.ArgumentCaptor.forClass(UserFile.class);
        verify(files).save(saved.capture());
        assertThat(saved.getValue().getOwner()).isSameAs(owner);
        assertThat(saved.getValue().getStorageKey()).isEqualTo("7/abc");
    }

    @Test
    void uploadsFileIntoOwnedNestedDirectorySetsParent() {
        User owner = new User("owner@example.com", "owner", "hash");
        UserFile directory = new UserFile(owner, null, "Documents", FileNodeType.DIRECTORY);
        ReflectionTestUtils.setField(directory, "id", 8L);
        when(files.findByIdAndOwner_IdForUpdate(8L, 7L)).thenReturn(Optional.of(directory));
        when(users.findByIdForUpdate(7L)).thenReturn(Optional.of(owner));
        when(storage.store(eq(7L), any())).thenReturn(
                new FileStorageService.StoredFile("7/abc", "notes.txt", 5, "text/plain"));
        when(files.save(any(UserFile.class))).thenAnswer(invocation -> invocation.getArgument(0));
        MockMultipartFile upload = new MockMultipartFile("file", "notes.txt", "text/plain",
                "hello".getBytes(StandardCharsets.UTF_8));

        FileItemResponse result = service.upload(7L, 8L, upload);

        assertThat(result.name()).isEqualTo("notes.txt");
        assertThat(result.type()).isEqualTo(FileNodeType.FILE);
        org.mockito.ArgumentCaptor<UserFile> saved = org.mockito.ArgumentCaptor.forClass(UserFile.class);
        verify(files).save(saved.capture());
        assertThat(saved.getValue().getParent()).isSameAs(directory);
        assertThat(saved.getValue().getOwner()).isSameAs(owner);
    }

    @Test
    void doesNotStoreUploadWhenParentDirectoryIsNotOwned() {
        when(files.findByIdAndOwner_IdForUpdate(99L, 7L)).thenReturn(Optional.empty());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.upload(7L, 99L,
                new MockMultipartFile("file", "notes.txt", "text/plain", new byte[] {1})))
                .isInstanceOf(FileBusinessException.class).extracting("code").isEqualTo("FILE_NOT_FOUND");
        verifyNoInteractions(storage);
    }

    @Test
    void removesStoredBytesWhenMetadataPersistenceFails() {
        User owner = new User("owner@example.com", "owner", "hash");
        when(users.findByIdForUpdate(7L)).thenReturn(Optional.of(owner));
        when(storage.store(eq(7L), any())).thenReturn(
                new FileStorageService.StoredFile("7/abc", "notes.txt", 1, "text/plain"));
        when(files.save(any(UserFile.class))).thenThrow(new IllegalStateException("database unavailable"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.upload(7L, null,
                new MockMultipartFile("file", "notes.txt", "text/plain", new byte[] {1})))
                .isInstanceOf(IllegalStateException.class);
        verify(storage).delete("7/abc");
    }

    @Test
    void quickCheckCreatesUserMetadataWithoutReceivingFileBytesWhenPhysicalFileExists() {
        User owner = new User("owner@example.com", "owner", "hash");
        PhysicalFile physical = new PhysicalFile(
                "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
                5, "blobs/existing", "text/plain");
        ReflectionTestUtils.setField(physical, "id", 19L);
        when(users.findByIdForUpdate(7L)).thenReturn(Optional.of(owner));
        when(physicalFiles.findByFileHashAndFileSize(
                "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", 5L))
                .thenReturn(Optional.of(physical));
        when(files.save(any(UserFile.class))).thenAnswer(invocation -> {
            UserFile file = invocation.getArgument(0);
            ReflectionTestUtils.setField(file, "id", 20086L);
            return file;
        });

        QuickCheckResponse result = quickCheckService.quickCheck(7L,
                new QuickCheckRequest(
                        "2CF24DBA5FB0A30E26E83B2AC5B9E29E1B161E5C1FA7425E73043362938B9824",
                        5L, "notes.txt", null));

        assertThat(result).isEqualTo(new QuickCheckResponse(true, 20086L));
        verify(files).save(any(UserFile.class));
        verifyNoInteractions(storage);
    }

    @Test
    void quickCheckMissReturnsBeforeAnyMetadataOrStorageOperation() {
        when(users.findByIdForUpdate(7L)).thenReturn(Optional.of(new User("owner@example.com", "owner", "hash")));
        when(physicalFiles.findByFileHashAndFileSize(any(), eq(5L))).thenReturn(Optional.empty());

        QuickCheckResponse result = quickCheckService.quickCheck(7L,
                new QuickCheckRequest("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
                        5L, "notes.txt", null));

        assertThat(result).isEqualTo(new QuickCheckResponse(false, null));
        verify(files, never()).save(any(UserFile.class));
        verifyNoInteractions(storage);
    }

    @Test
    void md5MissInitializesLargeSessionButSmallFileDoesNot() {
        when(users.findByIdForUpdate(7L)).thenReturn(Optional.of(new User("owner@example.com", "owner", "hash")));
        ChunkUploadService chunks = mock(ChunkUploadService.class);
        quickCheckService.setChunks(chunks);
        long size = 3 * 1024 * 1024 * 1024L;
        String md5 = "a".repeat(32);
        when(chunks.createSession(7L, "large.txt", size, md5, null)).thenReturn(new ChunkUploadSession(
                "upload-test", 7, "large.txt", size, md5, ChunkUploadService.CHUNK_SIZE, java.time.Instant.now(), null));
        QuickCheckResponse response = quickCheckService.quickCheck(7L, new QuickCheckRequest(md5, size, "large.txt", null));
        assertThat(response.uploadId()).isEqualTo("upload-test");
        assertThat(response.totalParts()).isEqualTo(384);
        assertThat(response.chunkSize()).isEqualTo(ChunkUploadService.CHUNK_SIZE);
        QuickCheckResponse small = quickCheckService.quickCheck(7L, new QuickCheckRequest(md5, 2 * 1024 * 1024 * 1024L, "large.txt", null));
        assertThat(small.uploadId()).isNull();
        verify(chunks, org.mockito.Mockito.times(1)).createSession(anyLong(), anyString(), anyLong(), anyString(), any());
        verifyNoInteractions(storage);
    }

    @Test
    void md5HitCreatesRecordWithoutChunkSessionOrStorage() {
        when(users.findByIdForUpdate(7L)).thenReturn(Optional.of(new User("owner@example.com", "owner", "hash")));
        ChunkUploadService chunks = mock(ChunkUploadService.class);
        quickCheckService.setChunks(chunks);
        String md5 = "a".repeat(32);
        long size = 3 * 1024 * 1024 * 1024L;
        when(physicalFiles.findFirstByFileMd5AndFileSize(md5, size)).thenReturn(Optional.of(
                new PhysicalFile("b".repeat(64), size, "blobs/existing", "text/plain")));
        when(files.save(any(UserFile.class))).thenAnswer(invocation -> invocation.getArgument(0));
        assertThat(quickCheckService.quickCheck(7L, new QuickCheckRequest(md5, size, "large.txt", null)).exist()).isTrue();
        verifyNoInteractions(chunks, storage);
    }

    @Test
    void md5MissReusesProvidedSessionAfterCheckingPhysicalFiles() {
        ChunkUploadService chunks = mock(ChunkUploadService.class);
        quickCheckService.setChunks(chunks);
        String md5 = "a".repeat(32);
        long size = 3 * 1024 * 1024 * 1024L;
        when(chunks.resumeSession(7L, "upload-existing", "large.txt", size, md5))
                .thenReturn(new ChunkUploadSession("upload-existing", 7, "large.txt", size, md5,
                        ChunkUploadService.CHUNK_SIZE, java.time.Instant.now(), null));
        QuickCheckResponse response = quickCheckService.quickCheck(7L,
                new QuickCheckRequest(md5, size, "large.txt", null, "upload-existing"));
        assertThat(response.uploadId()).isEqualTo("upload-existing");
        verify(chunks, never()).createSession(anyLong(), anyString(), anyLong(), anyString(), any());
        verifyNoInteractions(storage);
    }

    @Test
    void createsDirectoryInRootForAuthenticatedOwner() {
        User owner = new User("owner@example.com", "owner", "hash");
        when(users.findByIdForUpdate(7L)).thenReturn(Optional.of(owner));
        when(files.existsByOwner_IdAndParentIsNullAndName(7L, "Travel")).thenReturn(false);
        when(files.save(any(UserFile.class))).thenAnswer(invocation -> invocation.getArgument(0));

        FileItemResponse result = service.createDirectory(7L, null, " Travel ");

        assertThat(result.name()).isEqualTo("Travel");
        assertThat(result.type()).isEqualTo(FileNodeType.DIRECTORY);
        org.mockito.ArgumentCaptor<UserFile> saved = org.mockito.ArgumentCaptor.forClass(UserFile.class);
        verify(files).save(saved.capture());
        assertThat(saved.getValue().getParent()).isNull();
        assertThat(saved.getValue().getOwner()).isSameAs(owner);
        verify(users).findByIdForUpdate(7L);
    }

    @Test
    void rejectsBlankDirectoryName() {
        for (String blank : List.of("", "   ", "\t\n")) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.createDirectory(7L, null, blank))
                    .isInstanceOf(FileBusinessException.class)
                    .extracting("code").isEqualTo("INVALID_PARAM");
        }
    }

    @Test
    void rejectsDirectoryNameOver255Characters() {
        String overlong = "a".repeat(256);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.createDirectory(7L, null, overlong))
                .isInstanceOf(FileBusinessException.class)
                .extracting("code").isEqualTo("INVALID_PARAM");
    }

    @Test
    void rejectsDirectoryNamesWithIllegalCharacters() {
        for (String illegal : List.of("a/b", "a<b", "a:b", "a\"b", "a\\b", "a|b", "a?b", "a*b", "ab")) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.createDirectory(7L, null, illegal))
                    .isInstanceOf(FileBusinessException.class)
                    .extracting("code").isEqualTo("INVALID_PARAM");
        }
    }

    @Test
    void rejectsCreateInNonexistentParentDirectory() {
        when(files.findByIdAndOwner_IdForUpdate(999L, 7L)).thenReturn(Optional.empty());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.createDirectory(7L, 999L, "Travel"))
                .isInstanceOf(FileBusinessException.class)
                .extracting("code").isEqualTo("FILE_NOT_FOUND");
    }

    @Test
    void rejectsCreateInAnotherOwnersDirectory() {
        User other = new User("other@example.com", "other", "hash");
        UserFile directory = new UserFile(other, null, "Private", FileNodeType.DIRECTORY);
        ReflectionTestUtils.setField(directory, "id", 8L);
        // 目录存在但属于 other，而非当前用户 7，因此 findByIdAndOwner_IdForUpdate 查不到。
        when(files.findByIdAndOwner_IdForUpdate(8L, 7L)).thenReturn(Optional.empty());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.createDirectory(7L, 8L, "Travel"))
                .isInstanceOf(FileBusinessException.class)
                .extracting("code").isEqualTo("FILE_NOT_FOUND");
    }

    @Test
    void rejectsCreateWhenParentIsFileNotDirectory() {
        User owner = new User("owner@example.com", "owner", "hash");
        UserFile file = new UserFile(owner, null, "notes.txt", FileNodeType.FILE);
        ReflectionTestUtils.setField(file, "id", 8L);
        when(files.findByIdAndOwner_IdForUpdate(8L, 7L)).thenReturn(Optional.of(file));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.createDirectory(7L, 8L, "Travel"))
                .isInstanceOf(FileBusinessException.class)
                .extracting("code").isEqualTo("FILE_NOT_FOUND");
    }

    @Test
    void autoNumbersDuplicateDirectoryNameAgainstExistingFileInSameParent() {
        User owner = new User("owner@example.com", "owner", "hash");
        when(users.findByIdForUpdate(7L)).thenReturn(Optional.of(owner));
        when(files.existsByOwner_IdAndParentIsNullAndName(7L, "Report")).thenReturn(true);
        when(files.existsByOwner_IdAndParentIsNullAndName(7L, "Report（1）")).thenReturn(false);
        when(files.save(any(UserFile.class))).thenAnswer(invocation -> invocation.getArgument(0));

        FileItemResponse result = service.createDirectory(7L, null, "Report");

        assertThat(result.name()).isEqualTo("Report（1）");
        assertThat(result.type()).isEqualTo(FileNodeType.DIRECTORY);
    }

    @Test
    void insertsFullWidthNumberBeforeFileExtensionWhenResolvingDuplicate() {
        assertThat(FileService.withNumberSuffix("report.pdf", 1, true)).isEqualTo("report（1）.pdf");
        assertThat(FileService.withNumberSuffix("archive.tar.gz", 1, true))
                .isEqualTo("archive.tar（1）.gz");
        assertThat(FileService.withNumberSuffix("无扩展名", 2, true)).isEqualTo("无扩展名（2）");
        assertThat(FileService.withNumberSuffix("旅行", 1, false)).isEqualTo("旅行（1）");
    }

}
