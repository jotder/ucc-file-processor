package com.gamma.etl;

/**
 * One output file written for a single partition by {@link PartitionWriter}.
 *
 * @param partition  partition path, e.g. {@code "year=2020/month=04/day=03"}
 * @param outputFile absolute path of the revealed output file
 * @param bytes      size of the output file in bytes
 */
public record PartitionOutput(String partition, String outputFile, long bytes) {}
