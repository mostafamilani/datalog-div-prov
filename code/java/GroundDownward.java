import java.io.*;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GroundDownward {

    // DLV_PATH = <this_class_dir>/bin/dlv2
    private static final Path DLV_PATH = resolveDlvPath();

    private static Path resolveDlvPath() {
        try {
            File where = new File(GroundDownward.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            File dir = where.isFile() ? where.getParentFile() : where; // jar vs classes dir
            return dir.toPath().resolve("bin").resolve("dlv2");
        } catch (URISyntaxException e) {
            throw new RuntimeException("Failed to resolve code location to find bin/dlv2", e);
        }
    }

    // ---------- NEW: text normalization & safe filename helpers ----------
    private static final Map<Character, Character> SMARTS = Map.ofEntries(
            Map.entry('“', '"'), Map.entry('”', '"'), Map.entry('„', '"'), Map.entry('‟', '"'), Map.entry('˝', '"'),
            Map.entry('‘', '\''), Map.entry('’', '\''), Map.entry('‚', '\''), Map.entry('‛', '\''), Map.entry('′', '\''),
            Map.entry('＂', '"'), Map.entry('＇', '\'')
    );

    private static final Pattern INVISIBLES_RE = Pattern.compile("[\\u200B-\\u200D\\u2060\\uFEFF]"); // zero-width chars

    public static String normalizeQueryText(String text) {
        if (text == null) return "";
        // Normalize Unicode NFKC
        String t = Normalizer.normalize(text, Normalizer.Form.NFKC);
        // Map smart quotes
        StringBuilder sb = new StringBuilder(t.length());
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            sb.append(SMARTS.getOrDefault(c, c));
        }
        t = sb.toString();
        // Strip invisibles
        t = INVISIBLES_RE.matcher(t).replaceAll("");
        return t;
    }

    public static String slugify(String text, int maxLen) {
        if (text == null) text = "";
        // Normalize -> ASCII (NFKD + remove non-ascii)
        String base = Normalizer.normalize(text, Normalizer.Form.NFKD);
        base = base.replaceAll("[^\\x00-\\x7F]", ""); // drop non-ascii
        // Keep [A-Za-z0-9_.-], collapse others to "_"
        base = base.replaceAll("[^\\w\\-.]+", "_");
        base = base.replaceAll("_+", "_").replaceAll("^[_.-]+|[_.-]+$", "");

        if (base.isEmpty()) base = "q";

        String h = sha1Hex(text).substring(0, 8);
        if (base.length() > maxLen) {
            int keep = maxLen - (1 + h.length());
            keep = Math.max(1, keep);
            base = base.substring(0, keep) + "_" + h;
        }
        return base;
    }

    private static String sha1Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] dig = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : dig) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("SHA-1 not available", e);
        }
    }
    // --------------------------------------------------------------------

    // ---------- Data classes ----------
    public static final class Atom {
        public String predicate;
        public List<String> terms;

        public Atom(String predicate, List<String> terms) {
            this.predicate = predicate;
            this.terms = (terms == null) ? new ArrayList<>() : new ArrayList<>(terms);
        }

        @Override
        public String toString() {
            if (terms == null || terms.isEmpty()) return predicate;
            return predicate + "(" + String.join(",", terms) + ")";
        }
    }

    public static final class Rule {
        public Atom head;
        public List<Atom> body; // null or empty => fact

        public Rule(Atom head, List<Atom> body) {
            this.head = head;
            this.body = body; // may be null
        }

        @Override
        public String toString() {
            if (body == null || body.isEmpty()) {
                return head.toString() + ".";
            }
            List<String> parts = new ArrayList<>();
            for (Atom a : body) parts.add(a.toString());
            return head.toString() + ":-" + String.join(",", parts) + ".";
        }
    }

    // ---------- Parser (port of your Python) ----------
    private static final class ParseResult<T> {
        final T value;
        final int idx;
        ParseResult(T value, int idx) { this.value = value; this.idx = idx; }
    }

    private static ParseResult<Character> nextToken(String input, int idx) {
        int n = input.length();
        while (idx < n && input.charAt(idx) == ' ') idx++;
        if (idx >= n) return new ParseResult<>(null, idx);
        return new ParseResult<>(input.charAt(idx), idx);
    }

    private static ParseResult<Atom> parseAtom(String input, int idx) {
        Atom atom = new Atom(null, new ArrayList<>());
        int state = 0;
        StringBuilder predicate = new StringBuilder();
        StringBuilder curTerm = new StringBuilder();

        int n = input.length();
        while (true) {
            if (idx >= n) return new ParseResult<>(null, idx);

            ParseResult<Character> tok = nextToken(input, idx);
            Character curCharObj = tok.value;
            idx = tok.idx;
            if (curCharObj == null) return new ParseResult<>(null, idx);

            char curChar = curCharObj;
            idx += 1;

            if (state == 0) {
                if (curChar == '(') {
                    state = 1;
                    atom.predicate = predicate.toString();
                } else if (Character.isLetterOrDigit(curChar) || curChar == '_') {
                    predicate.append(curChar);
                } else {
                    idx -= 1;
                    atom.predicate = predicate.toString();
                    return new ParseResult<>(atom, idx);
                }
            } else if (state == 1) {
                if (curChar == ')') {
                    atom.terms.add(curTerm.toString());
                    return new ParseResult<>(atom, idx);
                }
                curTerm.append(curChar);
                if (curChar == '"') {
                    state = 2;
                } else {
                    state = 3;
                }
            } else if (state == 2) {
                // (Matches your python, even though it appends twice—kept for behavior parity)
                curTerm.append(curChar);

                if (curChar == '"') {
                    if (idx >= n) return new ParseResult<>(null, idx);

                    ParseResult<Character> tok2 = nextToken(input, idx);
                    Character c2Obj = tok2.value;
                    idx = tok2.idx;
                    if (c2Obj == null) return new ParseResult<>(null, idx);
                    char c2 = c2Obj;
                    idx += 1;

                    if (c2 == ')') {
                        atom.terms.add(curTerm.toString());
                        return new ParseResult<>(atom, idx);
                    } else if (c2 == ',') {
                        atom.terms.add(curTerm.toString());
                        curTerm.setLength(0);
                        state = 1;
                    } else {
                        return new ParseResult<>(null, idx);
                    }
                }
            } else if (state == 3) {
                if (curChar == ')') {
                    atom.terms.add(curTerm.toString());
                    return new ParseResult<>(atom, idx);
                } else if (curChar == ',') {
                    atom.terms.add(curTerm.toString());
                    curTerm.setLength(0);
                    state = 1;
                } else {
                    curTerm.append(curChar);
                }
            }
        }
    }

    private static final class ProgramParse {
        final List<Rule> rules;
        final int maxArity;
        final int maxBody;
        ProgramParse(List<Rule> rules, int maxArity, int maxBody) {
            this.rules = rules;
            this.maxArity = maxArity;
            this.maxBody = maxBody;
        }
    }

    private static ProgramParse parseProgram(List<String> lines) {
        List<Rule> rules = new ArrayList<>();
        int maxArity = 0;
        int maxBody = 0;

        for (String ruleStr : lines) {
            int idx = 0;
            int n = ruleStr.length();

            while (true) {
                Rule r = new Rule(null, new ArrayList<>());
                ParseResult<Atom> headRes = parseAtom(ruleStr, idx);
                Atom head = headRes.value;
                idx = headRes.idx;

                if (head == null) break;

                if (head.terms.size() > maxArity) maxArity = head.terms.size();
                r.head = head;

                if (idx < n - 1) {
                    ParseResult<Character> c1 = nextToken(ruleStr, idx);
                    idx = c1.idx + 1;
                    ParseResult<Character> c2 = nextToken(ruleStr, idx);
                    idx = c2.idx + 1;

                    if (c1.value != null && c2.value != null && c1.value == ':' && c2.value == '-') {
                        List<Atom> body = new ArrayList<>();
                        while (true) {
                            ParseResult<Atom> factRes = parseAtom(ruleStr, idx);
                            Atom fact = factRes.value;
                            idx = factRes.idx;

                            if (fact == null) throw new RuntimeException("Expected body fact, but found EOF");

                            if (fact.terms.size() > maxArity) maxArity = fact.terms.size();
                            body.add(fact);

                            if (idx < n) {
                                ParseResult<Character> sep = nextToken(ruleStr, idx);
                                idx = sep.idx + 1;
                                if (sep.value != null && sep.value == '.') {
                                    break;
                                } else if (idx >= n || sep.value == null || sep.value != ',') {
                                    throw new RuntimeException("Expected comma separator after fact");
                                }
                            }
                        }
                        r.body = body;
                        if (r.body.size() > maxBody) maxBody = r.body.size();
                        rules.add(r);
                        break;
                    } else {
                        throw new RuntimeException("Expected rule separator");
                    }
                } else {
                    throw new RuntimeException("Expected rule separator");
                }
            }
        }

        return new ProgramParse(rules, maxArity, maxBody);
    }

    private static final class EdgeRulesResult {
        final List<Rule> edgeRules;
        final String goalPredicate;
        EdgeRulesResult(List<Rule> edgeRules, String goalPredicate) {
            this.edgeRules = edgeRules;
            this.goalPredicate = goalPredicate;
        }
    }

    private static EdgeRulesResult createEdgeRules(List<String> program, Atom goal) {
        ProgramParse pp = parseProgram(program);
        List<Rule> rules = pp.rules;
        int maxArity = pp.maxArity;
        int maxBody = pp.maxBody;

        int goalArity = 2 + (maxBody + 1) * (maxArity + 1);

        List<String> curNodeTuple = new ArrayList<>();
        curNodeTuple.add(goal.predicate);
        curNodeTuple.addAll(goal.terms);
        while (curNodeTuple.size() < 1 + maxArity) curNodeTuple.add("nil"); // pad terms part

        Rule curNodeFact = new Rule(new Atom("dcnode", curNodeTuple), null);

        List<Rule> edgeRules = new ArrayList<>();
        edgeRules.add(curNodeFact);

        for (Rule rule : rules) {
            Atom head = rule.head;
            List<Atom> newBody = (rule.body == null) ? new ArrayList<>() : new ArrayList<>(rule.body);

            Atom edgeAtom = new Atom("dcedge", new ArrayList<>());
            edgeAtom.terms.add(String.valueOf(maxArity));
            edgeAtom.terms.add(String.valueOf(maxBody));

            edgeAtom.terms.add(head.predicate);
            edgeAtom.terms.addAll(head.terms);
            while (edgeAtom.terms.size() < 2 + 1 + maxArity) edgeAtom.terms.add("nil");

            Atom curNodeAtom = new Atom("dcnode", new ArrayList<>());
            curNodeAtom.terms.add(head.predicate);
            curNodeAtom.terms.addAll(head.terms);
            while (curNodeAtom.terms.size() < 1 + maxArity) curNodeAtom.terms.add("nil");
            newBody.add(0, curNodeAtom);

            for (int bodyId = 0; bodyId < maxBody; bodyId++) {
                Atom target = null;
                if (rule.body != null && bodyId < rule.body.size()) target = rule.body.get(bodyId);

                if (target != null) {
                    edgeAtom.terms.add(target.predicate);
                    edgeAtom.terms.addAll(target.terms);
                    while (edgeAtom.terms.size() % (maxArity + 1) != 0) edgeAtom.terms.add("nil");
                } else {
                    edgeAtom.terms.add("pnil");
                    for (int k = 0; k < maxArity; k++) edgeAtom.terms.add("nil");
                }

                if (target != null) {
                    Atom nextNodeAtom = new Atom("dcnode", new ArrayList<>());
                    nextNodeAtom.terms.add(target.predicate);
                    nextNodeAtom.terms.addAll(target.terms);
                    while (nextNodeAtom.terms.size() < 1 + maxArity) nextNodeAtom.terms.add("nil");
                    edgeRules.add(new Rule(nextNodeAtom, newBody));
                }
            }

            edgeRules.add(new Rule(edgeAtom, newBody));
        }

        return new EdgeRulesResult(edgeRules, "dcedge/" + goalArity);
    }

    // ---------- DLV calls / grounding ----------
    private static final class ProcResult {
        final int exitCode;
        final String stdout;
        final String stderr;
        ProcResult(int exitCode, String stdout, String stderr) {
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
        }
    }

    private static ProcResult runProcess(List<String> cmd, String input) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        Process p = pb.start();

        // write stdin
        try (OutputStream os = p.getOutputStream()) {
            if (input != null) os.write(input.getBytes(StandardCharsets.UTF_8));
        }

        String stdout;
        String stderr;

        try (InputStream is = p.getInputStream();
             InputStream es = p.getErrorStream()) {
            stdout = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            stderr = new String(es.readAllBytes(), StandardCharsets.UTF_8);
        }

        int code = p.waitFor();
        return new ProcResult(code, stdout, stderr);
    }

    private static void ground(String dbfile, String progfile, String query, String outGroundingFile)
            throws IOException, InterruptedException {

        List<String> programLines = Files.readAllLines(Paths.get(progfile), StandardCharsets.UTF_8);
        List<String> programWithoutQuery = new ArrayList<>();
        for (String line : programLines) {
            String t = line.trim();
            if (!t.isEmpty() && !t.endsWith("?")) programWithoutQuery.add(line);
        }

        // Normalize query for ASP tool
        query = normalizeQueryText(query);

        List<String> programWithGoal = new ArrayList<>(programWithoutQuery);
        programWithGoal.add(query + "?");
        String programWithGoalStr = String.join("\n", programWithGoal);

        // arity = commas + 1
        int arity = 1;
        for (int i = 0; i < query.length(); i++) if (query.charAt(i) == ',') arity++;
        String bstring = "b".repeat(Math.max(0, arity));
        String fstring = "f".repeat(Math.max(0, arity));

        // First dlv run: stderr contains rewriting
        List<String> cmd1 = List.of(
                DLV_PATH.toString(),
                "--silent", "--no-projection", "--print-rewriting", "--stdin"
        );
        ProcResult dlv1 = runProcess(cmd1, programWithGoalStr);
        String magicRewRaw = dlv1.stderr;

        magicRewRaw = magicRewRaw.replace("#", "");
        List<String> magicRewProgramLines = new ArrayList<>();
        for (String line : magicRewRaw.split("\\R")) {
            if (!line.startsWith("-")) magicRewProgramLines.add(line);
        }

        ProgramParse magicParsed = parseProgram(magicRewProgramLines);
        List<Rule> magicRewProgram = new ArrayList<>(magicParsed.rules);

        Atom goalAtom = parseAtom(query, 0).value;
        if (goalAtom == null) throw new RuntimeException("Failed to parse goal atom from query: " + query);

        Rule magicGoalB = new Rule(new Atom("magic" + goalAtom.predicate + bstring, goalAtom.terms), null);
        Rule magicGoalF = new Rule(new Atom("magic" + goalAtom.predicate + fstring, Collections.emptyList()), null);

        magicRewProgram.add(0, magicGoalF);
        magicRewProgram.add(0, magicGoalB);

        // Edge rules
        EdgeRulesResult er = createEdgeRules(programWithoutQuery, goalAtom);
        magicRewProgram.addAll(er.edgeRules);

        StringBuilder finalProgram = new StringBuilder();
        for (Rule r : magicRewProgram) finalProgram.append(r.toString()).append("\n");
        finalProgram.append("\n");

        // Second dlv run: write stdout to outGroundingFile
        List<String> cmd2 = new ArrayList<>();
        cmd2.add(DLV_PATH.toString());
        cmd2.add("--filter=" + er.goalPredicate);
        cmd2.add("--silent");
        cmd2.add(dbfile);
        cmd2.add("--stdin");

        ProcessBuilder pb = new ProcessBuilder(cmd2);
        Process p = pb.start();

        // feed finalProgram to stdin
        try (OutputStream os = p.getOutputStream()) {
            os.write(finalProgram.toString().getBytes(StandardCharsets.UTF_8));
        }

        // stream stdout into file
        Path outPath = Paths.get(outGroundingFile);
        Files.createDirectories(outPath.getParent());

        try (InputStream is = p.getInputStream();
             OutputStream fos = Files.newOutputStream(outPath, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            is.transferTo(fos);
        }

        // read stderr
        String stderr;
        try (InputStream es = p.getErrorStream()) {
            stderr = new String(es.readAllBytes(), StandardCharsets.UTF_8);
        }

        int code = p.waitFor();
        if (!stderr.isBlank()) {
            System.out.println(stderr);
        }
        if (code != 0) {
            throw new RuntimeException("dlv2 failed with exit code " + code + " for query: " + query);
        }
    }

    // ---------- Main ----------
    public static void main(String[] args) throws Exception {
        int argc = args.length;
        if (argc != 2 && argc != 3) {
            System.out.println("Usage: java GroundDownward <scenario name> <out dir> [database name]");
            return;
        }

        String scenarioName = args[0];
        String baseOutDir = args[1];

        String scenarioDir = baseOutDir + "/datasets/" + scenarioName;
        String progfile = scenarioDir + "/program.lp";
        String queryDir = scenarioDir + "/queries/";
        String groundingDir = baseOutDir + "/downward/" + scenarioName;

        Files.createDirectories(Paths.get(groundingDir));
        Files.createDirectories(Paths.get(baseOutDir, "times"));

        List<String> queryFiles = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(Paths.get(queryDir), "*.txt")) {
            for (Path p : ds) queryFiles.add(p.getFileName().toString());
        }

        if (argc == 3) {
            queryFiles = List.of(args[2] + ".txt");
        }

        Path groundTimesPath = Paths.get(baseOutDir, "times", "ground_times.tsv");
        try (BufferedWriter bw = Files.newBufferedWriter(groundTimesPath, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
             PrintWriter tsv = new PrintWriter(bw)) {

            for (String qfile : queryFiles) {
                String dbname = qfile.replaceFirst("\\.txt$", "");
                String dbfile = scenarioDir + "/databases/" + dbname + ".facts";

                System.out.println("The database is: " + dbname);

                Path qfileFull = Paths.get(queryDir, qfile);
                List<String> queryData = new ArrayList<>();
                for (String line : Files.readAllLines(qfileFull, StandardCharsets.UTF_8)) {
                    if (!line.trim().isEmpty()) queryData.add(line);
                }

                for (String rawQuery : queryData) {
                    System.out.println("  Grounding with query: " + rawQuery);

                    String safeDb = slugify(dbname, 80);
                    String safeQ = slugify(rawQuery, 140);
                    Path groundingFile = Paths.get(groundingDir, safeDb + "_" + safeQ + ".lp");

                    if (Files.exists(groundingFile)) {
                        Files.delete(groundingFile);
                    }

                    Instant start = Instant.now();
                    String normQuery = normalizeQueryText(rawQuery);
                    ground(dbfile, progfile, normQuery, groundingFile.toString());
                    Instant end = Instant.now();

                    long elapsedMs = Duration.between(start, end).toMillis();

                    // TSV row: (scenarioName, dbname, rawQuery, elapsed)
                    tsv.println(String.join("\t",
                            scenarioName,
                            dbname,
                            rawQuery.replace("\t", " "), // avoid breaking TSV
                            String.valueOf(elapsedMs)
                    ));
                    tsv.flush();
                }
            }
        }
    }
}
