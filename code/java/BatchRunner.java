// BatchRunner.java
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public class BatchRunner {

    // Default (can be overridden by config)
    private static final long DEFAULT_PROCESS_TIME_OUT_MINUTES = 1000000;

    /**
     * Usage:
     *   java BatchRunner <inputFolder> <outputFolder> <mode:SAT|LP>
     *                    [numModels] [numInitialModels] [print] [extraAnalysis]
     *                    [proof] [ch] [metricsOnly]
     *                    [configFile]
     *
     * New params (only used for mode=LP):
     *   proof : dag | hypergraph | hypertree
     *           (maps to DatalogLP.Characterization.PROOF_DAG or HYPERGRAPH)
     *   ch    : standard | unambiguous (also accepts "unambigous")
     *           (maps to DatalogLP.ProofTree.STANDARD or UNAMBIGUOUS)
     *
     * Defaults if omitted:
     *   proof = dag
     *   ch    = unambiguous
     *
     * Notes:
     * - If configFile is omitted, defaults to: config/runner.properties
     * - Per-dataset override:
     *     datasetName = last path element of <inputFolder>
     *     looks for sibling file: config/runner-<datasetName>.properties
     *   Example:
     *     inputFolder = .../experiments/downward/test
     *     override    = config/runner-test.properties
     */
    public static void main(String[] args) {
        if (args.length < 3) {
            System.err.println(
                    "Usage: java BatchRunner <inputFolder> <outputFolder> <mode:SAT|LP> " +
                            "[numModels] [numInitialModels] [print] [extraAnalysis] [proof] [ch] [metricsOnly] [configFile]"
            );
            System.exit(1);
        }

        final Path inputFolder  = Paths.get(args[0]).toAbsolutePath().normalize();
        final Path outputFolder = Paths.get(args[1]).toAbsolutePath().normalize();
        final String modeRaw    = args[2];

        final Mode mode = parseMode(modeRaw);

        // ---- Detect optional configFile (last arg if .properties or exists) ----
        String configFileArg = null;
        if (args.length >= 4) {
            String last = args[args.length - 1];
            if (last != null && !last.isBlank()) {
                java.io.File f = new java.io.File(last);
                if (last.toLowerCase(Locale.ROOT).endsWith(".properties") || f.exists()) {
                    configFileArg = last;
                }
            }
        }
        if (configFileArg == null) configFileArg = "config/runner.properties";

        // ---- Config loading (base + optional dataset override) ----
        final String datasetName = inputFolder.getFileName().toString();
        final Properties cfg = loadConfigWithDatasetOverride(configFileArg, datasetName);

        // ---- Read defaults from config ----
        int cfgNumModels = getInt(cfg, "defaults.numModels", -1);
        int cfgNumInitialModels = getInt(cfg, "defaults.numInitialModels", 2);
        boolean cfgPrint = getBool(cfg, "defaults.printModels", false);
        boolean cfgExtra = getBool(cfg, "defaults.extraAnalysis", false);
        boolean cfgMetricsOnly = getBool(cfg, "defaults.metricsOnly", false);

        // New LP-only defaults (still parsed here so BatchRunner can forward)
        String cfgProof = getString(cfg, "defaults.proof", "dag");
        String cfgCh    = getString(cfg, "defaults.ch", "unambiguous");

        long processTimeoutMinutes =
                getLong(cfg, "batch.processTimeoutMinutes", DEFAULT_PROCESS_TIME_OUT_MINUTES);

        // ---- CLI overrides (if provided) override config ----
        // We parse positional args[3..] BUT exclude the configFile if present.
        final int cliEndExclusive = args.length - (isConfigArg(args[args.length - 1]) ? 1 : 0);

        final int numModels        = (cliEndExclusive >= 4 ? parseIntArg(args[3], cfgNumModels) : cfgNumModels);
        final int numInitialModels = (cliEndExclusive >= 5 ? parseIntArg(args[4], cfgNumInitialModels) : cfgNumInitialModels);
        final boolean printModels  = (cliEndExclusive >= 6 ? parseBoolArg(args[5], cfgPrint) : cfgPrint);
        final boolean extraAnalysis= (cliEndExclusive >= 7 ? parseBoolArg(args[6], cfgExtra) : cfgExtra);

        // New LP-only CLI params
        final String proofParam = (cliEndExclusive >= 8 ? safeLower(args[7], cfgProof) : cfgProof);
        final String chParam    = (cliEndExclusive >= 9 ? safeLower(args[8], cfgCh) : cfgCh);
        final boolean metricsOnly = (cliEndExclusive >= 10 ? parseBoolArg(args[9], cfgMetricsOnly) : cfgMetricsOnly);

        if (!Files.isDirectory(inputFolder)) {
            System.err.println("Not a directory (inputFolder): " + inputFolder);
            System.exit(2);
        }

        try {
            Files.createDirectories(outputFolder);
        } catch (Exception e) {
            System.err.println("Could not create outputFolder: " + outputFolder + " (" + e.getMessage() + ")");
            System.exit(2);
        }
        if (!Files.isDirectory(outputFolder)) {
            System.err.println("Not a directory (outputFolder): " + outputFolder);
            System.exit(2);
        }

        final String enumeratorClass =
                (mode == Mode.SAT) ? "org.datalog.SATModelEnumerator" : "LPModelEnumerator";

        System.out.println("[BatchRunner] inputFolder   : " + inputFolder);
        System.out.println("[BatchRunner] outputFolder  : " + outputFolder);
        System.out.println("[BatchRunner] datasetName   : " + datasetName);
        System.out.println("[BatchRunner] mode          : " + mode + " (" + enumeratorClass + ")");
        System.out.println("[BatchRunner] config(base)  : " + configFileArg);
        System.out.println("[BatchRunner] config(eff)   : " + cfg.getProperty("__effective_config_file__", configFileArg));
        System.out.println("[BatchRunner] numModels=" + numModels +
                ", numInitialModels=" + numInitialModels +
                ", print=" + printModels +
                ", extraAnalysis=" + extraAnalysis +
                ", metricsOnly=" + metricsOnly);

        if (mode == Mode.LP) {
            System.out.println("[BatchRunner] proof=" + proofParam + ", ch=" + chParam);
        }

        System.out.println("[BatchRunner] timeout(min)  : " + processTimeoutMinutes);

        Set<Object> seenKeys = new HashSet<>();
        Set<String> seenRealPaths = new HashSet<>();

        try (java.util.stream.Stream<Path> walk = Files.walk(inputFolder)) {
            int processed = 0, skipped = 0;

            Iterator<Path> it = walk.iterator();
            while (it.hasNext()) {
                Path lpFile = it.next();

                String name = lpFile.getFileName().toString();
                if (!name.toLowerCase(Locale.ROOT).endsWith(".lp")) continue;

                try {
                    if (!Files.isRegularFile(lpFile, LinkOption.NOFOLLOW_LINKS)) {
                        skipped++;
                        continue;
                    }

                    java.nio.file.attribute.BasicFileAttributes attrs =
                            Files.readAttributes(lpFile,
                                    java.nio.file.attribute.BasicFileAttributes.class,
                                    LinkOption.NOFOLLOW_LINKS);
                    Object key = attrs.fileKey();
                    Path real = lpFile.toRealPath(LinkOption.NOFOLLOW_LINKS);

                    boolean isDup = (key != null && !seenKeys.add(key))
                            || (key == null && !seenRealPaths.add(real.toString()));
                    if (isDup) {
                        System.out.println("[INFO] Skipping duplicate alias: " + lpFile);
                        skipped++;
                        continue;
                    }

                    Path rel = inputFolder.relativize(real);
                    Path relParent = (rel.getParent() == null ? Paths.get("") : rel.getParent());
                    Path outDirForThisFile = outputFolder.resolve(relParent).normalize();

                    Files.createDirectories(outDirForThisFile);

                    Path expectedLpLogPath = null;
                    if (mode == Mode.LP) {
                        String logName = buildLpLogName(real, numInitialModels, numModels, proofParam, chParam, metricsOnly);
                        expectedLpLogPath = outDirForThisFile.resolve(logName);
                    }

                    // Forward args to Enumerator:
                    //   <downwardFile> <outDir> <numModels> <numInitialModels> <print> <extraAnalysis>
                    //   [proof] [ch]    (LP only)
                    //   <configFile>
                    List<String> forwarded = new ArrayList<>();
                    forwarded.add(real.toString());
                    forwarded.add(outDirForThisFile.toString());
                    forwarded.add(String.valueOf(numModels));
                    forwarded.add(String.valueOf(numInitialModels));
                    forwarded.add(String.valueOf(printModels));
                    forwarded.add(String.valueOf(extraAnalysis));

                    if (mode == Mode.LP) {
                        forwarded.add(proofParam);
                        forwarded.add(chParam);
                        forwarded.add(String.valueOf(metricsOnly));
                    }

                    forwarded.add(cfg.getProperty("__effective_config_file__", configFileArg));

                    try {
                        List<String> cmd = new ArrayList<>();
                        String javaBin = System.getProperty("java.home")
                                + java.io.File.separator + "bin" + java.io.File.separator
                                + (System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "java.exe" : "java");
                        cmd.add(javaBin);
                        cmd.add("-cp");
                        cmd.add(System.getProperty("java.class.path"));
                        cmd.add(enumeratorClass);
                        cmd.addAll(forwarded);

                        System.out.println("[CMD] " + String.join(" ", cmd));
                        System.out.println("[OUT] " + outDirForThisFile);

                        long procStartNs = System.nanoTime();

                        Process p = new ProcessBuilder(cmd)
                                .inheritIO()
                                .start();

                        boolean finished = p.waitFor(processTimeoutMinutes, java.util.concurrent.TimeUnit.MINUTES);

                        int exitCode;
                        if (!finished) {
                            long childPid = -1L;
                            try { childPid = p.pid(); } catch (Throwable ignored) {}
                            Long rssKb = readLinuxProcessStatusKb(childPid, "VmRSS:");
                            Long hwmKb = readLinuxProcessStatusKb(childPid, "VmHWM:");
                            try {
                                java.lang.ProcessHandle ph = p.toHandle();
                                ph.descendants().forEach(h -> {
                                    try { h.destroyForcibly(); } catch (Throwable ignored) {}
                                });
                            } catch (Throwable ignored) {}
                            p.destroyForcibly();
                            p.waitFor();
                            exitCode = 124;
                            long elapsedMs = (System.nanoTime() - procStartNs) / 1_000_000L;
                            System.err.println("[TIMEOUT] " + enumeratorClass + " exceeded " + processTimeoutMinutes
                                    + " min for file " + real.getFileName() + " (elapsed=" + elapsedMs + " ms)");
                                if (rssKb != null || hwmKb != null) {
                                System.err.println("[TIMEOUT] child_pid=" + childPid
                                    + ", rss_mb=" + (rssKb == null ? "?" : (rssKb / 1024L))
                                    + ", hwm_mb=" + (hwmKb == null ? "?" : (hwmKb / 1024L)));
                                }
                            appendRunnerDiagnostic(expectedLpLogPath, Arrays.asList(
                                    "----------------------------------------------------------------",
                                    "[RUNNER] status=TIMEOUT",
                                    "[RUNNER] file=" + real,
                                    "[RUNNER] timeout_minutes=" + processTimeoutMinutes,
                                    "[RUNNER] elapsed_ms=" + elapsedMs,
                                    "[RUNNER] child_pid=" + childPid,
                                    "[RUNNER] rss_mb=" + (rssKb == null ? "" : String.valueOf(rssKb / 1024L)),
                                    "[RUNNER] hwm_mb=" + (hwmKb == null ? "" : String.valueOf(hwmKb / 1024L)),
                                    "[RUNNER] note=Child process was force-killed by BatchRunner; if program creation did not finish, timing is unavailable.",
                                    "================================================================"
                            ));
                        } else {
                            exitCode = p.exitValue();
                        }

                        if (exitCode != 0) {
                            if (exitCode != 124) {
                                String hint = (exitCode == 137 || exitCode == 9)
                                        ? "likely killed externally/OOM"
                                        : (exitCode == 134 ? "abort (native assert/terminate)" : "non-zero exit");
                                appendRunnerDiagnostic(expectedLpLogPath, Arrays.asList(
                                        "----------------------------------------------------------------",
                                        "[RUNNER] status=NONZERO_EXIT",
                                        "[RUNNER] file=" + real,
                                        "[RUNNER] exit_code=" + exitCode,
                                        "[RUNNER] hint=" + hint,
                                        "================================================================"
                                ));
                            }
                            throw new RuntimeException(enumeratorClass + " exited with code " + exitCode);
                        }

                        processed++;
                    } catch (Throwable t) {
                        System.err.println("[ERROR] Failure running " + enumeratorClass + " for " + real.getFileName() + ": " + t.getMessage());
                        t.printStackTrace(System.err);
                    }
                } catch (java.io.IOException ioe) {
                    System.err.println("[WARN] Skipping unreadable entry: " + lpFile + " (" + ioe.getMessage() + ")");
                    skipped++;
                }
            }

            System.out.println("[BatchRunner] Done. Processed=" + processed + ", Skipped=" + skipped);
        } catch (java.io.IOException e) {
            System.err.println("[ERROR] Failed to walk folder: " + e.getMessage());
            System.exit(3);
        }
    }

    // ----- Mode + Helpers -----

    private enum Mode { SAT, LP }

    private static Mode parseMode(String s) {
        if (s == null) {
            System.err.println("Mode must be SAT or LP.");
            System.exit(1);
        }
        String v = s.trim().toUpperCase(Locale.ROOT);
        if (v.equals("SAT")) return Mode.SAT;
        if (v.equals("LP")) return Mode.LP;

        System.err.println("Invalid mode: " + s + " (expected SAT or LP)");
        System.exit(1);
        return Mode.SAT;
    }

    private static int parseIntArg(String s, int defVal) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return defVal; }
    }

    private static boolean parseBoolArg(String s, boolean defVal) {
        if (s == null) return defVal;
        String v = s.trim().toLowerCase(Locale.ROOT);
        if (v.equals("true") || v.equals("1") || v.equals("yes") || v.equals("y")) return true;
        if (v.equals("false") || v.equals("0") || v.equals("no") || v.equals("n")) return false;
        return defVal;
    }

    private static boolean isConfigArg(String s) {
        if (s == null || s.isBlank()) return false;
        java.io.File f = new java.io.File(s);
        return s.toLowerCase(Locale.ROOT).endsWith(".properties") || f.exists();
    }

    private static String safeLower(String s, String defVal) {
        if (s == null || s.isBlank()) return defVal;
        return s.trim().toLowerCase(Locale.ROOT);
    }

    private static String baseNameNoExt(Path filePath) {
        String n = filePath.getFileName().toString();
        int dot = n.lastIndexOf('.');
        return (dot >= 0) ? n.substring(0, dot) : n;
    }

    private static String canonicalProofLabel(String proofParam) {
        String p = safeLower(proofParam, "dag");
        if (p.equals("hypergraph") || p.equals("hypertree") || p.equals("hyper") || p.equals("hg")) {
            return "hypergraph";
        }
        return "dag";
    }

    private static String canonicalChLabel(String chParam) {
        String c = safeLower(chParam, "unambiguous");
        if (c.equals("standard") || c.equals("std")) return "standard";
        return "unambiguous";
    }

    private static String buildLpLogName(Path lpFile, int numInitialModels, int numModels, String proofParam, String chParam, boolean metricsOnly) {
        return String.format(
                "%s-initial-%d-total-%d-proof-%s-ch-%s%s.lp.log",
                baseNameNoExt(lpFile),
                numInitialModels,
                numModels,
                canonicalProofLabel(proofParam),
                canonicalChLabel(chParam),
                metricsOnly ? "-metrics-only" : ""
        );
    }

    private static void appendRunnerDiagnostic(Path logPath, List<String> lines) {
        if (logPath == null || lines == null || lines.isEmpty()) return;
        try {
            Path parent = logPath.getParent();
            if (parent != null) Files.createDirectories(parent);
            try (java.io.BufferedWriter bw = Files.newBufferedWriter(
                    logPath,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            )) {
                for (String line : lines) {
                    bw.write(line == null ? "" : line);
                    bw.newLine();
                }
            }
        } catch (Throwable ignored) {
            // best-effort diagnostics only
        }
    }

    private static Long readLinuxProcessStatusKb(long pid, String keyPrefix) {
        if (pid <= 0L) return null;
        Path status = Paths.get("/proc", String.valueOf(pid), "status");
        if (!Files.isRegularFile(status)) return null;
        try {
            for (String line : Files.readAllLines(status, StandardCharsets.UTF_8)) {
                if (line.startsWith(keyPrefix)) {
                    String[] parts = line.trim().split("\\s+");
                    if (parts.length >= 2) {
                        return Long.parseLong(parts[1]);
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static Properties loadProps(String path) {
        Properties p = new Properties();
        try (FileInputStream fis = new FileInputStream(path)) {
            p.load(fis);
        } catch (Exception e) {
            // silent: caller decides if missing is ok
        }
        return p;
    }

    private static Properties loadConfigWithDatasetOverride(String baseConfigPath, String datasetName) {
        Properties base = loadProps(baseConfigPath);

        // Derive: <dir>/runner-<dataset>.properties
        Path basePath = Paths.get(baseConfigPath).toAbsolutePath().normalize();
        Path dir = (basePath.getParent() == null ? Paths.get(".") : basePath.getParent());
        String datasetOverrideName = "runner-" + datasetName + ".properties";
        Path overridePath = dir.resolve(datasetOverrideName);

        if (Files.isRegularFile(overridePath)) {
            Properties override = loadProps(overridePath.toString());
            // apply override on top of base
            for (String k : override.stringPropertyNames()) {
                base.setProperty(k, override.getProperty(k));
            }
            base.setProperty("__effective_config_file__", overridePath.toString());
        } else {
            base.setProperty("__effective_config_file__", baseConfigPath);
        }

        return base;
    }

    private static int getInt(Properties p, String key, int defVal) {
        try {
            String v = p.getProperty(key);
            if (v == null) return defVal;
            return Integer.parseInt(v.trim());
        } catch (Exception e) {
            return defVal;
        }
    }

    private static long getLong(Properties p, String key, long defVal) {
        try {
            String v = p.getProperty(key);
            if (v == null) return defVal;
            return Long.parseLong(v.trim());
        } catch (Exception e) {
            return defVal;
        }
    }

    private static boolean getBool(Properties p, String key, boolean defVal) {
        try {
            String v = p.getProperty(key);
            if (v == null) return defVal;
            String s = v.trim().toLowerCase(Locale.ROOT);
            if (s.equals("true") || s.equals("1") || s.equals("yes") || s.equals("y")) return true;
            if (s.equals("false") || s.equals("0") || s.equals("no") || s.equals("n")) return false;
            return defVal;
        } catch (Exception e) {
            return defVal;
        }
    }

    private static String getString(Properties p, String key, String defVal) {
        try {
            String v = p.getProperty(key);
            if (v == null) return defVal;
            String s = v.trim();
            return s.isEmpty() ? defVal : s;
        } catch (Exception e) {
            return defVal;
        }
    }
}
