package com.networkdisk.file;

import static org.assertj.core.api.Assertions.assertThat;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DownloadArchiveServiceTest {
    @TempDir Path directory;

    @Test
    void createsUtf8ZipWithNestedPathsAndCleansTaskDirectory() throws Exception {
        Path source = directory.resolve("source.txt");
        Files.writeString(source, "文件内容", StandardCharsets.UTF_8);
        DownloadArchiveService service = new DownloadArchiveService(directory.resolve("temporary"));
        DownloadArchiveService.PreparedArchive archive = service.create(42L, "项目资料", List.of(
                new FileService.FileDownload("项目资料/", null),
                new FileService.FileDownload("项目资料/报告.txt", source)));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Files.copy(archive.path(), bytes);
        try (ZipInputStream zip = new ZipInputStream(
                new java.io.ByteArrayInputStream(bytes.toByteArray()), StandardCharsets.UTF_8)) {
            assertThat(zip.getNextEntry().getName()).isEqualTo("项目资料/");
            assertThat(zip.getNextEntry().getName()).isEqualTo("项目资料/报告.txt");
            assertThat(new String(zip.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("文件内容");
        }
        assertThat(archive.taskDirectory().startsWith(directory.resolve("temporary/42"))).isTrue();
        service.cleanup(archive.taskDirectory());
        assertThat(archive.taskDirectory()).doesNotExist();
    }
}
