package com.networkdisk.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.networkdisk.auth.UserRepository;
import java.util.List;
import org.junit.jupiter.api.Test;

class FolderTreeServiceTest {
    private final UserFileRepository files = mock(UserFileRepository.class);
    private final FileService service = new FileService(files, mock(UserRepository.class), mock(FileStorageService.class));

    @Test
    void groupsDirectoriesByParentIdAndUsesZeroForRoots() {
        when(files.findActiveDirectoryRows(7L)).thenReturn(List.of(
                new FolderTreeRow(3L, 1L, "子目录"),
                new FolderTreeRow(1L, null, "根目录"),
                new FolderTreeRow(2L, null, "另一个目录")));
        List<FolderTreeNodeResponse> tree = service.folderTree(7L);
        assertThat(tree).extracting(FolderTreeNodeResponse::id).containsExactly(1L, 2L);
        assertThat(tree.get(0).parentId()).isZero();
        assertThat(tree.get(0).children()).extracting(FolderTreeNodeResponse::id).containsExactly(3L);
        assertThat(tree.get(0).children().get(0).parentId()).isEqualTo(1L);
        verify(files).findActiveDirectoryRows(7L);
    }

    @Test
    void returnsEmptyOnlyWhenNoDirectoryRowsExist() {
        assertThat(service.folderTree(7L)).isEmpty();
    }

    @Test
    void rejectsOrphanedStructureInsteadOfSilentlyOmittingFolders() {
        when(files.findActiveDirectoryRows(7L)).thenReturn(List.of(new FolderTreeRow(3L, 99L, "孤立目录")));
        assertThatThrownBy(() -> service.folderTree(7L))
                .isInstanceOf(FileBusinessException.class).extracting("status").isEqualTo(409);
    }
}
