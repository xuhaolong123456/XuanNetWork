package com.networkdisk.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.networkdisk.auth.User;
import com.networkdisk.auth.UserRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class FilePreviewServiceTest {
    private final UserFileRepository files = mock(UserFileRepository.class);
    private final FileStorageService storage = mock(FileStorageService.class);
    private final FileService service = new FileService(files, mock(UserRepository.class), storage);

    @Test
    void readsContentOnlyAfterOwnershipCheck() {
        UserFile file = file("notes.MD", FileNodeType.FILE);
        when(files.findByIdAndOwner_Id(12L, 7L)).thenReturn(Optional.of(file));
        when(storage.readText(7L, "7/key")).thenReturn("# 中文");
        assertThat(service.preview(7L, 12L)).isEqualTo(new FilePreviewResponse(12L, "notes.MD", "md", "# 中文"));
        var order = inOrder(files, storage);
        order.verify(files).findByIdAndOwner_Id(12L, 7L);
        order.verify(storage).readText(7L, "7/key");
    }

    @Test
    void rejectsInvalidIdsBeforeDatabaseAccess() {
        for (long id : new long[] {0, -1}) {
            assertThatThrownBy(() -> service.preview(7L, id)).isInstanceOf(FileBusinessException.class)
                    .extracting("status").isEqualTo(400);
        }
        verifyNoInteractions(files, storage);
    }

    @Test
    void hidesMissingForeignDeletedAndDirectoryResources() {
        when(files.findByIdAndOwner_Id(12L, 7L)).thenReturn(Optional.empty());
        assertNotFound();
        UserFile deleted = file("a.txt", FileNodeType.FILE);
        ReflectionTestUtils.setField(deleted, "deleted", true);
        when(files.findByIdAndOwner_Id(12L, 7L)).thenReturn(Optional.of(deleted));
        assertNotFound();
        when(files.findByIdAndOwner_Id(12L, 7L)).thenReturn(Optional.of(file("folder", FileNodeType.DIRECTORY)));
        assertNotFound();
        UserFile blocked = file("a.txt", FileNodeType.FILE);
        ReflectionTestUtils.setField(blocked, "previewAllowed", false);
        when(files.findByIdAndOwner_Id(12L, 7L)).thenReturn(Optional.of(blocked));
        assertNotFound();
        verifyNoInteractions(storage);
    }

    @Test
    void rejectsUnsupportedLegacyFilesAndPropagatesStorageFailures() {
        when(files.findByIdAndOwner_Id(12L, 7L)).thenReturn(Optional.of(file("a.pdf", FileNodeType.FILE)));
        assertThatThrownBy(() -> service.preview(7L, 12L)).isInstanceOf(FileBusinessException.class)
                .extracting("status").isEqualTo(415);
        verifyNoInteractions(storage);
        when(files.findByIdAndOwner_Id(12L, 7L)).thenReturn(Optional.of(file("a.txt", FileNodeType.FILE)));
        when(storage.readText(7L, "7/key")).thenThrow(new FileBusinessException("FILE_STORAGE_UNAVAILABLE", "读取失败", 503));
        assertThatThrownBy(() -> service.preview(7L, 12L)).isInstanceOf(FileBusinessException.class)
                .extracting("status").isEqualTo(503);
    }

    private void assertNotFound() {
        assertThatThrownBy(() -> service.preview(7L, 12L)).isInstanceOf(FileBusinessException.class)
                .extracting("status").isEqualTo(404);
    }

    private UserFile file(String name, FileNodeType type) {
        UserFile file = new UserFile(new User("owner@example.com", "owner", "hash"), null, name, type, 5, "text/plain", "7/key");
        ReflectionTestUtils.setField(file, "id", 12L);
        return file;
    }
}
