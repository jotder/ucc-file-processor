package com.gamma.config.spec;

/**
 * The declared type of a {@link FieldSpec}.
 *
 * <p>These are the value shapes a config field can hold, used to drive type-checking in
 * {@code ConfigLoader.validate}, form-control selection in a UI, and grammar-constrained
 * generation by an LLM. {@code FILEPATH}, {@code CRON} and {@code SQL} are {@code STRING}
 * refinements that carry extra UI/validation intent without changing the wire representation.
 */
public enum FieldType {
    STRING,
    INT,
    LONG,
    BOOL,
    ENUM,
    FILEPATH,
    CRON,
    SQL,
    MAP,
    LIST
}
