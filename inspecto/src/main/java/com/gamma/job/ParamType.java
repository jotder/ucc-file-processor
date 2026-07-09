package com.gamma.job;

/**
 * The declared type of a Job {@link ParameterDecl} (job-framework §7.1). Drives resolution
 * ({@link ParameterResolver}, P3a) and UI form widgets.
 */
public enum ParamType {
    STRING, INTEGER, DECIMAL, BOOLEAN, DATE, INSTANT, DATASET_REF
}
