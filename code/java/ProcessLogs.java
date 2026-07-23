import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ProcessLogs {

    // --------- regex patterns (top-level fields) ---------
    private static final Pattern RE_DOWNWARD_FILE = Pattern.compile("^downwardFile\\s*:\\s*(.+\\.lp)\\s*$", Pattern.MULTILINE);
    private static final Pattern RE_GOAL         = Pattern.compile("^goal\\s*:\\s*(.+)\\s*$", Pattern.MULTILINE);
    private static final Pattern RE_VARS         = Pattern.compile("^Number of variables\\s*:\\s*(\\d+)\\s*$", Pattern.MULTILINE);
    private static final Pattern RE_CLAUSES      = Pattern.compile("^Number of clauses\\s*:\\s*(\\d+)\\s*$", Pattern.MULTILINE);
    private static final Pattern RE_METRICS_ONLY = Pattern.compile("^metricsOnly\\s*:\\s*true\\s*$", Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);
    private static final Pattern RE_PROGRAM_CREATE_TIME = Pattern.compile("^Program creation time \\(variables\\+constraints\\)\\s*:\\s*(\\d+)\\s*ms\\s*$", Pattern.MULTILINE);
    private static final Pattern RE_INIT_TIME    = Pattern.compile("^Initial phase time\\s*:\\s*(\\d+)\\s*ms\\s*$", Pattern.MULTILINE);
    private static final Pattern RE_PROGRAM_CREATE_TIME_SO_FAR = Pattern.compile("^Program creation time so far \\(incomplete\\)\\s*:\\s*(\\d+)\\s*ms\\s*$", Pattern.MULTILINE);
    private static final Pattern RE_BUILD_TIME   = Pattern.compile("^Build time\\s*:\\s*(\\d+)\\s*ms\\s*$", Pattern.MULTILINE);
    private static final Pattern RE_INIT_DIV     = Pattern.compile("^Initial diversity\\s*:\\s*(\\d+)\\s*$", Pattern.MULTILINE);
    private static final Pattern RE_NUM_INITIAL  = Pattern.compile("^numInitial\\s*:\\s*(\\d+)\\s*$", Pattern.MULTILINE);

    // Model blocks (Initial/Iterative) — robust to CRLF, extra lines, etc.
    private static final Pattern RE_MODEL_BLOCK = Pattern.compile(
            "--\\s*(?<kind>Initial|Iterative)\\s+Model\\s+#(?<idx>\\d+)\\s*--\\s*"
                    + "(?<body>.*?)(?=^\\s*--\\s*(?:Initial|Iterative)\\s+Model\\s+#|\\z)",
            Pattern.MULTILINE | Pattern.DOTALL
    );

    // Lines inside a model block
    private static final Pattern RE_TRUE_LINE = Pattern.compile("^\\s*(?<atom>.+?)\\s*=\\s*true\\s*$", Pattern.MULTILINE);
    private static final Pattern RE_TIME_LINE = Pattern.compile("^\\s*time\\s*:\\s*(\\d+)\\s*ms\\s*$", Pattern.MULTILINE);
    private static final Pattern RE_DIV_LINE  = Pattern.compile("^\\s*diversity\\s*:\\s*(\\d+)\\s*$", Pattern.MULTILINE);

    // ----------------- Helpers -----------------

    private static String group1OrEmpty(Matcher m) {
        return (m != null && m.find()) ? m.group(1).trim() : "";
    }

    private static Integer group1OrNullInt(Pattern p, String txt) {
        Matcher m = p.matcher(txt);
        if (!m.find()) return null;
        try { return Integer.parseInt(m.group(1)); } catch (Exception e) { return null; }
    }

    private static int group1OrZeroInt(Pattern p, String txt) {
        Integer v = group1OrNullInt(p, txt);
        return v == null ? 0 : v;
    }

    private static String group1OrEmpty(Pattern p, String txt) {
        Matcher m = p.matcher(txt);
        return (m.find()) ? m.group(1).trim() : "";
    }

    private static Optional<String> extractDatasetFromPath(String lpPath) {
        if (lpPath == null || lpPath.isEmpty()) return Optional.empty();
        Path p = Paths.get(lpPath);
        for (int i = 0; i < p.getNameCount(); i++) {
            if ("downward".equals(p.getName(i).toString())) {
                if (i + 1 < p.getNameCount()) return Optional.of(p.getName(i + 1).toString());
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    private static int symdiffSize(Set<String> a, Set<String> b) {
        // |a Δ b|
        int count = 0;
        for (String x : a) if (!b.contains(x)) count++;
        for (String x : b) if (!a.contains(x)) count++;
        return count;
    }

    private static int pairwiseSymdiffSum(List<Set<String>> sets) {
        int total = 0;
        for (int i = 0; i < sets.size(); i++) {
            for (int j = i + 1; j < sets.size(); j++) {
                total += symdiffSize(sets.get(i), sets.get(j));
            }
        }
        return total;
    }

    private static final class ModelParseResult {
        final List<Set<String>> initSets;              // index-based list (may be empty)
        final Map<Integer, Set<String>> iterSets;      // idx -> set
        final Map<Integer, Integer> iterTimeMs;        // idx -> time
        final Map<Integer, Integer> iterDiv;           // idx -> diversity (as logged)

        ModelParseResult(List<Set<String>> initSets,
                         Map<Integer, Set<String>> iterSets,
                         Map<Integer, Integer> iterTimeMs,
                         Map<Integer, Integer> iterDiv) {
            this.initSets = initSets;
            this.iterSets = iterSets;
            this.iterTimeMs = iterTimeMs;
            this.iterDiv = iterDiv;
        }
    }

    private static ModelParseResult parseModelBlocksWithAtoms(String txt) {
        List<Set<String>> initSets = new ArrayList<>();
        Map<Integer, Set<String>> iterSets = new HashMap<>();
        Map<Integer, Integer> iterTime = new HashMap<>();
        Map<Integer, Integer> iterDiv = new HashMap<>();

        Matcher m = RE_MODEL_BLOCK.matcher(txt);
        while (m.find()) {
            String kind = m.group("kind");
            int idx = Integer.parseInt(m.group("idx"));
            String body = m.group("body");

            // atoms = all lines "<atom> = true"
            Set<String> atoms = new HashSet<>();
            Matcher tm = RE_TRUE_LINE.matcher(body);
            while (tm.find()) {
                String atom = tm.group("atom").trim();
                atoms.add(atom);
            }

            if ("Initial".equals(kind)) {
                while (initSets.size() <= idx) initSets.add(new HashSet<>());
                initSets.set(idx, atoms);
            } else {
                iterSets.put(idx, atoms);

                Matcher tmm = RE_TIME_LINE.matcher(body);
                if (tmm.find()) {
                    try { iterTime.put(idx, Integer.parseInt(tmm.group(1))); } catch (Exception ignored) {}
                }
                Matcher dvm = RE_DIV_LINE.matcher(body);
                if (dvm.find()) {
                    try { iterDiv.put(idx, Integer.parseInt(dvm.group(1))); } catch (Exception ignored) {}
                }
            }
        }

        // trim trailing empty initial sets
        while (!initSets.isEmpty() && initSets.get(initSets.size() - 1).isEmpty()) {
            initSets.remove(initSets.size() - 1);
        }

        return new ModelParseResult(initSets, iterSets, iterTime, iterDiv);
    }

    // Row is a map of string fields (like Python dict)
    private static List<Map<String, String>> parseLogFile(Path p, boolean checkDiversity) throws IOException {
        String txt = Files.readString(p, StandardCharsets.UTF_8);

        String lp = group1OrEmpty(RE_DOWNWARD_FILE, txt);
        String dataset = extractDatasetFromPath(lp).orElse("");
        String goal = group1OrEmpty(RE_GOAL, txt);

        Integer nvars = group1OrNullInt(RE_VARS, txt);
        Integer nclauses = group1OrNullInt(RE_CLAUSES, txt);
        boolean metricsOnly = RE_METRICS_ONLY.matcher(txt).find();
        int initTime = group1OrZeroInt(RE_INIT_TIME, txt);
        Integer programCreateTime = group1OrNullInt(RE_PROGRAM_CREATE_TIME, txt);
        if (programCreateTime == null) {
            programCreateTime = group1OrNullInt(RE_PROGRAM_CREATE_TIME_SO_FAR, txt);
        }
        if (programCreateTime == null) {
            programCreateTime = group1OrNullInt(RE_BUILD_TIME, txt);
        }

        Integer initDiv = group1OrNullInt(RE_INIT_DIV, txt);
        int k0 = group1OrNullInt(RE_NUM_INITIAL, txt) != null ? group1OrNullInt(RE_NUM_INITIAL, txt) : 1;

        ModelParseResult pr = parseModelBlocksWithAtoms(txt);

        // iter indices = union of keys
        Set<Integer> idxUnion = new HashSet<>();
        idxUnion.addAll(pr.iterSets.keySet());
        idxUnion.addAll(pr.iterTimeMs.keySet());
        idxUnion.addAll(pr.iterDiv.keySet());
        List<Integer> iterIndices = new ArrayList<>(idxUnion);
        Collections.sort(iterIndices);

        List<Map<String, String>> rows = new ArrayList<>();

        if (!iterIndices.isEmpty()) {
            // ========= GREEDY: initial row + one per iterative model =========
            String mode = "greedy";
            int cumulativeTime = initTime;

            if (initDiv != null && k0 >= 1) {
                Map<String, String> row = new LinkedHashMap<>();
                row.put("dataset", dataset);
                row.put("lp_file", lp);
                row.put("goal", goal);
                row.put("k", String.valueOf(k0));
                row.put("vars", (nvars == null) ? "" : String.valueOf(nvars));
                row.put("clauses", (nclauses == null) ? "" : String.valueOf(nclauses));
                row.put("metrics_only", metricsOnly ? "true" : "false");
                row.put("diversity", String.valueOf(initDiv));
                row.put("time_ms", String.valueOf(cumulativeTime));
                row.put("program_creation_time_ms", (programCreateTime == null) ? "" : String.valueOf(programCreateTime));
                row.put("mode", mode);
                row.put("log_file", p.toString());
                rows.add(row);
            }

            // diversity checking state
            List<Set<String>> selectedSets = new ArrayList<>();
            int cumulativeExpected = 0;

            if (checkDiversity) {
                if (!pr.initSets.isEmpty() && pr.initSets.size() >= k0 && initDiv != null) {
                    int recomputedInitCum = pairwiseSymdiffSum(pr.initSets.subList(0, k0));
                    if (recomputedInitCum != initDiv) {
                        System.out.println("[DIVERSITY MISMATCH][initial] " + p);
                        System.out.println("  expected cumulative=" + recomputedInitCum
                                + ", logged initial_div=" + initDiv + ", k0=" + k0);
                    }
                }

                if (!pr.initSets.isEmpty() && pr.initSets.size() >= k0) {
                    selectedSets.addAll(pr.initSets.subList(0, k0));
                    cumulativeExpected = pairwiseSymdiffSum(selectedSets);
                }
            }

            // Emit rows per iterative model
            int k = k0;
            for (Integer idx : iterIndices) {
                k += 1;
                int t = pr.iterTimeMs.getOrDefault(idx, 0);
                Integer dLogged = pr.iterDiv.get(idx);

                cumulativeTime += t;

                Map<String, String> row = new LinkedHashMap<>();
                row.put("dataset", dataset);
                row.put("lp_file", lp);
                row.put("goal", goal);
                row.put("k", String.valueOf(k));
                row.put("vars", (nvars == null) ? "" : String.valueOf(nvars));
                row.put("clauses", (nclauses == null) ? "" : String.valueOf(nclauses));
                row.put("metrics_only", metricsOnly ? "true" : "false");
                row.put("diversity", (dLogged == null) ? "" : String.valueOf(dLogged));
                row.put("time_ms", String.valueOf(cumulativeTime));
                row.put("program_creation_time_ms", (programCreateTime == null) ? "" : String.valueOf(programCreateTime));
                row.put("mode", mode);
                row.put("log_file", p.toString());
                rows.add(row);

                if (checkDiversity) {
                    Set<String> currentAtoms = pr.iterSets.get(idx);
                    if (!selectedSets.isEmpty() && currentAtoms != null) {
                        int marginalExpected = 0;
                        for (Set<String> s : selectedSets) {
                            marginalExpected += symdiffSize(currentAtoms, s);
                        }
                        cumulativeExpected += marginalExpected;

                        if (dLogged == null) {
                            System.out.println("[DIVERSITY INFO][iter " + idx + "] " + p);
                            System.out.println("  marginal_expected=" + marginalExpected
                                    + ", cumulative_expected=" + cumulativeExpected + " (no logged value)");
                        } else if (dLogged == marginalExpected && !dLogged.equals(cumulativeExpected)) {
                            System.out.println("[OK as MARGINAL][iter " + idx + "] " + p);
                            System.out.println("  logged=" + dLogged + ", marginal=" + marginalExpected
                                    + ", cumulative=" + cumulativeExpected);
                        } else if (dLogged.equals(cumulativeExpected)) {
                            System.out.println("[OK as CUMULATIVE][iter " + idx + "] " + p);
                            System.out.println("  logged=" + dLogged + ", cumulative=" + cumulativeExpected);
                        } else {
                            System.out.println("[DIVERSITY MISMATCH][iter " + idx + "] " + p);
                            System.out.println("  logged=" + dLogged + ", marginal_expected=" + marginalExpected
                                    + ", cumulative_expected=" + cumulativeExpected);
                        }

                        selectedSets.add(currentAtoms);
                    }
                }
            }

        } else {
            // ========= OPTIMAL / single-row logs =========
            String mode = (k0 == 1) ? "greedy" : "optimal";

            Map<String, String> row = new LinkedHashMap<>();
            row.put("dataset", dataset);
            row.put("lp_file", lp);
            row.put("goal", goal);
            row.put("k", String.valueOf(k0));
            row.put("vars", (nvars == null) ? "" : String.valueOf(nvars));
                row.put("clauses", (nclauses == null) ? "" : String.valueOf(nclauses));
                row.put("metrics_only", metricsOnly ? "true" : "false");
            row.put("diversity", (initDiv == null) ? "" : String.valueOf(initDiv));
            row.put("time_ms", String.valueOf(initTime));
            row.put("program_creation_time_ms", (programCreateTime == null) ? "" : String.valueOf(programCreateTime));
            row.put("mode", mode);
            row.put("log_file", p.toString());
            rows.add(row);

            if (checkDiversity) {
                if (!pr.initSets.isEmpty() && pr.initSets.size() >= k0 && initDiv != null) {
                    int recomputedInitCum = pairwiseSymdiffSum(pr.initSets.subList(0, k0));
                    if (recomputedInitCum != initDiv) {
                        System.out.println("[DIVERSITY MISMATCH][initial-only] " + p);
                        System.out.println("  expected cumulative=" + recomputedInitCum
                                + ", logged initial_div=" + initDiv + ", k0=" + k0);
                    }
                }
            }
        }

        return rows;
    }

    private static void writeCsv(String outCsv, List<Map<String, String>> allRows) throws IOException {
        boolean anyError = false;
        for (Map<String, String> r : allRows) {
            if (r.containsKey("error")) { anyError = true; break; }
        }

        List<String> fieldnames = new ArrayList<>(Arrays.asList(
            "dataset", "lp_file", "goal", "k", "vars", "clauses", "metrics_only", "diversity", "time_ms", "program_creation_time_ms", "mode", "log_file"
        ));
        if (anyError) fieldnames.add("error");

        System.out.println("[info] rows to write: " + allRows.size());
        System.out.println("[info] output path: " + outCsv);

        try (BufferedWriter bw = Files.newBufferedWriter(Paths.get(outCsv), StandardCharsets.UTF_8);
             PrintWriter out = new PrintWriter(bw)) {

            // header
            out.println(String.join(",", fieldnames));

            for (Map<String, String> r : allRows) {
                List<String> vals = new ArrayList<>(fieldnames.size());
                for (String k : fieldnames) {
                    vals.add(csvEscape(r.getOrDefault(k, "")));
                }
                out.println(String.join(",", vals));
            }
        }

        System.out.println("[done] CSV written.");
    }

    private static String csvEscape(String s) {
        if (s == null) return "";
        boolean needsQuotes = s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r");
        String v = s.replace("\"", "\"\"");
        return needsQuotes ? ("\"" + v + "\"") : v;
    }

    public static void parseLogsToCsv(String rootDir, String outCsv, boolean checkDiversity) throws IOException {
        Path root = Paths.get(rootDir);
        List<Map<String, String>> allRows = new ArrayList<>();

        if (!Files.exists(root)) {
            throw new IOException("Root directory does not exist: " + root.toAbsolutePath());
        }

        try (var walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".log"))
                    .forEach(logPath -> {
                        try {
                            boolean effectiveCheck = checkDiversity && logPath.getFileName().toString().endsWith("-1.log");
                            allRows.addAll(parseLogFile(logPath, effectiveCheck));
                        } catch (Exception e) {
                            Map<String, String> errRow = new LinkedHashMap<>();
                            errRow.put("dataset", "");
                            errRow.put("lp_file", "");
                            errRow.put("goal", "");
                            errRow.put("k", "");
                            errRow.put("vars", "");
                            errRow.put("clauses", "");
                            errRow.put("metrics_only", "");
                            errRow.put("diversity", "");
                            errRow.put("time_ms", "");
                            errRow.put("program_creation_time_ms", "");
                            errRow.put("mode", "");
                            errRow.put("log_file", logPath.toString());
                            errRow.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
                            allRows.add(errRow);
                        }
                    });
        }

        writeCsv(outCsv, allRows);
    }

    public static void main(String[] args) throws Exception {
        // Usage:
        //   java ProcessLogs <root_dir> <out_csv> [--check|--no-check]
        String root = (args.length > 0) ? args[0] : ".";
        String out  = (args.length > 1) ? args[1] : "explanation_sets_summary.csv";
        String flag = (args.length > 2) ? args[2] : "--no-check";
        boolean check = flag.trim().toLowerCase().matches("^(--check|--check-diversity|--debug|-c)$");

        System.out.println("[info] scanning: " + root);
        System.out.println("[info] diversity check: " + (check ? "ON" : "OFF"));

        parseLogsToCsv(root, out, check);

        System.out.println("[done] wrote: " + Paths.get(out).toAbsolutePath());
    }
}
