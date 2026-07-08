package com.gamma.intelligence.pack;

import com.eoiagent.app.KnowledgeSource;
import com.eoiagent.app.SourceKind;
import com.eoiagent.knowledge.DocumentSource;
import com.eoiagent.knowledge.IngestOptions;

import java.util.List;
import java.util.Map;

/**
 * P0's RAG corpus: exactly one document, {@code docs/GLOSSARY.md} — small, stable, and already
 * proven readable offline by {@link GlossaryLoader}. {@code onnxruntime} +
 * {@code langchain4j-embeddings-all-minilm-l6-v2} (the in-JVM embedding model {@code
 * PlatformBuilder} wires from {@link InspectoModelProfile#embedding()}) are both cached in the
 * offline {@code .m2} and bundle their model weights inside the jar — no network at ingest time.
 * A wider corpus (OKF bundle, examples) is a later phase once this one is proven in CI.
 */
final class InspectoKnowledgeSources {

    private InspectoKnowledgeSources() {
    }

    static List<KnowledgeSource> sources() {
        return List.of(new GlossarySource());
    }

    private static final class GlossarySource implements KnowledgeSource {

        @Override
        public String id() {
            return "inspecto-glossary";
        }

        @Override
        public SourceKind kind() {
            return SourceKind.PRODUCT_DOC;
        }

        @Override
        public IngestOptions options() {
            return IngestOptions.defaults();
        }

        @Override
        public List<DocumentSource> resolve() {
            return List.of(new DocumentSource(GlossaryLoader.glossaryPath().toString(), "text/markdown",
                    Map.of("title", "Inspecto Canonical Vocabulary")));
        }
    }
}
