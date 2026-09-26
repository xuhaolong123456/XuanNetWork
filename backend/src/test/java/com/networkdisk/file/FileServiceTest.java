package com.networkdisk.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import com.networkdisk.auth.User;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

class FileServiceTest {
    private final UserFileRepository files = mock(UserFileRepository.class);
    private final FileService service = new FileService(files);

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
}
