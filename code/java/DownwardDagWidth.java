import de.hu.dagwidth.alg.DAGConstruction;
import de.hu.gralog.graph.DirectedGraph;
import de.hu.gralog.graph.LabeledGraphVertex;
import de.hu.gralog.graph.alg.AlgorithmResult;
import org.jgrapht.graph.DefaultEdge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class DownwardDagWidth {
    private static final String NIL = "nil";
    private static final String PNIL = "pnil";

    private record ExtractedGraph(
            DirectedGraph<LabeledGraphVertex, DefaultEdge> graph,
            int dcedgeAtoms,
            int graphEdges
    ) {}

    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 2) {
            System.err.println("Usage: java DownwardDagWidth <downward.lp> [maxWidth]");
            System.exit(2);
        }

        Path downwardFile = Path.of(args[0]);
        int maxWidth = args.length == 2 ? Integer.parseInt(args[1]) : Integer.MAX_VALUE;

        ExtractedGraph extracted = readDownwardGraph(downwardFile);
        int width = computeDagWidth(extracted.graph(), maxWidth);

        System.out.println("file," + downwardFile);
        System.out.println("dcedge_atoms," + extracted.dcedgeAtoms());
        System.out.println("vertices," + extracted.graph().vertexSet().size());
        System.out.println("edges," + extracted.graphEdges());
        System.out.println("gralog_dag_width," + width);
    }

    private static int computeDagWidth(
            DirectedGraph<LabeledGraphVertex, DefaultEdge> graph,
            int maxWidth
    ) throws Exception {
        if (graph.vertexSet().isEmpty()) {
            return 0;
        }

        int upper = maxWidth == Integer.MAX_VALUE ? graph.vertexSet().size() : maxWidth;
        for (int width = 1; width <= upper; width++) {
            DAGConstruction<LabeledGraphVertex, DefaultEdge> algorithm = new DAGConstruction<>();
            algorithm.setGraph(graph);
            algorithm.setDagWidth(width);
            algorithm.setMaxDAGsCount(1);

            AlgorithmResult result = algorithm.execute();
            if (result.getContentList() != null && !result.getContentList().isEmpty()) {
                return width;
            }
        }
        return -1;
    }

    private static ExtractedGraph readDownwardGraph(Path path) throws IOException {
        String text = Files.readString(path, StandardCharsets.UTF_8).trim();
        if (text.startsWith("{") && text.endsWith("}")) {
            text = text.substring(1, text.length() - 1).trim();
        }

        DirectedGraph<LabeledGraphVertex, DefaultEdge> graph =
                new DirectedGraph<>(LabeledGraphVertex.class);
        Map<String, LabeledGraphVertex> vertices = new HashMap<>();

        int atomCount = 0;
        int edgeCount = 0;
        for (String atom : splitTopLevel(text)) {
            atom = atom.trim();
            if (atom.isEmpty() || !atom.startsWith("dcedge(") || !atom.endsWith(")")) {
                continue;
            }

            List<String> terms = splitTopLevel(atom.substring("dcedge(".length(), atom.length() - 1));
            if (terms.size() < 2) {
                throw new IllegalArgumentException("Malformed dcedge atom in " + path + ": " + atom);
            }

            int maxArity = Integer.parseInt(terms.get(0).trim());
            int maxBody = Integer.parseInt(terms.get(1).trim());
            int tupleSize = maxArity + 1;
            int expected = 2 + (maxBody + 1) * tupleSize;
            if (terms.size() != expected) {
                throw new IllegalArgumentException(
                        "Expected " + expected + " dcedge terms but found " + terms.size()
                                + " in " + path + ": " + atom
                );
            }

            String sourceLabel = atomLabel(terms, 2, maxArity);
            LabeledGraphVertex source = vertex(graph, vertices, sourceLabel);

            int bodyStart = 2 + tupleSize;
            for (int bodyId = 0; bodyId < maxBody; bodyId++) {
                int offset = bodyStart + bodyId * tupleSize;
                String predicate = terms.get(offset).trim();
                if (PNIL.equals(predicate)) {
                    continue;
                }

                String targetLabel = atomLabel(terms, offset, maxArity);
                LabeledGraphVertex target = vertex(graph, vertices, targetLabel);
                if (!graph.containsEdge(source, target)) {
                    graph.addEdge(source, target);
                    edgeCount++;
                }
            }
            atomCount++;
        }

        return new ExtractedGraph(graph, atomCount, edgeCount);
    }

    private static LabeledGraphVertex vertex(
            DirectedGraph<LabeledGraphVertex, DefaultEdge> graph,
            Map<String, LabeledGraphVertex> vertices,
            String label
    ) {
        LabeledGraphVertex existing = vertices.get(label);
        if (existing != null) {
            return existing;
        }

        LabeledGraphVertex created = new LabeledGraphVertex(label);
        graph.addVertex(created);
        vertices.put(label, created);
        return created;
    }

    private static String atomLabel(List<String> terms, int offset, int maxArity) {
        String predicate = terms.get(offset).trim();
        List<String> args = new ArrayList<>();
        for (int i = 1; i <= maxArity; i++) {
            String term = terms.get(offset + i).trim();
            if (!NIL.equals(term)) {
                args.add(term);
            }
        }
        if (args.isEmpty()) {
            return predicate;
        }
        return predicate + "(" + String.join(",", args) + ")";
    }

    private static List<String> splitTopLevel(String text) {
        List<String> parts = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return parts;
        }

        int depth = 0;
        boolean inQuote = false;
        boolean escaped = false;
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (inQuote && c == '\\') {
                escaped = true;
                continue;
            }
            if (c == '"') {
                inQuote = !inQuote;
                continue;
            }
            if (inQuote) {
                continue;
            }

            if (c == '(') {
                depth++;
            } else if (c == ')' && depth > 0) {
                depth--;
            } else if (c == ',' && depth == 0) {
                parts.add(text.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(text.substring(start));
        return parts;
    }
}
