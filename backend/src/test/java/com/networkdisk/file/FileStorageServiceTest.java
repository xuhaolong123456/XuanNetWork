package com.networkdisk.file;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

class FileStorageServiceTest {
    @TempDir
    Path storageRoot;

    @Test
    void storesContentUnderOwnerDirectoryUsingGeneratedKeyAndSafeDisplayName() throws Exception {
        FileStorageService storage = new FileStorageService(storageRoot, 100);
        MockMultipartFile upload = new MockMultipartFile("file", "C:\\fakepath\\notes.txt", "text/plain",
                "hello".getBytes(StandardCharsets.UTF_8));

        FileStorageService.StoredFile stored = storage.store(7L, upload);

        assertThat(stored.name()).isEqualTo("notes.txt");
        assertThat(stored.sizeBytes()).isEqualTo(5);
        assertThat(Files.readString(storageRoot.resolve(stored.storageKey()))).isEqualTo("hello");
        assertThat(stored.storageKey()).startsWith("7/");
    }

    @Test
    void rejectsEmptyOversizedAndUnsafeFileNames() {
        FileStorageService storage = new FileStorageService(storageRoot, 3);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> storage.store(7L,
                new MockMultipartFile("file", "empty.txt", "text/plain", new byte[0])))
                .isInstanceOf(FileBusinessException.class).extracting("code").isEqualTo("INVALID_FILE");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> storage.store(7L,
                new MockMultipartFile("file", "large.txt", "text/plain", new byte[4])))
                .isInstanceOf(FileBusinessException.class).extracting("code").isEqualTo("FILE_TOO_LARGE");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> storage.store(7L,
                new MockMultipartFile("file", "bad:name.txt", "text/plain", new byte[] {1})))
                .isInstanceOf(FileBusinessException.class).extracting("code").isEqualTo("INVALID_FILE_NAME");
    }

    @Test
    void deletesStoredBytesOnlyWithinStorageRoot() throws Exception {
        FileStorageService storage = new FileStorageService(storageRoot, 100);
        FileStorageService.StoredFile stored = storage.store(7L,
                new MockMultipartFile("file", "notes.txt", "text/plain", new byte[] {1}));
        Path storedPath = storageRoot.resolve(stored.storageKey());

        storage.delete(stored.storageKey());

        assertThat(Files.exists(storedPath)).isFalse();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> storage.delete("../outside"))
                .isInstanceOf(FileBusinessException.class).extracting("code").isEqualTo("INVALID_FILE");
    }
}
