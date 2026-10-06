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
        assertThat(stored.fileMd5()).isEqualTo("5d41402abc4b2a76b9719d911017c592");
        assertThat(Files.readString(storageRoot.resolve(stored.storageKey()))).isEqualTo("hello");
        assertThat(stored.storageKey()).startsWith("blobs/");
    }

    @Test
    void rejectsEmptyOversizedAndUnsafeFileNames() {
        FileStorageService storage = new FileStorageService(storageRoot, 3);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> storage.store(7L,
                new MockMultipartFile("file", "empty.txt", "text/plain", new byte[0])))
                .isInstanceOf(FileBusinessException.class).extracting("code", "status").containsExactly("EMPTY_FILE", 401);
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

    @Test
    void readsUtf8MarkdownAndTextIncludingBomAndWhitespace() {
        FileStorageService storage = new FileStorageService(storageRoot, 100);
        for (String name : java.util.List.of("中文.MD", "notes.TXT")) {
            var stored = storage.store(7L, new MockMultipartFile("file", name, "application/octet-stream",
                    "\uFEFF# 中文\r\n  hello\n".getBytes(StandardCharsets.UTF_8)));
            assertThat(storage.readText(7L, stored.storageKey())).isEqualTo("# 中文\r\n  hello\n");
        }
    }

    @Test
    void validatesUploadTypesAndChecksExactSizeBoundary() {
        FileStorageService storage = new FileStorageService(storageRoot, 3);
        var exact = storage.store(7L, new MockMultipartFile("file", "a.txt", "text/plain", new byte[3]));
        assertThat(storage.readText(7L, exact.storageKey())).hasSize(3);
        for (String name : java.util.List.of("a.docx", "a.csv", "a.xlsx", "a.pdf", "a.html", "a.pptx")) {
            assertThat(storage.store(7L, new MockMultipartFile("file", name, "application/octet-stream", new byte[] {1})).name())
                    .isEqualTo(name);
        }
        for (String name : java.util.List.of("a.exe", "txt", "a.md.exe", "a.")) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> storage.store(7L,
                    new MockMultipartFile("file", name, "text/plain", new byte[] {1})))
                    .isInstanceOf(FileBusinessException.class).extracting("status").isEqualTo(415);
        }
    }

    @Test
    void capsConfiguredUploadLimitAtTwoGiB() throws Exception {
        FileStorageService storage = new FileStorageService(storageRoot, Long.MAX_VALUE);
        var upload = org.mockito.Mockito.mock(org.springframework.web.multipart.MultipartFile.class);
        org.mockito.Mockito.when(upload.getOriginalFilename()).thenReturn("large.txt");
        org.mockito.Mockito.when(upload.getSize()).thenReturn(2147483648L);
        assertThat(storage.store(7L, upload).sizeBytes()).isEqualTo(2147483648L);
        org.mockito.Mockito.when(upload.getSize()).thenReturn(2147483649L);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> storage.store(7L, upload))
                .isInstanceOf(FileBusinessException.class).extracting("status").isEqualTo(413);
    }

    @Test
    void refusesMissingForeignAndEscapedStoragePaths() throws Exception {
        FileStorageService storage = new FileStorageService(storageRoot, 100);
        Files.createDirectories(storageRoot.resolve("7"));
        Files.writeString(storageRoot.resolve("outside.txt"), "secret");
        for (String key : java.util.Arrays.asList(null, "", "7/missing", "8/private", "7/../outside.txt",
                storageRoot.resolve("outside.txt").toString(), "7/\u0000")) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> storage.readText(7L, key))
                    .isInstanceOf(FileBusinessException.class).extracting("status").isEqualTo(404);
        }
    }

    @Test
    void refusesInvalidEncodingAndOversizedStoredContentWithoutPartialResults() throws Exception {
        FileStorageService storage = new FileStorageService(storageRoot, 3);
        Files.createDirectories(storageRoot.resolve("7"));
        Files.write(storageRoot.resolve("7/bad"), new byte[] {(byte) 0xc3, 0x28});
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> storage.readText(7L, "7/bad"))
                .isInstanceOf(FileBusinessException.class).extracting("code").isEqualTo("UNSUPPORTED_ENCODING");
        Files.writeString(storageRoot.resolve("7/large"), "1234");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> storage.readText(7L, "7/large"))
                .isInstanceOf(FileBusinessException.class).extracting("status").isEqualTo(413);
    }

    @Test
    void resolvesOnlyCurrentOwnersRealFilesForDownload() throws Exception {
        FileStorageService storage = new FileStorageService(storageRoot, 3);
        Files.createDirectories(storageRoot.resolve("7"));
        Files.createDirectories(storageRoot.resolve("8"));
        Files.writeString(storageRoot.resolve("7/good"), "abc");
        Files.writeString(storageRoot.resolve("8/private"), "secret");
        assertThat(storage.resolveDownload(7L, "7/good")).isEqualTo(storageRoot.resolve("7/good").toRealPath());
        for (String key : java.util.Arrays.asList(null, "", "8/private", "7/missing", "7/../8/private")) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> storage.resolveDownload(7L, key))
                    .isInstanceOf(FileBusinessException.class).extracting("status").isEqualTo(404);
        }
        Files.writeString(storageRoot.resolve("7/large"), "abcd");
        assertThat(storage.resolveDownload(7L, "7/large"))
                .isEqualTo(storageRoot.resolve("7/large").toRealPath());
    }
}
