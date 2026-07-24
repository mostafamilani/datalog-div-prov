import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public class BatchRunner {

    private static final long DEFAULT_PROCESS_TIME_OUT_MINUTES = 1000000;

    /**
     * Simplified LP-only usage:
     *   java BatchRunner <inputFolder> <outputFolder>
     *                    [k] [print] [proof] [mode]
     *                    [configFile]
     *
     * Options:
     *   proof : dag | hypertree
     *   mode  : arbitrary | unambiguous
     *
     * Defaults come from config/runner.properties (or the provided config file).
     */
    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println(
                    "Usage: java BatchRunner <inputFolder> <outputFolder> " +
                    "[k] [print] [proof] [mode] [configFile]"
            );
            System.exit(1);
        }

        final Path inputFolder = Paths.get(args[0]).toAbsolutePath().normalize();
        final Path outputFolder = Paths.get(args[1]).toAbsolutePath().normalize();

        String configFileArg = "config/runner.properties";
        if (args.length >= 3) {
            String last = args[args.length - 1];
            if (isConfigArg(last)) {
                configFileArg = last;
            }
        }

        final Properties cfg = loadProps(configFileArg);
        final int cliEndExclusive = args.length - (isConfigArg(args[args.length - 1]) ? 1 : 0);

        final int cfgK = getInt(cfg, "defaults.k", getInt(cfg, "defaults.numInitialModels", 2));
        final boolean cfgPrint = getBool(cfg, "defaults.print", getBool(cfg, "defaults.printModels", false));
        final String cfgProof = getString(cfg, "defaults.proof", "dag");
        final String cfgMode = getString(cfg, "defaults.mode", getString(cfg, "defaults.ch", "unambiguous"));
        final long processTimeoutMinutes =
                getLong(cfg, "batch.processTimeoutMinutes", DEFAULT_PROCESS_TIME_OUT_MINUTES);

        final int k = (cliEndExclusive >= 3 ? parseIntArg(args[2], cfgK) : cfgK);
        final boolean printModels = (cliEndExclusive >= 4 ? parseBoolArg(args[3], cfgPrint) : cfgPrint);
        final String proofParam = (cliEndExclusive >= 5 ? canonicalProof(args[4]) : canonicalProof(cfgProof));
        final String modeParam = (cliEndExclusive >= 6 ? canonicalMode(args[5]) : canonicalMode(cfgMode));

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

        System.out.println("[BatchRunner] inputFolder   : " + inputFolder);
        System.out.println("[BatchRunner] outputFolder  : " + outputFolder);
        System.out.println("[BatchRunner] config        : " + configFileArg);
        System.out.println("[BatchRunner] k=" + k + ", print=" + printModels + ", proof=" + proofParam + ", mode=" + modeParam);
        System.out.println("[BatchRunner] timeout(min)  : " + processTimeoutMinutes);

        Set<Object> seenKeys = new HashSet<>();
        Set<String> seenRealPaths = new HashSet<>();

        try (java.util.stream.Stream<Path> walk = Files.walk(inputFolder)) {
            int processed = 0;
            int skipped = 0;

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

                    Path expectedLpLogPath = outDirForThisFile.resolve(buildLpLogName(real, k, proofParam, modeParam));

                    List<String> forwarded = new ArrayList<>();
                    forwarded.add(real.toString());
                    forwarded.add(outDirForThisFile.toString());
                    forwarded.add("0"); // total models disabled, initial-only mode
                    forwarded.add(String.valueOf(k));
                    forwarded.add(String.valueOf(printModels));
                    forwarded.add("false"); // extraAnalysis
                    forwarded.add(proofParam);
                    forwarded.add(modeParam);
                    forwarded.add("false"); // metricsOnly
                    forwarded.add(configFileArg);

                    try {
                        List<String> cmd = new ArrayList<>();
                        String javaBin = System.getProperty("java.home")
                                + java.io.File.separator + "bin" + java.io.File.separator
                                + (System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "java.exe" : "java");
                        cmd.add(javaBin);
                        cmd.add("-cp");
                        cmd.add(System.getProperty("java.class.path"));
                        cmd.add("LPModelEnumerator");
                        cmd.addAll(forwarded);

                        System.out.println("[CMD] " + String.join(" ", cmd));
                        System.out.println("[OUT] " + outDirForThisFile);

                        long procStartNs = System.nanoTime();
                        Process p = new ProcessBuilder(cmd).inheritIO().start();

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
                            System.err.println("[TIMEOUT] LPModelEnumerator exceeded " + processTimeoutMinutes
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
                                    "================================================================"
                            ));
                        } else {
                            exitCode = p.exitValue();
                        }

                        if (exitCode != 0) {
                            if (exitCode != 124) {
                                appendRunnerDiagnostic(expectedLpLogPath, Arrays.asList(
                                        "----------------------------------------------------------------",
                                        "[RUNNER] status=NONZERO_EXIT",
                                        "[RUNNER] file=" + real,
                                        "[RUNNER] exit_code=" + exitCode,
                                        "================================================================"
                                ));
                            }
                            throw new RuntimeException("LPModelEnumerator exited with code " + exitCode);
                        }

                        processed++;
                    } catch (Throwable t) {
                        System.err.println("[ERROR] Failure running LPModelEnumerator for " + real.getFileName() + ": " + t.getMessage());
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

    private static String canonicalProof(String proofParam) {
        String p = safeLower(proofParam, "dag");
        if (p.equals("hypertree") || p.equals("hypergraph") || p.equals("hyper") || p.equals("hg")) {
            return "hypertree";
        }
        return "dag";
    }

    private static String canonicalMode(String modeParam) {
        String c = safeLower(modeParam, "unambiguous");
        if (c.equals("arbitrary") || c.equals("standard") || c.equals("std")) return "arbitrary";
        return "unambiguous";
    }

    private static String baseNameNoExt(Path filePath) {
        String n = filePath.getFileName().toString();
        int dot = n.lastIndexOf('.');
        return (dot >= 0) ? n.substring(0, dot) : n;
    }

    private static String buildLpLogName(Path lpFile, int k, String proofParam, String modeParam) {
        return String.format(
                "%s-initial-%d-total-0-proof-%s-ch-%s.lp.log",
                baseNameNoExt(lpFile),
                k,
                canonicalProof(proofParam).equals("hypertree") ? "hypertree" : "dag",
                canonicalMode(modeParam)
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
        }
        return p;
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
