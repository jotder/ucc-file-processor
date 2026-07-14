package com.gamma.acquire.connectors;

import com.gamma.acquire.ConnectionProfile;
import com.gamma.acquire.CollectorConnector;
import com.gamma.acquire.CollectorConnectorFactory;
import com.gamma.etl.PipelineConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;

/**
 * {@link CollectorConnectorFactory} for the {@code kafka} scheme (ACQ-5) — a Kafka topic consumed as a Source.
 * Discovered via {@code META-INF/services/com.gamma.acquire.CollectorConnectorFactory} when this module is on
 * the classpath.
 */
public final class KafkaConnectorFactory implements CollectorConnectorFactory {

    @Override
    public String scheme() {
        return "kafka";
    }

    @Override
    public CollectorConnector create(PipelineConfig cfg) {
        return create(cfg, null);
    }

    @Override
    public CollectorConnector create(PipelineConfig cfg, ConnectionProfile profile) {
        if (profile == null)
            throw new IllegalArgumentException("kafka source '" + cfg.collector().id()
                    + "' requires source.connection to reference a *_connection.toon profile (brokers/topic)");
        return new KafkaConnector(profile, KafkaConsumer::new);
    }
}
