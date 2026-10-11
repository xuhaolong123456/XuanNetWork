package com.networkdisk.file;

import com.alibaba.otter.canal.client.CanalConnector;
import com.alibaba.otter.canal.client.CanalConnectors;
import com.alibaba.otter.canal.protocol.Message;
import com.alibaba.otter.canal.protocol.CanalEntry;
import com.google.protobuf.InvalidProtocolBufferException;
import java.net.InetSocketAddress;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.search.canal.enabled", havingValue = "true")
public class CanalSearchIndexConsumer {
    private static final Logger log = LoggerFactory.getLogger(CanalSearchIndexConsumer.class);
    private static final int BATCH_SIZE = 500;
    private final ElasticsearchOperations elasticsearch;
    private final UserFileRepository files;
    private final String host;
    private final int port;
    private final String destination;
    private final String username;
    private final String password;
    private final String filter;
    private volatile CanalConnector connector;

    public CanalSearchIndexConsumer(ElasticsearchOperations elasticsearch, UserFileRepository files,
            @Value("${app.search.canal.host:localhost}") String host,
            @Value("${app.search.canal.port:11111}") int port,
            @Value("${app.search.canal.destination:example}") String destination,
            @Value("${app.search.canal.username:}") String username,
            @Value("${app.search.canal.password:}") String password,
            @Value("${app.search.canal.filter:network_disk\\.user_file}") String filter) {
        this.elasticsearch = elasticsearch;
        this.files = files;
        this.host = host;
        this.port = port;
        this.destination = destination;
        this.username = username;
        this.password = password;
        this.filter = filter;
    }

    @Scheduled(fixedDelayString = "${app.search.canal.poll-delay-ms:1000}", initialDelayString = "${app.search.canal.initial-delay-ms:5000}")
    public void consumeBatch() {
        CanalConnector active = null;
        long batchId = -1;
        try {
            active = connector();
            Message message = active.getWithoutAck(BATCH_SIZE);
            batchId = message.getId();
            if (batchId < 0 || message.getEntries().isEmpty()) return;
            processEntries(message.getEntries(), elasticsearch);
            // 整批写入 ES 成功后才确认，失败批次由 Canal 重放。
            active.ack(batchId);
        } catch (Exception exception) {
            if (active != null && batchId >= 0) {
                try { active.rollback(batchId); }
                catch (RuntimeException rollbackException) { exception.addSuppressed(rollbackException); }
            }
            log.warn("Canal 文件索引批次处理失败，已回滚等待重试", exception);
            if (active != null) disconnect(active);
        }
    }

    private CanalConnector connector() {
        CanalConnector active = connector;
        if (active != null) return active;
        synchronized (this) {
            if (connector == null) {
                var indexOperations = elasticsearch.indexOps(FileSearchDocument.class);
                boolean initializeIndex = !indexOperations.exists();
                CanalConnector created = CanalConnectors.newSingleConnector(
                        new InetSocketAddress(host, port), destination, username, password);
                try {
                    created.connect();
                    created.subscribe(filter);
                    created.rollback();
                    if (initializeIndex) {
                        indexOperations.createWithMapping();
                        initializeExistingDocuments(indexOperations);
                    }
                    connector = created;
                } catch (RuntimeException exception) {
                    try { created.disconnect(); }
                    catch (RuntimeException disconnectException) { exception.addSuppressed(disconnectException); }
                    throw exception;
                }
            }
            return connector;
        }
    }

    private void initializeExistingDocuments(org.springframework.data.elasticsearch.core.IndexOperations indexOperations) {
        int pageNumber = 0;
        Page<UserFile> page;
        do {
            page = files.findAllByDeletedFalse(PageRequest.of(pageNumber++, BATCH_SIZE));
            List<FileSearchDocument> documents = page.getContent().stream().map(FileSearchDocument::new).toList();
            if (!documents.isEmpty()) elasticsearch.save(documents);
        } while (page.hasNext());
        indexOperations.refresh();
    }

    private synchronized void disconnect(CanalConnector failed) {
        if (connector != failed) return;
        try { failed.disconnect(); }
        catch (RuntimeException exception) { log.debug("关闭 Canal 连接失败", exception); }
        connector = null;
    }

    static void processEntries(List<CanalEntry.Entry> entries, ElasticsearchOperations elasticsearch)
            throws InvalidProtocolBufferException {
        for (CanalEntry.Entry entry : entries) {
            if (entry.getEntryType() != CanalEntry.EntryType.ROWDATA
                    || !"user_file".equals(entry.getHeader().getTableName())) continue;
            CanalEntry.RowChange change = CanalEntry.RowChange.parseFrom(entry.getStoreValue());
            for (CanalEntry.RowData row : change.getRowDatasList()) {
                if (change.getEventType() == CanalEntry.EventType.DELETE) {
                    String id = columnValue(row.getBeforeColumnsList(), "id");
                    if (id != null) elasticsearch.delete(id, FileSearchDocument.class);
                } else if (change.getEventType() == CanalEntry.EventType.INSERT
                        || change.getEventType() == CanalEntry.EventType.UPDATE) {
                    Map<String, String> fields = columnMap(row.getAfterColumnsList());
                    String id = fields.get("id");
                    if (id == null) throw new IllegalArgumentException("user_file 变更缺少主键");
                    if (isTrue(fields.get("is_delete"))) {
                        // 软删除由数据库 UPDATE 表示，但回收站内容不保留在活动搜索索引中。
                        elasticsearch.delete(id, FileSearchDocument.class);
                        continue;
                    }
                    elasticsearch.save(toDocument(fields));
                }
            }
        }
    }

    private static FileSearchDocument toDocument(Map<String, String> fields) {
        return new FileSearchDocument(Long.parseLong(fields.get("id")),
                Long.parseLong(required(fields, "user_id")), nullableLong(fields.get("parent_id")),
                required(fields, "name"), FileNodeType.fromStoredValue(required(fields, "node_type")).name(),
                Long.parseLong(fields.getOrDefault("size_bytes", "0")), fields.get("mime_type"),
                LocalDateTime.parse(required(fields, "updated_at").replace(' ', 'T')),
                isTrue(fields.get("download_allowed")), isTrue(fields.get("preview_allowed")));
    }

    private static String required(Map<String, String> fields, String name) {
        String value = fields.get(name);
        if (value == null) throw new IllegalArgumentException("user_file 变更缺少字段: " + name);
        return value;
    }

    private static Long nullableLong(String value) {
        return value == null || value.isBlank() ? null : Long.valueOf(value);
    }

    private static boolean isTrue(String value) {
        return "1".equals(value) || "true".equalsIgnoreCase(value);
    }

    private static String columnValue(List<CanalEntry.Column> columns, String name) {
        return columns.stream().filter(column -> name.equals(column.getName()))
                .map(CanalEntry.Column::getValue).findFirst().orElse(null);
    }

    private static Map<String, String> columnMap(List<CanalEntry.Column> columns) {
        Map<String, String> result = new HashMap<>();
        for (CanalEntry.Column column : columns) result.put(column.getName(), column.getValue());
        return result;
    }
}
