package com.gamma.inspector;

import java.nio.file.Path;

/** Bridges the fixture helpers in com.gamma.etl.PipelineConfigBatchTest to inspector-package tests. */
final class PipelineConfigBatchTestRef {
    static Path writePipeline(Path dir, String batchSection) throws Exception {
        return com.gamma.etl.PipelineConfigBatchTest.writePipeline(dir, batchSection);
    }
}
