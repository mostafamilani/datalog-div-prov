import java.nio.file.*;
import java.util.*;

public class BatchRunner {

    private static final long PROCESS_TIME_OUT = 240;
//    private static final long PROCESS_TIME_OUT = 15;

    /**
     * Updated Usage:
     *   java org.datalog.BatchRunner <inputFolder> <outputFolder> <mode:SAT|LP> [numModels=0] [numInitialModels=2] [print=false] [extraAnalysis=false]
     *
     * Behavior:
     * - Walks <inputFolder> recursively and finds all *.lp files.
     * - Mirrors the relative subdirectory structure from input to output:
     *     input:  <inputFolder>/.../X.lp
     *     output: <outputFolder>/.../   (same relative parent path)
     * - For each input file, spawns a JVM running either:
     *     org.datalog.SATModelEnumerator  (mode=SAT)
     *     LPModelEnumerator              (mode=LP)   <-- keep as you had it
     *
     * Invoked as:
     *   <EnumeratorClass> <inputFile> <outDirForThisFile> <numModels> <numInitialModels> <print> <extraAnalysis>
     */
    public static void main(String[] args) {
        System.out.println("Updated on 2015-12-3 9:51! Press Enter to continue...");
        new java.util.Scanner(System.in).nextLine();

        if (args.length < 3) {
            System.err.println("Usage: java org.datalog.BatchRunner <inputFolder> <outputFolder> <mode:SAT|LP> [numModels=0] [numInitialModels=2] [print=false] [extraAnalysis=false]");
            System.exit(1);
        }

        final Path inputFolder  = Paths.get(args[0]).toAbsolutePath().normalize();
        final Path outputFolder = Paths.get(args[1]).toAbsolutePath().normalize();
        final String modeRaw    = args[2];

        final Mode mode = parseMode(modeRaw);

        final int numModels        = (args.length >= 4 ? parseIntArg(args[3], 0) : 0);   // 0 = unlimited
        final int numInitialModels = (args.length >= 5 ? parseIntArg(args[4], 2) : 2);
        final boolean printModels  = (args.length >= 6 ? parseBoolArg(args[5], false) : false);
        final boolean extraAnalysis= (args.length >= 7 ? parseBoolArg(args[6], false) : false);

        if (!Files.isDirectory(inputFolder)) {
            System.err.println("Not a directory (inputFolder): " + inputFolder);
            System.exit(2);
        }

        // Ensure output folder exists
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

        System.out.println("[BatchRunner] Scanning input : " + inputFolder);
        System.out.println("[BatchRunner] Output root    : " + outputFolder);
        System.out.println("[BatchRunner] Mode           : " + mode + " (" + enumeratorClass + ")");
        System.out.println("[BatchRunner] numModels=" + numModels + " (0 means unlimited), numInitialModels=" + numInitialModels
                + ", print=" + printModels + ", extraAnalysis=" + extraAnalysis);

        Set<Object> seenKeys = new HashSet<>();
        Set<String> seenRealPaths = new HashSet<>();

        try (java.util.stream.Stream<Path> walk = Files.walk(inputFolder)) {
            int processed = 0, skipped = 0;

            Iterator<Path> it = walk.iterator();
            while (it.hasNext()) {
                Path lpFile = it.next();

                // Only consider *.lp files (case-insensitive)
                String name = lpFile.getFileName().toString();
                if (!name.toLowerCase().endsWith(".lp")) continue;

                try {
                    // Only regular files
                    if (!Files.isRegularFile(lpFile, LinkOption.NOFOLLOW_LINKS)) {
                        skipped++;
                        continue;
                    }

                    // Resolve identity & canonical path
                    java.nio.file.attribute.BasicFileAttributes attrs =
                            Files.readAttributes(lpFile,
                                    java.nio.file.attribute.BasicFileAttributes.class,
                                    LinkOption.NOFOLLOW_LINKS);
                    Object key = attrs.fileKey();
                    Path real = lpFile.toRealPath(LinkOption.NOFOLLOW_LINKS);

                    // Skip if we've already seen this physical file (handles aliases/symlinks/8.3 names)
                    boolean isDup = (key != null && !seenKeys.add(key))
                            || (key == null && !seenRealPaths.add(real.toString()));
                    if (isDup) {
                        System.out.println("[INFO] Skipping duplicate alias: " + lpFile);
                        skipped++;
                        continue;
                    }

                    // Mirror relative structure: outDirForThisFile = outputFolder / relativePathParent
                    Path rel = inputFolder.relativize(real);
                    Path relParent = (rel.getParent() == null ? Paths.get("") : rel.getParent());
                    Path outDirForThisFile = outputFolder.resolve(relParent).normalize();

                    // Ensure per-file output dir exists
                    Files.createDirectories(outDirForThisFile);

                    // Forward args to the chosen Enumerator:
                    //   <downwardFile> <outDir> <numModels> <numInitialModels> <print> <extraAnalysis>
                    List<String> forwarded = new ArrayList<>();
                    forwarded.add(real.toString());
                    forwarded.add(outDirForThisFile.toString());
                    forwarded.add(String.valueOf(numModels));
                    forwarded.add(String.valueOf(numInitialModels));
                    forwarded.add(String.valueOf(printModels));
                    forwarded.add(String.valueOf(extraAnalysis));

                    try {
                        List<String> cmd = new ArrayList<>();
                        String javaBin = System.getProperty("java.home")
                                + java.io.File.separator + "bin" + java.io.File.separator
                                + (System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java");
                        cmd.add(javaBin);
                        cmd.add("-cp");
                        cmd.add(System.getProperty("java.class.path"));
                        cmd.add(enumeratorClass);
                        cmd.addAll(forwarded);

                        System.out.println("[CMD] " + String.join(" ", cmd));
                        System.out.println("[OUT] " + outDirForThisFile);

                        Process p = new ProcessBuilder(cmd)
                                .inheritIO()
                                .start();

                        boolean finished = p.waitFor(PROCESS_TIME_OUT,
                                java.util.concurrent.TimeUnit.MINUTES);

                        int exitCode;
                        if (!finished) {
                            // Kill descendants first (Java 9+), then the process itself
                            try {
                                java.lang.ProcessHandle ph = p.toHandle();
                                ph.descendants().forEach(h -> {
                                    try { h.destroyForcibly(); } catch (Throwable ignored) {}
                                });
                            } catch (Throwable ignored) {}
                            p.destroyForcibly();
                            p.waitFor();
                            exitCode = 124; // timeout
                        } else {
                            exitCode = p.exitValue();
                        }

                        if (exitCode != 0) {
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
        return Mode.SAT; // unreachable
    }

    private static int parseIntArg(String s, int defVal) {
        try { return Integer.parseInt(s); } catch (Exception e) { return defVal; }
    }

    private static boolean parseBoolArg(String s, boolean defVal) {
        if (s == null) return defVal;
        String v = s.trim().toLowerCase(Locale.ROOT);
        if (v.equals("true") || v.equals("1") || v.equals("yes") || v.equals("y")) return true;
        if (v.equals("false") || v.equals("0") || v.equals("no") || v.equals("n")) return false;
        return defVal;
    }
}
