package com.networkdisk.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.alibaba.otter.canal.protocol.CanalEntry;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;

class CanalSearchIndexConsumerTest {
    @Test
    void upsertsInsertedUserFileAndRemovesSoftDeletedRecord() throws Exception {
        ElasticsearchOperations elasticsearch = mock(ElasticsearchOperations.class);
        CanalEntry.Entry inserted = rowEntry(CanalEntry.EventType.INSERT, columns(
                "id", "18", "user_id", "7", "parent_id", "42", "name", "项目计划.md",
                "node_type", "2", "size_bytes", "128", "mime_type", "text/markdown",
                "updated_at", "2026-10-09 12:00:00", "download_allowed", "1",
                "preview_allowed", "1", "is_delete", "0"));
        CanalEntry.Entry deleted = rowEntry(CanalEntry.EventType.UPDATE, columns(
                "id", "19", "user_id", "7", "parent_id", "42", "name", "旧文件.txt",
                "node_type", "FILE", "size_bytes", "0", "updated_at", "2026-10-09 12:00:00",
                "is_delete", "1"));

        CanalSearchIndexConsumer.processEntries(List.of(inserted, deleted), elasticsearch);

        ArgumentCaptor<FileSearchDocument> document = ArgumentCaptor.forClass(FileSearchDocument.class);
        verify(elasticsearch).save(document.capture());
        assertThat(document.getValue().getType()).isEqualTo("FILE");
        verify(elasticsearch).delete("19", FileSearchDocument.class);
    }

    @Test
    void deletesHardDeletedRecordsByPrimaryKey() throws Exception {
        ElasticsearchOperations elasticsearch = mock(ElasticsearchOperations.class);
        CanalEntry.Entry deleted = rowEntry(CanalEntry.EventType.DELETE, columns("id", "25"));

        CanalSearchIndexConsumer.processEntries(List.of(deleted), elasticsearch);

        verify(elasticsearch).delete("25", FileSearchDocument.class);
    }

    private static CanalEntry.Entry rowEntry(CanalEntry.EventType eventType, List<String> values)
            throws Exception {
        CanalEntry.RowData.Builder row = CanalEntry.RowData.newBuilder();
        for (int index = 0; index < values.size(); index += 2) {
            CanalEntry.Column column = CanalEntry.Column.newBuilder().setName(values.get(index))
                    .setValue(values.get(index + 1)).build();
            if (eventType == CanalEntry.EventType.DELETE) row.addBeforeColumns(column);
            else row.addAfterColumns(column);
        }
        CanalEntry.RowChange change = CanalEntry.RowChange.newBuilder().setEventType(eventType)
                .addRowDatas(row).build();
        return CanalEntry.Entry.newBuilder().setEntryType(CanalEntry.EntryType.ROWDATA)
                .setHeader(CanalEntry.Header.newBuilder().setTableName("user_file"))
                .setStoreValue(change.toByteString()).build();
    }

    private static List<String> columns(String... values) { return List.of(values); }
}
