package com.networkdisk.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.networkdisk.auth.User;
import com.networkdisk.auth.UserRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class FileMoveServiceTest {
    private final UserFileRepository files = mock(UserFileRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final FileStorageService storage = mock(FileStorageService.class);
    private final FileService service = new FileService(files, users, storage);

    @Test
    void movesFileAndAddsNumberBeforeExtensionWhenNameConflicts() {
        User owner = new User("owner@example.com", "owner", "hash");
        UserFile target = directory(owner, null, 5L, "Documents");
        UserFile file = file(owner, null, 1L, "a.md");
        when(users.findByIdForUpdate(7L)).thenReturn(Optional.of(owner));
        when(files.findByIdAndOwner_IdForUpdate(5L, 7L)).thenReturn(Optional.of(target));
        when(files.findByIdAndOwner_IdForUpdate(1L, 7L)).thenReturn(Optional.of(file));
        when(files.existsByOwner_IdAndParent_IdAndNameAndIdNot(7L, 5L, "a.md", 1L)).thenReturn(true);
        when(files.existsByOwner_IdAndParent_IdAndNameAndIdNot(7L, 5L, "a（1）.md", 1L)).thenReturn(false);

        List<FileItemResponse> result = service.move(7L, List.of(1L), 5L);

        assertThat(result).extracting(FileItemResponse::name).containsExactly("a（1）.md");
        assertThat(file.getName()).isEqualTo("a（1）.md");
        assertThat(file.getParent()).isSameAs(target);
    }

    @Test
    void rejectsFileAsMoveTarget() {
        User owner = new User("owner@example.com", "owner", "hash");
        UserFile target = file(owner, null, 5L, "target.txt");
        when(users.findByIdForUpdate(7L)).thenReturn(Optional.of(owner));
        when(files.findByIdAndOwner_IdForUpdate(5L, 7L)).thenReturn(Optional.of(target));

        assertThatThrownBy(() -> service.move(7L, List.of(1L), 5L))
                .isInstanceOf(FileBusinessException.class)
                .satisfies(error -> {
                    FileBusinessException exception = (FileBusinessException) error;
                    assertThat(exception.getStatus()).isEqualTo(400);
                    assertThat(exception.getCode()).isEqualTo("INVALID_PARAM");
                });
    }

    @Test
    void rejectsMovingDirectoryIntoItsDescendant() {
        User owner = new User("owner@example.com", "owner", "hash");
        UserFile parent = directory(owner, null, 1L, "Parent");
        UserFile child = directory(owner, parent, 2L, "Child");
        when(users.findByIdForUpdate(7L)).thenReturn(Optional.of(owner));
        when(files.findByIdAndOwner_IdForUpdate(2L, 7L)).thenReturn(Optional.of(child));
        when(files.findByIdAndOwner_IdForUpdate(1L, 7L)).thenReturn(Optional.of(parent));

        assertThatThrownBy(() -> service.move(7L, List.of(1L), 2L))
                .isInstanceOf(FileBusinessException.class)
                .satisfies(error -> {
                    FileBusinessException exception = (FileBusinessException) error;
                    assertThat(exception.getStatus()).isEqualTo(400);
                    assertThat(exception.getCode()).isEqualTo("INVALID_PARAM");
                });
    }

    private UserFile directory(User owner, UserFile parent, long id, String name) {
        UserFile directory = new UserFile(owner, parent, name, FileNodeType.DIRECTORY);
        ReflectionTestUtils.setField(directory, "id", id);
        return directory;
    }

    private UserFile file(User owner, UserFile parent, long id, String name) {
        UserFile file = new UserFile(owner, parent, name, FileNodeType.FILE, 5, "text/plain", "7/key");
        ReflectionTestUtils.setField(file, "id", id);
        return file;
    }
}
