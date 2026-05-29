package com.gamma.service;

import com.gamma.etl.CommitLog;
import com.gamma.etl.PipelineConfig;

import java.util.Set;

/**
 * File-backed {@link StatusStore} — reads the durable commit log
 * ({@code cfg.dirs().commitLogPath()}) via {@link CommitLog}. The default
 * implementation for the 2.x service line; M5 adds a database-backed alternative.
 */
public final class FileStatusStore implements StatusStore {

    @Override
    public Set<String> committedBatches(PipelineConfig cfg) {
        String path = cfg.dirs().commitLogPath();
        if (path == null || path.isBlank()) return Set.of();
        return new CommitLog(path).committedBatchIds();
    }
}
