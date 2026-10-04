package com.networkdisk.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.networkdisk.auth.User;
import com.networkdisk.auth.UserRepository;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class FileDownloadServiceTest {
    private final UserFileRepository files = mock(UserFileRepository.class);
    private final FileStorageService storage = mock(FileStorageService.class);
    private final FileService service = new FileService(files, mock(UserRepository.class), storage);

    @Test
    void downloadsOnlyMatchingOwnedFileAfterPermissionChecks() {
        UserFile file = file("同名.txt");
        when(files.findByIdAndOwner_Id(12L, 7L)).thenReturn(Optional.of(file));
        when(storage.resolveDownload(7L, "7/key")).thenReturn(Path.of("stored"));
        assertThat(service.download(7L, "同名.txt", 12L))
                .isEqualTo(new FileService.FileDownload("同名.txt", Path.of("stored")));
        var order = inOrder(files, storage);
        order.verify(files).findByIdAndOwner_Id(12L, 7L);
        order.verify(storage).resolveDownload(7L, "7/key");
    }

    @Test
    void rejectsInvalidParametersBeforeLookup() {
        for (String name : new String[] {"", " "}) {
            assertThatThrownBy(() -> service.download(7L, name, 12L))
                    .isInstanceOf(FileBusinessException.class).extracting("status").isEqualTo(401);
        }
        assertThatThrownBy(() -> service.download(7L, null, 12L))
                .isInstanceOf(FileBusinessException.class).extracting("status").isEqualTo(401);
        assertThatThrownBy(() -> service.download(7L, "a.txt", 0L))
                .isInstanceOf(FileBusinessException.class).extracting("status").isEqualTo(401);
        verifyNoInteractions(files, storage);
    }

    @Test
    void hidesMissingForeignDeletedDirectoryAndDeniedRecords() {
        when(files.findByIdAndOwner_Id(12L, 7L)).thenReturn(Optional.empty());
        assertNotFound();
        UserFile file = file("a.txt");
        when(files.findByIdAndOwner_Id(12L, 7L)).thenReturn(Optional.of(file));
        assertThatThrownBy(() -> service.download(7L, "wrong.txt", 12L))
                .isInstanceOf(FileBusinessException.class).extracting("status").isEqualTo(404);
        ReflectionTestUtils.setField(file, "deleted", true);
        assertNotFound();
        ReflectionTestUtils.setField(file, "deleted", false);
        ReflectionTestUtils.setField(file, "downloadAllowed", false);
        assertNotFound();
        ReflectionTestUtils.setField(file, "downloadAllowed", true);
        ReflectionTestUtils.setField(file, "previewAllowed", false);
        assertNotFound();
        when(files.findByIdAndOwner_Id(12L, 7L)).thenReturn(Optional.of(
                new UserFile(new User("owner@example.com", "owner", "hash"), null, "a.txt", FileNodeType.DIRECTORY)));
        assertNotFound();
        verifyNoInteractions(storage);
    }

    @Test
    void filenameOnlyWorksWhenUniqueAndRejectsAmbiguity() {
        UserFile file = file("a.txt");
        when(files.findActiveFilesByOwnerAndName(7L, "a.txt")).thenReturn(List.of(file));
        when(storage.resolveDownload(7L, "7/key")).thenReturn(Path.of("stored"));
        assertThat(service.download(7L, "a.txt", null).name()).isEqualTo("a.txt");
        when(files.findActiveFilesByOwnerAndName(7L, "a.txt")).thenReturn(List.of(file, file));
        assertThatThrownBy(() -> service.download(7L, "a.txt", null))
                .isInstanceOf(FileBusinessException.class).extracting("status").isEqualTo(409);
    }

    @Test
    void batchChecksEveryRecordBeforeReturningAnyDownload() {
        UserFile first = file("a.txt");
        UserFile second = file("b.txt");
        when(files.findByIdAndOwner_Id(12L, 7L)).thenReturn(Optional.of(first));
        when(files.findByIdAndOwner_Id(13L, 7L)).thenReturn(Optional.of(second));
        when(storage.resolveDownload(7L, "7/key")).thenReturn(Path.of("stored"));
        assertThat(service.downloadMany(7L, List.of(12L, 13L, 12L))).hasSize(2);
        ReflectionTestUtils.setField(second, "downloadAllowed", false);
        assertThatThrownBy(() -> service.downloadMany(7L, List.of(12L, 13L)))
                .isInstanceOf(FileBusinessException.class).extracting("status").isEqualTo(404);
        assertThatThrownBy(() -> service.downloadMany(7L, List.of()))
                .isInstanceOf(FileBusinessException.class).extracting("status").isEqualTo(400);
        assertThatThrownBy(() -> service.downloadMany(7L, java.util.Collections.nCopies(51, 12L)))
                .isInstanceOf(FileBusinessException.class).extracting("status").isEqualTo(400);
    }

    private void assertNotFound() {
        assertThatThrownBy(() -> service.download(7L, "a.txt", 12L))
                .isInstanceOf(FileBusinessException.class).extracting("status").isEqualTo(404);
    }

    private UserFile file(String name) {
        UserFile file = new UserFile(new User("owner@example.com", "owner", "hash"), null,
                name, FileNodeType.FILE, 5, "text/plain", "7/key");
        ReflectionTestUtils.setField(file, "id", 12L);
        return file;
    }
}
