// LPModelEnumerator.java
import org.datalog.DatalogLP;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public class LPModelEnumerator {

    // Defaults (overridable by config)
    private static final int DEFAULT_MAX_INITIAL = 5;
    private static final int DEFAULT_MAX_ITERATION = 10; // number of ITERATIVE models when numModels == -1
    private static final long DEFAULT_PROCESS_TIME_OUT_MINUTES = 30;

    // New defaults for (proof, ch) if nothing provided
    private static final String DEFAULT_PROOF = "dag";              // dag | hypergraph | hypertree
    private static final String DEFAULT_CH = "unambiguous";         // standard | unambiguous (accepts "unambigous")

    // ========= Dual-output logger (stdout + file) =========
    private static PrintWriter outWriter = null;

    private static void log(String msg) {
        System.out.println(msg);
        if (outWriter != null) {
            outWriter.println(msg);
            outWriter.flush();
        }
    }

    private static void logf(String fmt, Object... args) {
        log(String.format(fmt, args));
    }
    // ======================================================

    private static long now() { return System.nanoTime(); }
    private static long dtMs(long start, long end) { return (end - start) / 1_000_000L; }

    private static long readLinuxRssKb() {
        File f = new File("/proc/self/status");
        if (!f.isFile()) return -1L;
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("VmRSS:")) {
                    String[] parts = line.trim().split("\\s+");
                    if (parts.length >= 2) {
                        return Long.parseLong(parts[1]);
                    }
                }
            }
        } catch (Exception ignored) {}
        return -1L;
    }

    private static long toMb(long bytes) {
        return bytes / (1024L * 1024L);
    }

    private static void logMemoryStats(String tag) {
        Runtime rt = Runtime.getRuntime();
        long heapUsed = rt.totalMemory() - rt.freeMemory();
        long heapTotal = rt.totalMemory();
        long heapMax = rt.maxMemory();
        long rssKb = readLinuxRssKb();
        if (rssKb >= 0L) {
            logf("[MEM][%s] heap_used_mb=%d heap_total_mb=%d heap_max_mb=%d rss_mb=%d",
                    tag, toMb(heapUsed), toMb(heapTotal), toMb(heapMax), rssKb / 1024L);
        } else {
            logf("[MEM][%s] heap_used_mb=%d heap_total_mb=%d heap_max_mb=%d",
                    tag, toMb(heapUsed), toMb(heapTotal), toMb(heapMax));
        }
    }

    /**
     * Enforce a fresh model:
     * - Hard constraint: sum(blocker literals) >= 1  (i.e., differ in at least one DB fact)
     * - Soft objective: add blocker literals to objective to encourage maximum difference
     *
     * NOTE: In this LP API, a negative literal means (1 - x), positive means x.
     */
    private static void enforceFreshModel(DatalogLP lp, long solverHandle, List<Long> literals) {
        lp.addGreaterOrEqualToConstantConstraint(solverHandle, literals, 1);
        lp.addVariablesToObjective(solverHandle, literals);
    }

    // ======= Filename ? goal parsing helpers (kept for logging/debug) =======
    private static final class GoalParts {
        final Integer prefixCount;
        final String predicate;
        final String arg1;
        final String arg2;
        GoalParts(Integer n, String p, String a1, String a2) {
            this.prefixCount = n; this.predicate = p; this.arg1 = a1; this.arg2 = a2;
        }
    }

    private static String baseName(String path) {
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        String name = (slash >= 0 ? path.substring(slash + 1) : path);
        int dot = name.lastIndexOf('.');
        return (dot >= 0 ? name.substring(0, dot) : name);
    }

    private static GoalParts parseGoalFromFileName(String filePath) {
        String base = baseName(filePath);
        String[] parts = base.split("_", 4);
        if (parts.length < 4) return null;

        Integer n = null;
        try { n = Integer.parseInt(parts[0]); } catch (Exception ignored) {}
        String predicate = parts[1];
        String arg1 = parts[2];
        String arg2 = parts[3];

        if (predicate.isEmpty() || arg1.isEmpty() || arg2.isEmpty()) return null;
        return new GoalParts(n, predicate, arg1, arg2);
    }

    private static boolean isInt(String s) {
        if (s == null || s.isEmpty()) return false;
        try { Integer.parseInt(s.trim()); return true; } catch (Exception e) { return false; }
    }

    private static boolean parseBool(String s, boolean defVal) {
        if (s == null) return defVal;
        String v = s.trim().toLowerCase(Locale.ROOT);
        if (v.equals("true") || v.equals("1") || v.equals("y") || v.equals("yes")) return true;
        if (v.equals("false") || v.equals("0") || v.equals("n") || v.equals("no")) return false;
        return defVal;
    }

    private static boolean isConfigArg(String s) {
        if (s == null || s.isBlank()) return false;
        File f = new File(s);
        return s.toLowerCase(Locale.ROOT).endsWith(".properties") || f.exists();
    }
    // ======================================================================

    private static String joinPath(String dir, String fileName) {
        if (dir == null || dir.isBlank()) return fileName;
        return new File(dir, fileName).getPath();
    }

    // ---- Config helpers ----
    private static Properties loadPropsIfExists(String path) {
        Properties p = new Properties();
        if (path == null || path.isBlank()) return p;
        File f = new File(path);
        if (!f.exists() || !f.isFile()) return p;
        try (FileInputStream fis = new FileInputStream(f)) {
            p.load(fis);
        } catch (Exception ignored) {}
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

    private static String norm(String s, String defVal) {
        if (s == null) return defVal;
        String t = s.trim().toLowerCase(Locale.ROOT);
        return t.isEmpty() ? defVal : t;
    }

    private static DatalogLP.Characterization parseProofCharacterization(String proof) {
        String p = norm(proof, DEFAULT_PROOF);

        // Accept a few aliases
        if (p.equals("dag") || p.equals("proof_dag") || p.equals("proof-dag")) {
            return DatalogLP.Characterization.PROOF_DAG;
        }
        if (p.equals("hypergraph") || p.equals("hypertree") || p.equals("hyper") || p.equals("hg")) {
            return DatalogLP.Characterization.HYPERGRAPH;
        }

        // Fallback default
        return DatalogLP.Characterization.PROOF_DAG;
    }

    private static DatalogLP.ProofTree parseProofTree(String ch) {
        String c = norm(ch, DEFAULT_CH);

        // Accept misspelling "unambigous"
        if (c.equals("unambiguous") || c.equals("unambigous") || c.equals("ua")) {
            return DatalogLP.ProofTree.UNAMBIGUOUS;
        }
        if (c.equals("standard") || c.equals("std")) {
            return DatalogLP.ProofTree.STANDARD;
        }

        // Fallback default
        return DatalogLP.ProofTree.UNAMBIGUOUS;
    }

    private static String proofToCliLabel(DatalogLP.Characterization ch) {
        if (ch == DatalogLP.Characterization.HYPERGRAPH) return "hypergraph";
        return "dag";
    }

    private static String treeToCliLabel(DatalogLP.ProofTree t) {
        if (t == DatalogLP.ProofTree.STANDARD) return "standard";
        return "unambiguous";
    }

    public static void main(String[] args) {
        /**
         * CLI:
         *   java LPModelEnumerator <downwardFile> <outDir>
         *                          [numModels] [numInitialModels] [print] [extraAnalysis]
         *                          [proof] [ch] [metricsOnly]
         *                          [configFile]
         *
         * proof : dag | hypergraph | hypertree
         * ch    : standard | unambiguous (also accepts "unambigous")
         * metricsOnly : true | false
         *
         * Defaults if omitted:
         *   proof = dag
         *   ch    = unambiguous
         *
         * numModels semantics:
         *   -1 : iterate until no more models, but capped by maxIteration (iterative models)
         *    0 : DO NOT run iterative phase; only compute the initial set (numInitialModels)
         *   >0 : total models desired (including initial); stop when modelCount == numModels (or UNSAT earlier)
         */
        if (args.length < 2) {
            System.err.println("Usage: java LPModelEnumerator <downwardFile> <outDir> [numModels] [numInitialModels] [print] [extraAnalysis] [proof] [ch] [metricsOnly] [configFile]");
            System.exit(1);
        }

        final long tAllStart = now();
        final String downwardFile = args[0];
        final String outDir = args[1];

        // Optional config file is LAST argument (if present and ends with .properties or exists)
        String configFile = null;
        if (args.length >= 3) {
            String last = args[args.length - 1];
            if (isConfigArg(last)) {
                configFile = last;
            }
        }
        final Properties cfg = loadPropsIfExists(configFile);

        // Configurable knobs
        final int MAX_INITIAL = getInt(cfg, "lp.maxInitial", getInt(cfg, "maxInitial", DEFAULT_MAX_INITIAL));
        final int MAX_ITERATION = getInt(cfg, "lp.maxIteration", getInt(cfg, "maxIteration", DEFAULT_MAX_ITERATION));
        final long PROCESS_TIME_OUT =
                getLong(cfg, "lp.processTimeoutMinutes", getLong(cfg, "processTimeoutMinutes", DEFAULT_PROCESS_TIME_OUT_MINUTES));

        final AtomicBoolean runFinished = new AtomicBoolean(false);
        final AtomicReference<String> stageRef = new AtomicReference<>("startup");
        final AtomicLong programCreateStartNs = new AtomicLong(-1L);
        final AtomicLong programCreateEndNs = new AtomicLong(-1L);
        Thread timeoutWatchdog = null;

        // Defaults for CLI-visible params (config-driven)
        final int defaultNumModels = getInt(cfg, "defaults.numModels", -1);
        final int defaultNumInitialModels = getInt(cfg, "defaults.numInitialModels", 2);
        final boolean defaultPrintModels = getBool(cfg, "defaults.printModels", false);
        final boolean defaultExtraAnalysis = getBool(cfg, "defaults.extraAnalysis", false);
        final boolean defaultMetricsOnly = getBool(cfg, "defaults.metricsOnly", false);

        // New defaults for proof/ch (config-driven)
        final String defaultProofStr = norm(getString(cfg, "defaults.proof", DEFAULT_PROOF), DEFAULT_PROOF);
        final String defaultChStr    = norm(getString(cfg, "defaults.ch", DEFAULT_CH), DEFAULT_CH);

        // Determine how many args are "real" (exclude config if present)
        final int cliEndExclusive = args.length - (configFile != null ? 1 : 0);

        // Parse args (but allow config defaults if missing)
        int idx = 2;
        int numModels = (cliEndExclusive > idx && isInt(args[idx])) ? Integer.parseInt(args[idx].trim()) : defaultNumModels;

        final int numInitialModels =
                (cliEndExclusive > idx + 1 && isInt(args[idx + 1])) ? Integer.parseInt(args[idx + 1].trim()) : defaultNumInitialModels;

        final boolean printModels  = (cliEndExclusive > idx + 2) ? parseBool(args[idx + 2], defaultPrintModels) : defaultPrintModels;
        final boolean extraAnalysis = (cliEndExclusive > idx + 3) ? parseBool(args[idx + 3], defaultExtraAnalysis) : defaultExtraAnalysis;

        // New: proof/ch as optional positional args
        final String proofStr = (cliEndExclusive > idx + 4) ? norm(args[idx + 4], defaultProofStr) : defaultProofStr;
        final String chStr    = (cliEndExclusive > idx + 5) ? norm(args[idx + 5], defaultChStr) : defaultChStr;
        final boolean metricsOnly = (cliEndExclusive > idx + 6) ? parseBool(args[idx + 6], defaultMetricsOnly) : defaultMetricsOnly;

        final DatalogLP.Characterization characterization = parseProofCharacterization(proofStr);
        final DatalogLP.ProofTree proofTree = parseProofTree(chStr);

        // Ensure output directory exists
        File outDirFile = new File(outDir);
        if (!outDirFile.exists()) {
            if (!outDirFile.mkdirs()) {
                System.err.println("Could not create output directory: " + outDirFile.getAbsolutePath());
                System.exit(2);
            }
        } else if (!outDirFile.isDirectory()) {
            System.err.println("Output path is not a directory: " + outDirFile.getAbsolutePath());
            System.exit(2);
        }

        // Goal parsing
        String goal = FileNameParser.goalFromFilename(downwardFile);

        // ---- Log file name includes proof/ch to avoid collisions ----
        final String base = baseName(downwardFile);
        final String logName = String.format(
                "%s-initial-%d-total-%d-proof-%s-ch-%s%s.lp.log",
                base,
                numInitialModels,
                numModels,
                proofToCliLabel(characterization),
                treeToCliLabel(proofTree),
                metricsOnly ? "-metrics-only" : ""
        );
        final String logFile = joinPath(outDir, logName);

        File lf = new File(logFile);
        if (lf.exists() && lf.isFile()) {
            System.out.println("[SKIP] Log already exists for this configuration: " + lf.getAbsolutePath());
            return;
        }

        try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(
                new FileOutputStream(logFile, true), StandardCharsets.UTF_8))) {
            outWriter = pw;

            try {

            StringBuilder cmd = new StringBuilder();
            for (String arg : args) cmd.append(arg).append(" ");
            log(cmd.toString().trim());

            String ts = ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            log("================================================================");
            logf("RUN @ %s", ts);
            logf("downwardFile : %s", downwardFile);
            logf("outDir       : %s", outDirFile.getAbsolutePath());
            logf("configFile   : %s", (configFile == null ? "(none)" : configFile));

            GoalParts gpEcho = parseGoalFromFileName(downwardFile);
            if (gpEcho != null) {
                logf("filename meta : count=%s, predicate=%s, arg1=%s, arg2=%s",
                        gpEcho.prefixCount == null ? "?" : gpEcho.prefixCount.toString(),
                        gpEcho.predicate, gpEcho.arg1, gpEcho.arg2);
            }

            logf("goal          : %s", goal);
            logf("numModels     : %d (-1=capped by maxIteration=%d, 0=only initial set)", numModels, MAX_ITERATION);
            logf("numInitial    : %d", numInitialModels);
            logf("print facts   : %s", printModels ? "true" : "false");
            logf("proof         : %s", proofToCliLabel(characterization));
            logf("ch            : %s", treeToCliLabel(proofTree));
            logf("metricsOnly   : %s", metricsOnly ? "true" : "false");
            logf("MAX_INITIAL   : %d", MAX_INITIAL);
            logf("PROCESS_TO(min): %d", PROCESS_TIME_OUT);
            log("----------------------------------------------------------------");
            logMemoryStats("startup");

            final long watchdogSleepMs = Math.max(1L, PROCESS_TIME_OUT) * 60_000L;
            timeoutWatchdog = new Thread(() -> {
                try {
                    Thread.sleep(watchdogSleepMs);
                } catch (InterruptedException ignored) {
                    return;
                }
                if (runFinished.get()) return;
                try {
                    long nowNs = now();
                    log("----------------------------------------------------------------");
                    logf("[TIMEOUT] Reached timeout after %d minute(s).", PROCESS_TIME_OUT);
                    logf("[TIMEOUT] Stage: %s", stageRef.get());

                    long pStart = programCreateStartNs.get();
                    long pEnd = programCreateEndNs.get();
                    if (pStart > 0L && pEnd > 0L) {
                        logf("Program creation time (variables+constraints): %d ms", dtMs(pStart, pEnd));
                    } else if (pStart > 0L) {
                        logf("Program creation time so far (incomplete): %d ms", dtMs(pStart, nowNs));
                    } else {
                        log("Program creation had not started.");
                    }

                    logf("[TIMEOUT] End-to-end elapsed : %d ms", dtMs(tAllStart, nowNs));
                    logMemoryStats("timeout");
                    log("================================================================");
                    if (outWriter != null) outWriter.flush();
                } catch (Throwable ignored) {}
                Runtime.getRuntime().halt(124);
            }, "lp-timeout-watchdog");
            timeoutWatchdog.setDaemon(true);
            timeoutWatchdog.start();

            // ---- Build LP encoding / program creation ----
            stageRef.set("build-program");
            final long tProgramCreateStart = now();
            programCreateStartNs.set(tProgramCreateStart);
            final long tBuildStart = tProgramCreateStart;
            DatalogLP lp = new DatalogLP();

            /*
              DatalogLP.ProofTree.UNAMBIGUOUS or DatalogLP.ProofTree.STANDARD
              DatalogLP.Characterization.PROOF_DAG or DatalogLP.Characterization.HYPERGRAPH
             */
                        stageRef.set("build-program:get-lp-handle");
            long handle = lp.getLPEncodingWithNumSolverCopiesHandle(
                    downwardFile,
                    goal,
                    numInitialModels,
                    characterization,
                    proofTree
            );

            final long tBuildEnd = now();
            final long buildMs = dtMs(tBuildStart, tBuildEnd);

            stageRef.set("build-program:get-solver-handles");
            long solver = lp.getLPEncodingSolverHandle(handle);
            long solverCopy = lp.getLPEncodingCopySolverHandle(handle);

            stageRef.set("build-program:optimization-metrics");
            long optimizationVars = lp.getSolverNumVariables(solver);
            long optimizationConstraints = lp.getSolverNumConstraints(solver);
            logf("Number of variables : %d", optimizationVars);
            logf("Number of clauses   : %d", optimizationConstraints);

            if (characterization == DatalogLP.Characterization.PROOF_DAG) {
                stageRef.set("build-program:acyclicity-metrics");
                long acyclicityVars = lp.getNumVarsAcyclicity(handle);
                long acyclicityClauses = lp.getNumClausesAcyclicity(handle);
                logf("Number of acyclicity variables : %d", acyclicityVars);
                logf("Number of acyclicity clauses   : %d", acyclicityClauses);
            } else {
                log("Number of acyclicity variables : n/a (acyclicity checks are only meaningful for PROOF_DAG)");
                log("Number of acyclicity clauses   : n/a (acyclicity checks are only meaningful for PROOF_DAG)");
            }

                        stageRef.set("build-program:db-var-map");
            Map<Long, String> dbVarMap = lp.getLPEncodingDBVarMap(handle);
            List<Long> dbVars = new ArrayList<>(dbVarMap.keySet());
            Collections.sort(dbVars);
            logf("Number of DB variables (facts) : %d", dbVars.size());
            log("");

            if (metricsOnly) {
                final long tProgramCreateEnd = now();
                programCreateEndNs.set(tProgramCreateEnd);
                final long programCreateMs = dtMs(tProgramCreateStart, tProgramCreateEnd);
                logf("Build time    : %d ms", buildMs);
                logf("Program creation time (variables+constraints): %d ms", programCreateMs);
                logMemoryStats("after-program-build");
                log("");
                stageRef.set("metrics-only-cleanup");
                log("Skipping solver phases (metricsOnly=true).");
                logf("Initial phase time : %d ms", 0);
                logf("End-to-end time    : %d ms", dtMs(tAllStart, now()));
                log("================================================================");
                log("Freeing the LP encoding space...");
                lp.freeLPEncoding(handle);
                log("Exit!");
                return;
            }

            // ---- Initial models: XOR constraints across copies if >=2 ----
                        stageRef.set("build-program:add-xor-constraints");
            if (numInitialModels >= 2) {
                for (int i = 0; i < numInitialModels; i++) {
                    for (int j = i + 1; j < numInitialModels; j++) {
                        List<Long> auxVariables = new ArrayList<>(dbVars.size());
                        for (Long baseVar : dbVars) {
                            long var0 = lp.getVariableNameInCopy(handle, baseVar, i);
                            long var1 = lp.getVariableNameInCopy(handle, baseVar, j);
                            long aux = lp.addXorAxiomatizationToSolver(solverCopy, List.of(var0, var1));
                            auxVariables.add(aux);
                        }
                        lp.addVariablesToObjective(solverCopy, auxVariables);
                        lp.addGreaterOrEqualToConstantConstraint(solverCopy, auxVariables, 1);
                    }
                }
            }

            final long tProgramCreateEnd = now();
            programCreateEndNs.set(tProgramCreateEnd);
            final long programCreateMs = dtMs(tProgramCreateStart, tProgramCreateEnd);
            logf("Build time    : %d ms", buildMs);
            logf("Program creation time (variables+constraints): %d ms", programCreateMs);
            logMemoryStats("after-program-build");
            log("");

            // ---- Solve initial phase ----
            stageRef.set("solve-initial-phase");
            logf("Solving initial phase to find %d explanation(s)...", numInitialModels);
            final long tInitStart = now();
            boolean initSat = lp.solve(solverCopy);
            final long tInitEnd = now();
            final long initMs = dtMs(tInitStart, tInitEnd);

            if (!initSat) {
                log("No explanations found in the initial phase.");
                logf("Initial phase time : %d ms", initMs);
                logf("End-to-end time    : %d ms", dtMs(tAllStart, now()));
                log("================================================================");
                lp.freeLPEncoding(handle);
                return;
            }

            long initDiversity = lp.getSolverCurrentSolutionCost(solverCopy);
            logf("Initial phase time : %d ms", initMs);
            logf("Initial diversity  : %d", initDiversity);

            long diversity = initDiversity;

            // Block initial models in main solver
            for (int i = 0; i < numInitialModels; i++) {
                if (printModels) logf("-- Initial Model #%d --", i);

                List<Long> modelBlocker = new ArrayList<>(dbVars.size());
                int trueCount = 0;

                for (Long baseVar : dbVars) {
                    long copyVar = lp.getVariableNameInCopy(handle, baseVar, i);
                    boolean val = lp.getSolverCurrentSolutionVariableValue(solverCopy, copyVar);

                    if (val) {
                        if (printModels) logf("  %s = true", dbVarMap.get(baseVar));
                        trueCount++;
                        modelBlocker.add(-baseVar);
                    } else {
                        modelBlocker.add(baseVar);
                    }
                }

                if (printModels) logf("  (#true vars: %d)", trueCount);
                enforceFreshModel(lp, solver, modelBlocker);
            }
            log("");

            // ---- Iterative phase ----
            int modelCount = numInitialModels;
            int iterFound = 0;
            long tIterTotal = 0L;

            // stopAtTotalModels is a TOTAL-model cap
            final int stopAtTotalModels;
            if (numModels == 0) {
                stopAtTotalModels = modelCount; // no iteration
            } else if (numModels == -1) {
                stopAtTotalModels = numInitialModels + MAX_ITERATION; // cap iterative models
            } else if (numModels > 0) {
                stopAtTotalModels = numModels;
            } else {
                // weird negatives other than -1 => still cap
                stopAtTotalModels = numInitialModels + MAX_ITERATION;
            }

            if (numModels == 0) {
                log("Skipping iterative phase (numModels=0 => only initial set).");
            } else {
                while (true) {
                    if (modelCount >= stopAtTotalModels) {
                        if (numModels == -1) {
                            logf("Reached maxIteration cap: %d iterative model(s) (total models=%d).",
                                    MAX_ITERATION, stopAtTotalModels);
                        } else {
                            logf("Reached user limit of %d total model(s).", stopAtTotalModels);
                        }
                        break;
                    }

                    final long tPerStart = now();
                    stageRef.set("solve-iterative-model");
                    boolean satMore = lp.solve(solver);
                    final long tPerEnd = now();
                    final long perMs = dtMs(tPerStart, tPerEnd);

                    if (!satMore) {
                        log("No more explanations exist.");
                        break;
                    }

                    long mDiversity = lp.getSolverCurrentSolutionCost(solver);
                    modelCount++;
                    iterFound++;
                    tIterTotal += perMs;
                    diversity += mDiversity;

                    if (printModels) {
                        logf("-- Iterative Model #%d --", modelCount - 1);
                        logf("  time              : %d ms", perMs);
                        logf("  marginal diversity: %d", mDiversity);
                        logf("  diversity         : %d", diversity);
                    }

                    List<Long> blocker = new ArrayList<>(dbVars.size());
                    int trueCount = 0;

                    for (Long baseVar : dbVars) {
                        boolean val = lp.getSolverCurrentSolutionVariableValue(solver, baseVar);
                        if (val) {
                            if (printModels) logf("  %s = true", dbVarMap.get(baseVar));
                            trueCount++;
                            blocker.add(-baseVar);
                        } else {
                            blocker.add(baseVar);
                        }
                    }

                    if (printModels) {
                        logf("  (#true vars: %d)", trueCount);
                        log("");
                    }

                    enforceFreshModel(lp, solver, blocker);
                }
            }

            // ---- Summary & cleanup ----
            stageRef.set("cleanup");
            logf("Iterative models found : %d", iterFound);
            logf("Iterative total time   : %d ms", tIterTotal);
            final long tAllEnd = now();
            logf("End-to-end time        : %d ms", dtMs(tAllStart, tAllEnd));
            log("================================================================");
            log("Freeing the LP encoding space...");
            lp.freeLPEncoding(handle);

            log("ExtraAnalysis: " + extraAnalysis);
            log("ModelCount: " + modelCount);

            // ---- ExtraAnalysis: ONLY initial set sub-runs (numModels=0) ----
            if (extraAnalysis && modelCount > 1) {
                stageRef.set("extra-analysis");
                boolean finished = true;
                for (int i = 2; i <= Math.min(MAX_INITIAL, modelCount) && finished; i++) {
                    log("Running more analysis for " + i + " initial models (no iterative)...");

                    // Ensure args length:
                    // [downwardFile, outDir, numModels, numInitialModels, print, extraAnalysis, proof, ch, configFile]
                    int minLen = 9;
                    String[] newArgs = Arrays.copyOf(args, Math.max(cliEndExclusive + (configFile != null ? 1 : 0), minLen));

                    // Rebuild from scratch deterministically (avoid carrying old positions)
                    List<String> rebuilt = new ArrayList<>();
                    rebuilt.add(downwardFile);
                    rebuilt.add(outDir);
                    rebuilt.add("0");
                    rebuilt.add(String.valueOf(i));
                    rebuilt.add(String.valueOf(printModels));
                    rebuilt.add("false"); // extraAnalysis off for sub-runs
                    rebuilt.add(proofToCliLabel(characterization));
                    rebuilt.add(treeToCliLabel(proofTree));
                    if (configFile != null && !configFile.isBlank()) rebuilt.add(configFile);

                    // Convert to array
                    newArgs = rebuilt.toArray(new String[0]);

                    List<String> cmdc = new ArrayList<>();
                    String javaBin = System.getProperty("java.home")
                            + File.separator + "bin" + File.separator
                            + (System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "java.exe" : "java");
                    cmdc.add(javaBin);
                    cmdc.add("-cp");
                    cmdc.add(System.getProperty("java.class.path"));
                    cmdc.add("LPModelEnumerator");
                    cmdc.addAll(Arrays.asList(newArgs));

                    Process p = new ProcessBuilder(cmdc)
                            .inheritIO()
                            .start();

                    finished = p.waitFor(PROCESS_TIME_OUT, java.util.concurrent.TimeUnit.MINUTES);

                    int exitCode;
                    if (!finished) {
                        try {
                            java.lang.ProcessHandle ph = p.toHandle();
                            ph.descendants().forEach(h -> {
                                try { h.destroyForcibly(); } catch (Throwable ignored) {}
                            });
                        } catch (Throwable ignored) {}
                        p.destroyForcibly();
                        exitCode = 124;
                    } else {
                        exitCode = p.exitValue();
                    }

                    if (exitCode != 0) {
                        throw new RuntimeException("LPModelEnumerator exited with code " + exitCode);
                    }
                }
            }

            log("Exit!");
            } finally {
                runFinished.set(true);
                if (timeoutWatchdog != null) {
                    timeoutWatchdog.interrupt();
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            e.printStackTrace();
        } catch (Throwable t) {
            try {
                logf("[FATAL] stage=%s", stageRef.get());
                logMemoryStats("fatal");
            } catch (Throwable ignored) {}
            t.printStackTrace();
        } finally {
            outWriter = null;
        }
    }
}
