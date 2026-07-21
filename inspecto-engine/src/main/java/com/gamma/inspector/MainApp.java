package com.gamma.inspector;

import com.gamma.util.*;
import dev.toonformat.jtoon.JToon;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Main entry point for the URA pre-ETL and pipeline file-management tools.
 *
 * <p>All pre-ETL commands read their configuration directly from the pipeline
 * {@code .toon} file, which is passed as the first positional argument. Each
 * command has a dedicated section in the toon (see below).
 *
 * <p>Usage:
 * <pre>
 *   java -cp file-processor.jar com.gamma.inspector.MainApp [--dry-run] &lt;command&gt; &lt;pipeline.toon&gt; [args...]
 * </pre>
 *
 * <p>Commands and their toon sections:
 * <pre>
 *   search   &lt;pipeline.toon&gt;   → reads: search.*,  dirs.poll
 *   copy     &lt;pipeline.toon&gt;   → reads: search.*,  dirs.poll
 *   copy-tars &lt;pipeline.toon&gt;  → reads: copy_tars.base_dirs, dirs.poll
 *   extract  &lt;pipeline.toon&gt;   → reads: dirs.poll, dirs.temp, dirs.backup
 *   backup   &lt;pipeline.toon&gt;   → reads: backup.*, dirs.backup
 *   prepare-inbox &lt;pipeline.toon&gt; → reads: dirs.poll, dirs.temp, dirs.backup
 *   create-schema &lt;source&gt; &lt;sample.csv&gt; &lt;gen_config.toon&gt;
 * </pre>
 */
public class MainApp {

    public static void main(String[] args) {
        if (args.length == 0) {
            printUsage();
            System.exit(1);
        }

        // ── parse flags ───────────────────────────────────────────────────────
        boolean dryRun  = false;
        String  command = null;
        List<String> subArgsList = new ArrayList<>();

        for (String a : args) {
            if (a.equalsIgnoreCase("--dry-run")) {
                dryRun = true;
            } else if (command == null) {
                command = a.toLowerCase();
            } else {
                subArgsList.add(a);
            }
        }

        if (command == null) {
            printUsage();
            System.exit(1);
        }

        String[] subArgs = subArgsList.toArray(new String[0]);

        // ── dispatch ──────────────────────────────────────────────────────────
        try {
            switch (command) {

                // ── search: find files from manifest in base_dirs, log only ───

                case "search": {
                    Map<String, Object> toon = loadToon(subArgs, command);
                    new FileOrganizer(toon, dryRun, /*searchOnly=*/true).runSearch();
                    break;
                }

                // ── copy: find files from manifest in base_dirs, copy to poll dir ──

                case "copy":
                case "organize": {
                    Map<String, Object> toon = loadToon(subArgs, command);
                    new FileOrganizer(toon, dryRun, /*searchOnly=*/false).run();
                    break;
                }

                // ── copy-tars: find *.tar.gz in base_dirs, copy flat to poll dir ──

                case "copy-tars": {
                    Map<String, Object> toon = loadToon(subArgs, command);
                    new TarArranger(toon, dryRun).copyTars();
                    break;
                }

                // ── extract: unpack *.tar.gz in poll dir, arrange CSVs by date ──

                case "extract": {
                    Map<String, Object> toon = loadToon(subArgs, command);
                    new TarArranger(toon, dryRun).extract();
                    break;
                }

                // ── backup: move originals listed in available_files.csv ───────

                case "backup": {
                    Map<String, Object> toon = loadToon(subArgs, command);
                    new FileBackup(toon, dryRun).run();
                    break;
                }

                // ── prepare-inbox: toon-driven tar → CSV inbox prep ────────────

                case "prepare-inbox": {
                    if (subArgs.length < 1) {
                        System.err.println("Usage: prepare-inbox <pipeline.toon>");
                        System.exit(1);
                    }
                    new TarInboxPreparer(subArgs[0], dryRun).run();
                    break;
                }

                // ── reprocess: delete-and-reprocess a whole batch by id ────────

                case "reprocess": {
                    if (subArgs.length < 2) {
                        System.err.println("Usage: reprocess <pipeline.toon> <batch_id>");
                        System.exit(1);
                    }
                    ReprocessCommand.run(subArgs[0], subArgs[1]);
                    break;
                }

                // ── create-schema: generate schema + pipeline toon from sample CSV

                case "create-schema": {
                    if (subArgs.length < 3) {
                        System.err.println("Usage: create-schema <source_name> <sample_csv> <gen_config.toon>");
                        System.exit(1);
                    }
                    SchemaExtractor.run(subArgs[0], subArgs[1], subArgs[2]);
                    break;
                }

                // ── legacy lower-level commands ───────────────────────────────

                case "move-by-date":
                    FileMoverByDate.main(args);
                    break;

                case "extract-unknown": {
                    String baseExt = subArgs.length > 0 ? subArgs[0] : ".";
                    String tempExt = subArgs.length > 1 ? subArgs[1] : "./temp";
                    new TarExtractor(baseExt, tempExt, dryRun).run();
                    break;
                }

                case "extract-move": {
                    if (subArgs.length < 3) {
                        System.err.println("Usage: extract-move <walk_root> <temp_dir> <target_base_dir>");
                        System.exit(1);
                    }
                    new IntegratedProcessor(subArgs[0], subArgs[1], subArgs[2], dryRun).run();
                    break;
                }

                case "help":
                    printUsage();
                    break;

                default:
                    printUsage();
                    System.err.println("\nUnknown command: " + command);
                    System.exit(1);
            }

        } catch (Exception e) {
            System.err.println("Fatal error executing '" + command + "': " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    // ── toon loading ──────────────────────────────────────────────────────────

    /**
     * Expects {@code subArgs[0]} to be a path to a pipeline {@code .toon} file.
     * Parses it and returns the top-level map.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadToon(String[] subArgs, String command)
            throws IOException {
        if (subArgs.length < 1) {
            System.err.println("Usage: " + command + " <pipeline.toon>");
            System.exit(1);
        }
        String path = subArgs[0];
        if (!Files.exists(Paths.get(path)))
            throw new IOException("Pipeline toon not found: " + path);
        return (Map<String, Object>) JToon.decode(
                Files.readString(Paths.get(path), StandardCharsets.UTF_8));
    }

    // ── usage ─────────────────────────────────────────────────────────────────

    private static void printUsage() {
        System.out.println("URA File Management Suite — Java 24 (Virtual Threads)");
        System.out.println();
        System.out.println("Usage:");
        System.out.println("  ura [--dry-run] <command> <pipeline.toon> [args...]");
        System.out.println();
        System.out.println("  (ura = ura.sh on Linux/Mac, ura.bat on Windows — shipped alongside file-processor.jar)");
        System.out.println("  (dev: ./ura.sh or ura.bat from the inspecto/ source directory)");
        System.out.println("  (raw: java --enable-native-access=ALL-UNNAMED -cp file-processor.jar com.gamma.inspector.MainApp ...)");
        System.out.println();
        System.out.println("Pre-ETL commands (all read from pipeline.toon sections):");
        System.out.println("  search    <pipeline.toon>   Scan base_dirs for manifest files — log only, no copy.");
        System.out.println("                              Toon section: search.*");
        System.out.println("  copy      <pipeline.toon>   Scan base_dirs, copy manifest files to poll dir by date.");
        System.out.println("                              Toon section: search.*, dirs.poll");
        System.out.println("  copy-tars <pipeline.toon>   Find *.tar.gz in base_dirs, copy flat to poll dir.");
        System.out.println("                              Toon section: copy_tars.base_dirs, dirs.poll");
        System.out.println("  extract   <pipeline.toon>   Extract *.tar.gz in poll dir; arrange CSVs by date;");
        System.out.println("                              backup archives; clean temp.");
        System.out.println("                              Toon section: dirs.poll, dirs.temp, dirs.backup");
        System.out.println("  backup    <pipeline.toon>   Move originals (from available_files.csv) to backup dir.");
        System.out.println("                              Toon section: backup.*, dirs.backup");
        System.out.println("  prepare-inbox <pipeline.toon>");
        System.out.println("                              Extract .tar.gz from poll dir, arrange CSVs by date,");
        System.out.println("                              backup archives (toon-native, single-step).");
        System.out.println("                              Toon section: dirs.poll, dirs.temp, dirs.backup");
        System.out.println("  reprocess <pipeline.toon> <batch_id>");
        System.out.println("                              Delete a batch's outputs + markers, restore its");
        System.out.println("                              member files from backup, and reprocess the set.");
        System.out.println();
        System.out.println("Schema generation:");
        System.out.println("  create-schema <source_name> <sample_csv> <gen_config.toon>");
        System.out.println("                              Infer <source>_schema.toon + <source>_pipeline.toon");
        System.out.println("                              from a representative sample CSV.");
        System.out.println();
        System.out.println("ETL pipeline (runs the CollectorProcessor on a pipeline config, not via ura):");
        System.out.println("  run-adjustment.sh | run-adjustment.bat   Polls inbox, processes adjustment CSVs to Parquet.");
        System.out.println("  run-voucher.sh    | run-voucher.bat      Polls inbox, processes voucher CSVs to Parquet.");
        System.out.println("  java -jar file-processor-<version>.jar <pipeline.toon>   (direct form)");
        System.out.println();
        System.out.println("Legacy / low-level commands:");
        System.out.println("  move-by-date                    Move files matching pattern into YYYYMMDD sub-folders.");
        System.out.println("  extract-unknown <base> <temp>   Extract tars found in 'obscure' directories.");
        System.out.println("  extract-move <walk> <temp> <target>  Integrated: find → extract → move.");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --dry-run   Simulate all actions; print what would happen without touching files.");
        System.out.println("  help        Print this message.");
    }
}
