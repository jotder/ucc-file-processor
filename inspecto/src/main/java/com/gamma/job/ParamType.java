package com.gamma.job;

/**
 * The declared type of a Job {@link ParameterDecl} (job-framework §7.1). Drives typed resolution
 * (the P1b resolver, deferred to its first consumer) and UI form widgets.
 */
public enum ParamType {
    STRING, INTEGER, DECIMAL, BOOLEAN, DATE, INSTANT, DATASET_REF
}
