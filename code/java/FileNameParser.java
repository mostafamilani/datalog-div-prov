import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

public final class FileNameParser {
    private FileNameParser() {}

    // Map arity for the doctors_* predicates
    private static final Map<String, Integer> DOCTORS_ARITY = Map.of(
            "q01", 1,
            "q02", 3,
            "q03", 2,
            "q04", 2,
            "q05", 3,
            "q06", 2,
            "q07", 2
    );

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String baseNameNoExt(String path) {
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        String name = (slash >= 0) ? path.substring(slash + 1) : path;
        int dot = name.lastIndexOf('.');
        return (dot >= 0 ? name.substring(0, dot) : name);
    }

    private static String joinQuoted(List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) sb.append(',');
            String s = esc(parts.get(i));
            boolean isNum = s.matches("\\d+");
            if (isNum) {
                sb.append(s);
            } else {
                sb.append('"').append(s).append('"');
            }
        }
        return sb.toString();
    }

    /**
     * Extract a goal string like subClassOf("A","B") or q02("x","y","z")
     * from a dataset filename following one of the known patterns.
     */
    public static String goalFromFilename(String filePath) {
        final String base = baseNameNoExt(filePath);

        // ---------- 1) doctors_qNN ----------
        // Examples:
        //   100k_q01_WOrh11181             -> q01(WOrh11181)
        //   100k_q02_DASAMBYFTH_PEWHZYJREB_PMQR49496 -> q02(DASAMBYFTH,PEWHZYJREB,PMQR49496)
        //   100k_q03_115176_IEDGQCKHRI     -> q03(115176,IEDGQCKHRI)
        // Pattern: ..._qNN_<arg1>[_<arg2>[_<arg3>...]]
        {
            Matcher m = Pattern.compile(".*_q(0[1-7])_(.+)$").matcher(base);
            if (m.matches()) {
                String qid = "q" + m.group(1);
                String tail = m.group(2);                 // the arguments part
                int arity = DOCTORS_ARITY.getOrDefault(qid, -1);

                List<String> toks = new ArrayList<>(Arrays.asList(tail.split("_")));
                if (arity > 0) {
                    // If more tokens than arity, merge extras into the last argument just in case.
                    if (toks.size() > arity) {
                        List<String> fixed = new ArrayList<>();
                        // take first (arity-1) as-is
                        for (int i = 0; i < arity - 1; i++) fixed.add(toks.get(i));
                        // join the rest as the last argument
                        StringBuilder last = new StringBuilder();
                        for (int i = arity - 1; i < toks.size(); i++) {
                            if (i > arity - 1) last.append('_');
                            last.append(toks.get(i));
                        }
                        fixed.add(last.toString());
                        toks = fixed;
                    }
                    // If fewer tokens than arity, we still format what we have.
                }
                return qid + "(" + joinQuoted(toks) + ")";
            }
        }

        // ---------- 2) galen subClassOf ----------
        // Examples:
        //   50_subClassOf_AnteriorSpinalNerveRoot_NAMEDNervousSystemPart  -> subClassOf(A, B)
        //   50_subClassOf_Erythromycin_9_1_970                             -> subClassOf(Erythromycin, 9_1_970)
        // Pattern: <count>_subClassOf_<arg1>_<arg2...>   (arg2 may contain underscores)
        {
            Matcher m = Pattern.compile("^(\\d+)_subClassOf_(.+)$").matcher(base);
            if (m.matches()) {
                String tail = m.group(2);
                String[] split = tail.split("_", 2); // limit=2 so arg2 keeps underscores
                if (split.length == 2) {
                    String a1 = split[0];
                    String a2 = split[1];
                    return "subClassOf(" + joinQuoted(List.of(a1, a2)) + ")";
                }
            }
        }

        // ---------- 3) graph_analytics path ----------
        // Examples:
        //   facebook_path_942_1755        -> path(942,1755)
        //   bitcoin_path_203609045_250223024 -> path(203609045,250223024)
        // Pattern: <prefix>_path_<arg1>_<arg2>   (numbers or tokens; assume exactly two args)
        {
            Matcher m = Pattern.compile(".*_path_([^_]+)_(.+)$").matcher(base);
            if (m.matches()) {
                String a1 = m.group(1);
                String a2 = m.group(2);
                return "path(" + joinQuoted(List.of(a1, a2)) + ")";
            }
        }

        // ---------- 4) csda null ----------
        // Example: httpd_null_3742745_3720919 -> null(3742745,3720919)
        {
            Matcher m = Pattern.compile(".*_null_([^_]+)_(.+)$").matcher(base);
            if (m.matches()) {
                String a1 = m.group(1);
                String a2 = m.group(2);
                return "null(" + joinQuoted(List.of(a1, a2)) + ")";
            }
        }

        // ---------- 5) anderson pointsTo ----------
        // Assumed pattern similar to csda:
        //   <prefix>_pointsTo_<arg1>_<arg2> -> pointsTo(arg1,arg2)
        {
            Matcher m = Pattern.compile(".*_pointsTo_([^_]+)_(.+)$").matcher(base);
            if (m.matches()) {
                String a1 = m.group(1);
                String a2 = m.group(2);
                return "pointsTo(" + joinQuoted(List.of(a1, a2)) + ")";
            }
        }

        // ---------- 6) Generic fallback ----------
        // Pattern: <something>_<predicate>_<arg1>_<arg2...>
        {
            Matcher m = Pattern.compile(".*_([A-Za-z][A-Za-z0-9]*)_(.+)$").matcher(base);
            if (m.matches()) {
                String pred = m.group(1);
                String tail = m.group(2);
                // Heuristic: if more than 2 tokens, keep all but it's common that arg2 may contain underscores.
                // We'll prefer (<arg1>, <rest-joined>).
                String[] split = tail.split("_", 2);
                if (split.length == 2) {
                    String a1 = split[0];
                    String aN = split[1]; // may contain underscores
                    return pred + "(" + joinQuoted(List.of(a1, aN)) + ")";
                } else {
                    // no underscore in tail -> unary predicate
                    return pred + "(" + joinQuoted(List.of(tail)) + ")";
                }
            }
        }

        // If nothing matched, return null or throw — here we return null to let caller decide.
        return null;
    }
}
