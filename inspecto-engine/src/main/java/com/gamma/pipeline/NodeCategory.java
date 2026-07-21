package com.gamma.pipeline;

import com.gamma.api.PublicApi;

/**
 * The coarse <b>family</b> a {@link PipelineNodeType} belongs to. Two uses: it groups node types in the UI
 * palette (doc §6), and it lets the engine reason about a node's <em>role</em> without string-matching
 * the {@code type} discriminator — e.g. {@link PipelineStores} treats every {@link #SINK} node as a store
 * producer, so the three sink subtypes ({@code sink.persistent}/{@code sink.materialized}/
 * {@code sink.view}) and any plugin-contributed sink are recognised uniformly.
 *
 * <p>The category is a property of the node <em>type</em> (the processor's family), orthogonal to the
 * pipeline topology — which sink kind a node is, or whether a source is a file collector or a stream
 * adapter, is a node-level concern, not a pipeline one (doc §3.1).
 */
@PublicApi(since = "4.3.0")
public enum NodeCategory {

    /** Entry / <b>collector</b> processors that land a batch — {@code acquisition} (files), {@code adapter} (stream→file). */
    SOURCE,

    /** Record readers that tokenise a landed file into rows — {@code parser}. */
    PARSE,

    /** Record operators over a batch <em>in motion</em> — the {@code transform.*} family + {@code enrichment}. */
    TRANSFORM,

    /** Put processors where data may rest, materialise, or be exposed as a view — the {@code sink.*} family. */
    SINK,

    /** Reporting / control side-tasks with no downstream {@code data} edge — {@code gap} / {@code alert} / {@code event}. */
    CONTROL
}
