package com.bitdata.sqlworkbench.session;

import java.time.Instant;

public record Session(
    String id,
    String name,
    String sqlText,
    String dialect,
    String connectionId,
    String fragmentTreeJson,
    String workspaceSnapshotJson,
    Instant createdAt,
    Instant updatedAt
) {
    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private String id;
        private String name = "";
        private String sqlText = "";
        private String dialect = "postgresql";
        private String connectionId;
        private String fragmentTreeJson;
        private String workspaceSnapshotJson;
        private Instant createdAt = Instant.now();
        private Instant updatedAt = Instant.now();

        public Builder id(String id) { this.id = id; return this; }
        public Builder name(String name) { this.name = name; return this; }
        public Builder sqlText(String sqlText) { this.sqlText = sqlText; return this; }
        public Builder dialect(String dialect) { this.dialect = dialect; return this; }
        public Builder connectionId(String connectionId) { this.connectionId = connectionId; return this; }
        public Builder fragmentTreeJson(String fragmentTreeJson) { this.fragmentTreeJson = fragmentTreeJson; return this; }
        public Builder workspaceSnapshotJson(String json) { this.workspaceSnapshotJson = json; return this; }
        public Builder createdAt(Instant createdAt) { this.createdAt = createdAt; return this; }
        public Builder updatedAt(Instant updatedAt) { this.updatedAt = updatedAt; return this; }

        public Session build() {
            return new Session(id, name, sqlText, dialect, connectionId, fragmentTreeJson, workspaceSnapshotJson, createdAt, updatedAt);
        }
    }
}
