package com.gamma.job;

/**
 * A Job Type's declaration of an output kind it may record as a Run Artifact (R7, §10) — catalog / UI
 * wiring metadata. The {@code ArtifactRecorder} that actually produces these is P1d, deferred to its
 * first artifact-producing Job Type.
 */
public record ArtifactDecl(String name, String kind) {   // kind: dataset | file | report

    public static ArtifactDecl dataset(String name) { return new ArtifactDecl(name, "dataset"); }
    public static ArtifactDecl file(String name)    { return new ArtifactDecl(name, "file"); }
    public static ArtifactDecl report(String name)  { return new ArtifactDecl(name, "report"); }
}
