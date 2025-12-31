import org.datalog.DatalogLP;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class LPModelEnumerator {

    private static final int MAX_INITIAL = 4;
    private static final int MAX_ITERATION = 5;
    static final long PROCESS_TIME_OUT = 30; // minutes (used in extraAnalysis)

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

    // ======= Filename → goal parsing helpers (kept for logging/debug) =======
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
        String v = s.trim().toLowerCase();
        if (v.equals("true") || v.equals("1") || v.equals("y") || v.equals("yes")) return true;
        if (v.equals("false") || v.equals("0") || v.equals("n") || v.equals("no")) return false;
        return defVal;
    }
    // ======================================================================

    public static void main(String[] args) {
        // CLI matches SATModelEnumerator style:
        //   java LPModelEnumerator <downwardFile> [numModels=0] [numInitialModels=2] [print=false] [extraAnalysis=false]
        if (args.length < 1) {
            System.err.println("Usage: java LPModelEnumerator <downwardFile> [numModels] [numInitialModels=2] [print=false] [extraAnalysis=false]");
            System.exit(1);
        }

        final long tAllStart = now();
        final String downwardFile = args[0];

        // Goal parsing (same intent as your SAT code)
        // Adjust this if your FileNameParser is in a package.
        String goal = FileNameParser.goalFromFilename(downwardFile);

        int idx = 1;
        int numModels = (args.length > idx && isInt(args[idx])) ? Integer.parseInt(args[idx].trim()) : 0; // 0 = unlimited
        final int numInitialModels = (args.length > idx + 1 && isInt(args[idx + 1])) ? Integer.parseInt(args[idx + 1].trim()) : 2;
        final boolean printModels = (args.length > idx + 2) ? parseBool(args[idx + 2], false) : false;
        final boolean extraAnalysis = (args.length > idx + 3) ? parseBool(args[idx + 3], false) : false;

        // ---- Log file name includes numModels and numInitialModels ----
        int dot = downwardFile.lastIndexOf('.');
        String basePath = (dot > 0 ? downwardFile.substring(0, dot) : downwardFile);
        final String logFile = String.format("%s-initial-%d-total-%d.lp.log", basePath, numInitialModels, numModels);
        File lf = new File(logFile);
        if (lf.exists() && lf.isFile()) {
            System.out.println("[SKIP] Log already exists for this configuration: " + lf.getAbsolutePath());
            return;
        }

        try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(
                new FileOutputStream(logFile, true), StandardCharsets.UTF_8))) {
            outWriter = pw;

            // Log command line
            StringBuilder cmd = new StringBuilder();
            for (String arg : args) cmd.append(arg).append(" ");
            log(cmd.toString().trim());

            String ts = ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            log("================================================================");
            logf("RUN @ %s", ts);
            logf("downwardFile : %s", downwardFile);

            GoalParts gpEcho = parseGoalFromFileName(downwardFile);
            if (gpEcho != null) {
                logf("filename meta : count=%s, predicate=%s, arg1=%s, arg2=%s",
                        gpEcho.prefixCount == null ? "?" : gpEcho.prefixCount.toString(),
                        gpEcho.predicate, gpEcho.arg1, gpEcho.arg2);
            }

            logf("goal          : %s", goal);
            logf("numModels     : %d (0 means unlimited)", numModels);
            logf("numInitial    : %d", numInitialModels);
            logf("print facts   : %s", printModels ? "true" : "false");
            log("----------------------------------------------------------------");

            // ---- Build LP encoding ----
            final long tBuildStart = now();
            DatalogLP lp = new DatalogLP();

            // We want the multi-copy solver to compute initial diverse solutions
            long handle = lp.getLPEncodingWithNumSolverCopiesHandle(downwardFile, goal, numInitialModels);
            long solver = lp.getLPEncodingSolverHandle(handle);
            long solverCopy = lp.getLPEncodingCopySolverHandle(handle);

            final long tBuildEnd = now();
            logf("Build time    : %d ms", dtMs(tBuildStart, tBuildEnd));
            log("");

            // Deterministic order of DB variables (for stable output)
            Map<Long, String> dbVarMap = lp.getLPEncodingDBVarMap(handle);
            List<Long> dbVars = new ArrayList<>(dbVarMap.keySet());
            Collections.sort(dbVars);
            logf("Number of variables (DB facts) : %d", dbVars.size());
            log("");

            // ---- Initial models: add XOR constraints across copies if >=2 copies ----
            if (numInitialModels >= 2) {
                for (int i = 0; i < numInitialModels; i++) {
                    for (int j = i + 1; j < numInitialModels; j++) {
                        List<Long> auxVariables = new ArrayList<>(dbVars.size());

                        for (Long baseVar : dbVars) {
                            long var0 = lp.getVariableNameInCopy(handle, baseVar, i);
                            long var1 = lp.getVariableNameInCopy(handle, baseVar, j);

                            // aux = XOR(var0, var1)
                            long aux = lp.addXorAxiomatizationToSolver(solverCopy, List.of(var0, var1));
                            auxVariables.add(aux);
                        }

                        // Maximize differences between copy i and j
                        lp.addVariablesToObjective(solverCopy, auxVariables);

                        // Require at least one difference
                        lp.addGreaterOrEqualToConstantConstraint(solverCopy, auxVariables, 1);
                    }
                }
            }

            // ---- Solve initial phase ----
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

            // Print and block initial models in the *main* solver
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
                        modelBlocker.add(-baseVar); // (1 - x_base) in blocker
                    } else {
                        modelBlocker.add(baseVar);  // x_base in blocker
                    }
                }

                if (printModels) logf("  (#true vars: %d)", trueCount);

                // Enforce freshness on main solver
                enforceFreshModel(lp, solver, modelBlocker);
            }
            log("");

            // ---- Iterative phase ----
            int modelCount = numInitialModels;
            int iterFound = 0;
            long tIterTotal = 0L;

            if (numModels < 0) numModels = MAX_ITERATION;

            while (true) {
                if (numModels > 0 && modelCount >= numModels) {
                    logf("Reached user limit of %d model(s).", numModels);
                    break;
                }

                final long tPerStart = now();
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

                // Build blocker for next iteration (and optionally print model)
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

                // Enforce freshness for next iterate
                enforceFreshModel(lp, solver, blocker);
            }

            // ---- Summary & cleanup ----
            logf("Iterative models found : %d", iterFound);
            logf("Iterative total time   : %d ms", tIterTotal);
            final long tAllEnd = now();
            logf("End-to-end time        : %d ms", dtMs(tAllStart, tAllEnd));
            log("================================================================");
            log("Freeing the LP encoding space...");
            lp.freeLPEncoding(handle);

            log("ExtraAnalysis: " + extraAnalysis);
            log("ModelCount: " + modelCount);

            // ---- Optional extra analysis: rerun with varying numInitialModels ----
            if (extraAnalysis && modelCount > 1) {
                boolean finished = true;
                for (int i = 2; i <= Math.min(MAX_INITIAL, modelCount) && finished; i++) {
                    log("Running more analysis for " + i + " models...");
                    String[] newArgs = Arrays.copyOf(args, args.length - 1); // drop extraAnalysis flag
                    newArgs[idx] = "0";            // numModels unlimited in sub-run
                    newArgs[idx + 1] = "" + i;     // numInitialModels = i

                    List<String> cmdc = new ArrayList<>();
                    String javaBin = System.getProperty("java.home")
                            + File.separator + "bin" + File.separator
                            + (System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java");
                    cmdc.add(javaBin);
                    cmdc.add("-cp");
                    cmdc.add(System.getProperty("java.class.path"));
                    cmdc.add("LPModelEnumerator");

                    cmdc.addAll(Arrays.asList(newArgs));

                    Process p = new ProcessBuilder(cmdc)
                            .inheritIO()
                            .start();

                    finished = p.waitFor(LPModelEnumerator.PROCESS_TIME_OUT,
                            java.util.concurrent.TimeUnit.MINUTES);

                    int exitCode;
                    if (!finished) {
                        try {
                            java.lang.ProcessHandle ph = p.toHandle();
                            ph.descendants().forEach(h -> {
                                try { h.destroyForcibly(); } catch (Throwable ignored) {}
                            });
                        } catch (Throwable ignored) {}

                        p.destroyForcibly();
                        exitCode = 124; // timeout
                    } else {
                        exitCode = p.exitValue();
                    }

                    if (exitCode != 0) {
                        throw new RuntimeException("LPModelEnumerator exited with code " + exitCode);
                    }
                }
            }

            log("Exit!");
        } catch (IOException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
}
