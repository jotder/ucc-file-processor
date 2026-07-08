package com.gamma.acquire.connectors;

import com.gamma.acquire.ConnectionProfile;
import com.gamma.acquire.SourceConnector;
import com.gamma.acquire.SourceConnectorFactory;
import com.gamma.etl.PipelineConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;

/**
 * {@link SourceConnectorFactory} for the {@code kafka} scheme (ACQ-5) — a Kafka topic consumed as a Source.
 * Discovered via {@code META-INF/services/com.gamma.acquire.SourceConnectorFactory} when this module is on
 * the classpath.
 */
public final class KafkaConnectorFactory implements SourceConnectorFactory {

    @Override
    public String scheme() {
        return "kafka";
    }

    @Override
    public SourceConnector create(PipelineConfig cfg) {
        return create(cfg, null);
    }

    @Override
    public SourceConnector create(PipelineConfig cfg, ConnectionProfile profile) {
        if (profile == null)
            throw new IllegalArgumentException("kafka source '" + cfg.source().id()
                    + "' requires source.connection to reference a *_connection.toon profile (brokers/topic)");
        return new KafkaConnector(profile, KafkaConsumer::new);
    }
}
