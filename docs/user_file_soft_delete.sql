-- 仅用于尚未由 JPA 更新的旧表；执行前检查字段与索引是否存在。
ALTER TABLE user_file
    ADD COLUMN is_delete BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN delete_at DATETIME(6) NULL,
    ADD COLUMN delete_batch VARCHAR(36) NULL;
CREATE INDEX idx_user_file_owner_deleted ON user_file (user_id, is_delete, delete_at, id);
