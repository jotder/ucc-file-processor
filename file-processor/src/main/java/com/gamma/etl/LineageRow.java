package com.gamma.etl;

/**
 * One cell of the many-to-many count matrix: how many transformed rows from
 * {@code inputFile} (tagged {@code srcId}) landed in {@code outputFile}.
 *
 * @param batchId    owning batch id
 * @param srcId      0-based member index within the batch
 * @param inputFile  member file name
 * @param outputFile absolute path of the output file the rows landed in
 * @param partition  partition path, e.g. {@code "year=2020/month=04/day=03"}
 * @param rowCount   number of rows from inputFile in this output file
 */
public record LineageRow(String batchId, int srcId, String inputFile,
                         String outputFile, String partition, long rowCount) {}
