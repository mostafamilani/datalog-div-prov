import org.datalog.DatalogSAT;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;


public class SATModelEnumerator {

    private static final int MAX_INITIAL = 5;
    //    private static final int MAX_INITIAL = 3;
    private static final int MAX_ITERATION = 10;
    //    private static final int MAX_ITERATION = 30;
    static final long PROCESS_TIME_OUT = 30;
    //    static final long PROCESS_TIME_OUT = 5;
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

    private static void enforceFreshModel(DatalogSAT sat, long solverHandle, List<Long> literals) {
        // Hard clause: forbid the current model as a whole
        sat.addSolverClause(solverHandle, literals, 0);
        // Soft clauses on each literal to encourage diversity among future models
        for (long lit : literals) {
            sat.addSolverClause(solverHandle, List.of(lit), 1);
        }
    }

    // ======= Filename → goal parsing helpers =======
    private static final class GoalParts {
        final Integer prefixCount; // e.g., 15 in "15_subClassOf_..."
        final String predicate;
        final String arg1;
        final String arg2; // may include underscores
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

    /**
     * Pattern: <count>_<predicate>_<arg1>_<arg2...>
     * We split with limit=4 so arg2 may contain underscores (e.g., 23_1_10075).
     */
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

    private static String esc(String s) { return s.replace("\"", "\\\""); }
    private static String buildGoal(GoalParts gp) {
        return gp.predicate + "(\"" + esc(gp.arg1) + "\",\"" + esc(gp.arg2) + "\")";
    }

    /** Heuristic: does a token look like a goal (has '(' and ')')? */
    private static boolean looksLikeGoal(String s) {
        if (s == null) return false;
        int i = s.indexOf('('), j = s.lastIndexOf(')');
        return i > 0 && j > i;
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
    // ===============================================

    private static String joinPath(String dir, String fileName) {
        if (dir == null || dir.isBlank()) return fileName;
        return new File(dir, fileName).getPath();
    }

    public static void main(String[] args) {
        // Updated CLI semantics:
        //   java org.datalog.SATModelEnumerator <downwardFile> <outDir> [numModels=-1] [numInitialModels=2] [print=false] [extraAnalysis=false]
        //
        // numModels semantics:
        //   -1 : iterate until no more models (unbounded)
        //    0 : DO NOT run iterative phase; only compute the initial set (numInitialModels)
        //   >0 : total models desired (including initial); iterative continues until modelCount == numModels
        //
        // Notes:
        // - goal is auto-parsed from filename (as before)
        // - log file is written under <outDir>
        if (args.length < 2) {
            System.err.println("Usage: java org.datalog.SATModelEnumerator <downwardFile> <outDir> [numModels=-1] [numInitialModels=2] [print=false] [extraAnalysis=false]");
            System.exit(1);
        }

        final long tAllStart = now();

        final String downwardFile = args[0];
        final String outDir = args[1];

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

        // ---- goal auto-parsed ----
        String goal = FileNameParser.goalFromFilename(downwardFile);

        // Remaining optional args (start at index 2 now)
        int idx = 2;

        // DEFAULT numModels is now -1 (unbounded iterative), not 0.
        int numModels =
                (args.length > idx && isInt(args[idx])) ? Integer.parseInt(args[idx].trim()) : -1;

        final int numInitialModels =
                (args.length > idx+1 && isInt(args[idx+1])) ? Integer.parseInt(args[idx+1].trim()) : 2;
        final boolean printModels  =
                (args.length > idx+2) ? parseBool(args[idx+2], false) : false;
        final boolean extraAnalysis=
                (args.length > idx+3) ? parseBool(args[idx+3], false) : false;

        // ---- Build log file name (now inside outDir) ----
        String base = baseName(downwardFile);
        final String logName = String.format("%s-initial-%d-total-%d.log", base, numInitialModels, numModels);
        final String logFile = joinPath(outDir, logName);

        java.io.File lf = new java.io.File(logFile);
        if (lf.exists() && lf.isFile()) {
            System.out.println("[SKIP] Log already exists for this configuration: " + lf.getAbsolutePath());
            return;
        }

        // Open log file in APPEND mode (never overwrite)
        try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(
                new FileOutputStream(logFile, true), StandardCharsets.UTF_8))) {
            outWriter = pw;

            String cmd = "";
            for (String arg : args) {
                cmd += arg + " ";
            }
            log(cmd);

            // ---- Run header (includes numModels and numInitialModels) ----
            String ts = ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            log("================================================================");
            logf("RUN @ %s", ts);
            logf("downwardFile : %s", downwardFile);
            logf("outDir       : %s", outDirFile.getAbsolutePath());
            GoalParts gpEcho = parseGoalFromFileName(downwardFile);
            if (gpEcho != null) {
                logf("filename meta : count=%s, predicate=%s, arg1=%s, arg2=%s",
                        gpEcho.prefixCount == null ? "?" : gpEcho.prefixCount.toString(),
                        gpEcho.predicate, gpEcho.arg1, gpEcho.arg2);
            }
            logf("goal          : %s", goal);
            logf("numModels     : %d (-1=unbounded iterative, 0=only initial set)", numModels);
            logf("numInitial    : %d", numInitialModels);
            logf("print facts   : %s", printModels ? "true" : "false");
            log("----------------------------------------------------------------");
            System.out.println("Starting to create the solver");

            // ---- Build SAT encoding ----
            final long tBuildStart = now();
            DatalogSAT sat = new DatalogSAT();
            long handle     = sat.getSATEncodingWithNumSolverCopiesHandle(downwardFile, goal, numInitialModels);
            long solver     = sat.getSATEncodingSolverHandle(handle);
            long solverCopy = sat.getSATEncodingCopySolverHandle(handle);
            final long tBuildEnd = now();

            logf("Build time    : %d ms", dtMs(tBuildStart, tBuildEnd));
            log("");

            // Deterministic order of DB variables (parity with ordered map in C++)
            Map<Long, String> dbVarMap = sat.getSATEncodingDBVarMap(handle);
            List<Long> dbVars = new ArrayList<>(dbVarMap.keySet());
            Collections.sort(dbVars);
            logf("Number of variables    : %d", dbVars.size());

            // ---- Initial models: add XOR constraints if >=2 copies ----
            if (numInitialModels >= 2) {
                for (int i = 0; i < numInitialModels; i++) {
                    for (int j = i + 1; j < numInitialModels; j++) {
                        List<Long> auxVariables = new ArrayList<>(dbVars.size());
                        for (Long baseVar : dbVars) {
                            long var0 = sat.getVariableNameInCopy(handle, baseVar, i);
                            long var1 = sat.getVariableNameInCopy(handle, baseVar, j);
                            long aux  = sat.addXorAxiomatizationToSolver(solverCopy, List.of(var0, var1));
                            // Soft clause encourages difference on this fact
                            sat.addSolverClause(solverCopy, List.of(aux), 1);
                            auxVariables.add(aux);
                        }
                        // Hardly require at least one xor (copies differ somewhere)
                        sat.addSolverClause(solverCopy, auxVariables, 0);
                    }
                }
                log("");
            }

            // ---- Solve once to get numInitialModels optimally diverse explanations ----
            logf("Solving initial phase to find %d explanation(s)...", numInitialModels);
            final long tInitStart = now();
            boolean initSat = sat.solve(solverCopy);
            final long tInitEnd = now();
            final long initMs = dtMs(tInitStart, tInitEnd);

            if (!initSat) {
                log("No explanations found in the initial phase.");
                logf("Initial phase time : %d ms", initMs);
                logf("End-to-end time    : %d ms", dtMs(tAllStart, now()));
                log("================================================================");
                return;
            }

            long initDiversity = sat.getSolverCurrentSolutionCost(solverCopy);
            logf("Initial phase time : %d ms", initMs);
            logf("Initial diversity  : %d", initDiversity);

            long diversity = initDiversity;

            // Print true variables per initial model (if requested) and prepare blockers
            List<Long> modelBlocker = new ArrayList<>(dbVars.size());
            for (int i = 0; i < numInitialModels; i++) {
                modelBlocker.clear();
                int trueCount = 0;

                if (printModels) logf("-- Initial Model #%d --", i);

                for (Long baseVar : dbVars) {
                    long varBase = sat.getVariableNameInCopy(handle, baseVar, i);
                    boolean val  = sat.getSolverCurrentSolutionLiteralValue(solverCopy, varBase);

                    long baseVarId = baseVar;

                    if (val) {
                        if (printModels) logf("  %s = true", dbVarMap.get(baseVar));
                        trueCount++;
                        modelBlocker.add(-baseVarId);
                    } else {
                        modelBlocker.add(baseVarId);
                    }
                }
                if (printModels) logf("  (#true vars: %d)", trueCount);

                // Enforce freshness on the *main* solver (not solverCopy)
                enforceFreshModel(sat, solver, modelBlocker);
            }
            log("");

            // ---- Iterative phase: enumerate additional models on main solver ----
            int modelCount = numInitialModels;
            int iterFound  = 0;
            long tIterTotal = 0L;

            // NEW semantics:
            //   numModels == 0 => do NOT iterate at all.
            //   numModels == -1 => iterate until UNSAT.
            //   numModels  > 0 => stop when modelCount >= numModels.
            if (numModels == 0) {
                log("Skipping iterative phase (numModels=0 => only initial set).");
            } else {
                if (numModels < -1) numModels = MAX_ITERATION;

                while (true) {
                    if (numModels > 0 && modelCount >= numModels) {
                        logf("Reached user limit of %d model(s).", numModels);
                        break;
                    }

                    final long tPerStart = now();
                    boolean satMore = sat.solve(solver);
                    final long tPerEnd = now();
                    final long perMs = dtMs(tPerStart, tPerEnd);

                    if (!satMore) {
                        log("No more explanations exist.");
                        break;
                    }

                    long mDiversity = sat.getSolverCurrentSolutionCost(solver);
                    modelCount++;
                    iterFound++;
                    tIterTotal += perMs;
                    diversity += mDiversity;

                    if (printModels) {
                        logf("-- Iterative Model #%d --", modelCount - 1);
                        logf("  time     : %d ms", perMs);
                        logf("  marginal diversity: %d", mDiversity);
                        logf("  diversity: %d", diversity);
                    }

                    // Build blocker and (optionally) print true variables
                    List<Long> blocker = new ArrayList<>(dbVars.size());
                    int trueCount = 0;
                    for (Long baseVar : dbVars) {
                        boolean val = sat.getSolverCurrentSolutionLiteralValue(solver, baseVar);
                        if (val) {
                            if (printModels) logf("  %s = true", dbVarMap.get(baseVar));
                            trueCount++;
                            blocker.add(-baseVar);
                        } else {
                            blocker.add(baseVar);
                        }
                    }
                    if (printModels) logf("  (#true vars: %d)", trueCount);
                    if (printModels) log("");

                    // Enforce freshness for next iterate
                    enforceFreshModel(sat, solver, blocker);
                }
            }

            // ---- Summary & cleanup ----
            logf("Iterative models found : %d", iterFound);
            logf("Iterative total time   : %d ms", tIterTotal);
            final long tAllEnd = now();
            logf("End-to-end time        : %d ms", dtMs(tAllStart, tAllEnd));
            log("================================================================");
            log("Freeing the SAT encoding space...");
            sat.freeSATEncoding(handle);
            log("ExtraAnalysis: " + extraAnalysis);
            log("ModelCount: " + modelCount);

            // IMPORTANT with new semantics:
            // Extra-analysis sub-runs should be ONLY initial models (no iterative), so set numModels=0.
            if (extraAnalysis && modelCount > 1) {
                boolean finished = true;
                for (int i = 2; i <= Math.min(MAX_INITIAL, modelCount) & finished; i++) {
                    log("Running more analysis for " + i + " initial models (no iterative)...");

                    // Ensure we have enough args slots (downwardFile,outDir,numModels,numInitial,print,extra)
                    String[] newArgs = Arrays.copyOf(args, Math.max(args.length, idx + 4));
                    newArgs[0] = downwardFile;
                    newArgs[1] = outDir;
                    newArgs[idx]   = "0";        // numModels = 0 => only initial set
                    newArgs[idx+1] = "" + i;     // numInitialModels
                    // keep print flag as-is
                    newArgs[idx+3] = "false";    // avoid recursive extraAnalysis

                    List<String> cmdc = new ArrayList<>();
                    String javaBin = System.getProperty("java.home")
                            + File.separator + "bin" + File.separator
                            + (System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java");
                    cmdc.add(javaBin);
                    cmdc.add("-cp");
                    cmdc.add(System.getProperty("java.class.path"));
                    cmdc.add("org.datalog.SATModelEnumerator"); // fully-qualified class name
                    cmdc.addAll(Arrays.asList(newArgs));

                    Process p = new ProcessBuilder(cmdc)
                            .inheritIO()
                            .start();

                    finished = p.waitFor(SATModelEnumerator.PROCESS_TIME_OUT,
                            java.util.concurrent.TimeUnit.MINUTES);

                    int exitCode;
                    if (!finished) {
                        // Kill descendants first (Java 9+)
                        try {
                            java.lang.ProcessHandle ph = p.toHandle();
                            ph.descendants().forEach(h -> {
                                try { h.destroyForcibly(); } catch (Throwable ignored) {}
                            });
                        } catch (Throwable ignored) {}

                        // Kill the process itself
                        p.destroyForcibly();
                        exitCode = 124; // timeout
                    } else {
                        exitCode = p.exitValue();
                    }

                    if (exitCode != 0) {
                        throw new RuntimeException("SATModelEnumerator exited with code " + exitCode);
                    }
                }
            }

            log("Exit!");
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
}
