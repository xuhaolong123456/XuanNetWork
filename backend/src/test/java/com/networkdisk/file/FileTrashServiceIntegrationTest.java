package com.networkdisk.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.networkdisk.auth.User;
import com.networkdisk.auth.UserRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;

@DataJpaTest(showSql = false)
@Import({FileService.class, FileTrashService.class, FileTrashServiceIntegrationTest.Beans.class})
class FileTrashServiceIntegrationTest {
    @Autowired UserFileRepository files;
    @Autowired UserRepository users;
    @Autowired FileTrashService trash;
    @Autowired FileStorageService storage;
    private User owner;
    private User other;

    @BeforeEach
    void setUp() {
        owner = users.saveAndFlush(new User("owner@example.com", "owner", "hash"));
        other = users.saveAndFlush(new User("other@example.com", "other", "hash"));
    }

    @Test
    void recursivelyDeletesMetadataAndRestoresOnlyTheSameBatch() {
        UserFile root = node(owner, null, "root", FileNodeType.DIRECTORY);
        UserFile sub = node(owner, root, "sub", FileNodeType.DIRECTORY);
        UserFile file = node(owner, sub, "notes.pdf", FileNodeType.FILE);
        UserFile earlier = node(owner, sub, "earlier", FileNodeType.FILE);
        trash.delete(owner.getId(), List.of(earlier.getId()));
        trash.delete(owner.getId(), List.of(root.getId(), sub.getId(), root.getId()));
        assertThat(files.findByOwner_IdAndParentIsNull(owner.getId(), PageRequest.of(0, 50))).isEmpty();
        assertThat(trash.list(owner.getId(), 0, 50).items()).hasSize(4);
        assertThat(files.findById(file.getId()).orElseThrow().getDeletedAt()).isNotNull();
        trash.restore(owner.getId(), List.of(root.getId(), file.getId()));
        assertThat(files.findByIdAndOwner_Id(file.getId(), owner.getId())).isPresent();
        assertThat(files.findByIdAndOwner_Id(earlier.getId(), owner.getId())).isEmpty();
        assertThat(files.count()).isEqualTo(4);
        verifyNoInteractions(storage);
    }

    @Test
    void restoresAncestorsAndPreservesExtensionOnConflict() {
        UserFile root = node(owner, null, "root", FileNodeType.DIRECTORY);
        UserFile file = node(owner, root, "notes.pdf", FileNodeType.FILE);
        UserFile sibling = node(owner, root, "sibling", FileNodeType.FILE);
        trash.delete(owner.getId(), List.of(root.getId()));
        node(owner, root, "notes.pdf", FileNodeType.FILE);
        trash.restore(owner.getId(), List.of(file.getId()));
        assertThat(files.findByIdAndOwner_Id(root.getId(), owner.getId())).isPresent();
        assertThat(files.findById(file.getId()).orElseThrow().getName()).isEqualTo("notes（1）.pdf");
        assertThat(files.findByIdAndOwner_Id(sibling.getId(), owner.getId())).isEmpty();
        trash.restore(owner.getId(), List.of(file.getId()));
        assertThat(files.findById(file.getId()).orElseThrow().getName()).isEqualTo("notes（1）.pdf");
        verifyNoInteractions(storage);
    }

    @Test
    void validatesWholeBatchBeforeChangingAnyMetadata() {
        UserFile mine = node(owner, null, "mine", FileNodeType.FILE);
        UserFile foreign = node(other, null, "foreign", FileNodeType.FILE);
        assertThatThrownBy(() -> trash.delete(owner.getId(), List.of(mine.getId(), foreign.getId())))
                .isInstanceOf(FileBusinessException.class).extracting("status").isEqualTo(403);
        assertThat(files.findById(mine.getId()).orElseThrow().isDeleted()).isFalse();
        assertThatThrownBy(() -> trash.delete(owner.getId(), List.of(mine.getId(), Long.MAX_VALUE)))
                .isInstanceOf(FileBusinessException.class).extracting("status").isEqualTo(404);
        assertThat(files.findById(mine.getId()).orElseThrow().isDeleted()).isFalse();
        assertThatThrownBy(() -> trash.delete(owner.getId(), List.of()))
                .isInstanceOf(FileBusinessException.class).extracting("status").isEqualTo(400);
        verifyNoInteractions(storage);
    }

    @Test
    void traversesMultipleChunksAndIsolatesTrashByOwner() {
        UserFile root = node(owner, null, "root", FileNodeType.DIRECTORY);
        for (int index = 0; index < 205; index++) node(owner, root, "file" + index, FileNodeType.FILE);
        UserFile foreign = node(other, null, "foreign", FileNodeType.FILE);
        trash.delete(other.getId(), List.of(foreign.getId()));
        trash.delete(owner.getId(), List.of(root.getId()));
        trash.delete(owner.getId(), List.of(root.getId()));
        assertThat(trash.list(owner.getId(), 0, 100).page().totalElements()).isEqualTo(206);
        assertThat(trash.list(other.getId(), 0, 50).items()).hasSize(1);
        trash.restore(owner.getId(), List.of(root.getId()));
        assertThat(files.findByOwner_IdAndParent_Id(owner.getId(), root.getId(), PageRequest.of(0, 100)).getTotalElements()).isEqualTo(205);
        assertThat(trash.list(owner.getId(), 0, 100).items()).isEmpty();
        verifyNoInteractions(storage);
    }

    private UserFile node(User user, UserFile parent, String name, FileNodeType type) {
        return files.saveAndFlush(new UserFile(user, parent, name, type));
    }

    @TestConfiguration
    static class Beans {
        @Bean FileStorageService storage() { return mock(FileStorageService.class); }
    }
}
