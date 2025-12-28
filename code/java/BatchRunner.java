package org.datalog;

import java.io.File;
import java.io.FilenameFilter;
import java.nio.file.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class SATBatchRunner {

    private static final long PROCESS_TIME_OUT = 240;
//    private static final long PROCESS_TIME_OUT = 15;

    /**
     * Usage:
     *   java org.datalog.SATBatchRunner <folder> [numModels=0] [numInitialModels=2] [print=false]
     *
     * Examples:
     *   java org.datalog.SATBatchRunner /path/to/downward 0 2 false
     *   java org.datalog.SATBatchRunner /path/to/downward 10 3 true
     *
     * For each .lp file such as:
     *   15_subClassOf_AdrenalineHydrochloride_SubstanceOrPhysicalStructure.lp
     *   15_subClassOf_Albumin_23_1_10075.lp
     *
     * We generate goals:
     *   subClassOf("AdrenalineHydrochloride","SubstanceOrPhysicalStructure")
     *   subClassOf("Albumin","23_1_10075")
     *
     * Then invoke:
     *   SATModelEnumerator.main(new String[]{downwardFile, goal, String.valueOf(numModels), String.valueOf(numInitial), String.valueOf(print)});
     */
    public static void main(String[] args) {
        System.out.println("Updated on 2015-12-3 9:51! Press Enter to continue...");
        new java.util.Scanner(System.in).nextLine();
        if (args.length < 1) {
            System.err.println("Usage: java org.datalog.SATBatchRunner <folder> [numModels=0] [numInitialModels=2] [print=false]");
            System.exit(1);
        }

        final Path folder = Paths.get(args[0]);
        final int numModels        = (args.length >= 2 ? parseIntArg(args[1], 0) : 0);   // 0 = unlimited
        final int numInitialModels = (args.length >= 3 ? parseIntArg(args[2], 2) : 2);
        final boolean printModels  = (args.length >= 4 ? parseBoolArg(args[3], false) : false);
        final boolean extraAnalysis  = (args.length >= 5 ? parseBoolArg(args[4], false) : false);

        if (!Files.isDirectory(folder)) {
            System.err.println("Not a directory: " + folder.toAbsolutePath());
            System.exit(2);
        }

        System.out.println("[SATBatchRunner] Scanning: " + folder.toAbsolutePath());
        System.out.println("[SATBatchRunner] numModels=" + numModels + " (0 means unlimited), numInitialModels=" + numInitialModels + ", print=" + printModels);

        java.util.Set<Object> seenKeys = new java.util.HashSet<>();
        java.util.Set<String> seenRealPaths = new java.util.HashSet<>();

        try (java.util.stream.Stream<Path> walk = java.nio.file.Files.walk(folder)) {
            int processed = 0, skipped = 0;

            java.util.Iterator<Path> it = walk.iterator();
            while (it.hasNext()) {
                Path lpFile = it.next();

                // Only consider *.lp files (case-insensitive)
                String name = lpFile.getFileName().toString();
                if (!name.toLowerCase().endsWith(".lp")) continue;

                try {
                    // Only regular files
                    if (!java.nio.file.Files.isRegularFile(lpFile, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                        skipped++;
                        continue;
                    }

                    // Resolve identity & canonical path
                    java.nio.file.attribute.BasicFileAttributes attrs =
                            java.nio.file.Files.readAttributes(lpFile,
                                    java.nio.file.attribute.BasicFileAttributes.class,
                                    java.nio.file.LinkOption.NOFOLLOW_LINKS);
                    Object key = attrs.fileKey();
                    Path real = lpFile.toRealPath(java.nio.file.LinkOption.NOFOLLOW_LINKS);

                    // Skip if we've already seen this physical file (handles aliases/symlinks/8.3 names)
                    boolean isDup = (key != null && !seenKeys.add(key))
                            || (key == null && !seenRealPaths.add(real.toString()));
                    if (isDup) {
                        System.out.println("[INFO] Skipping duplicate alias: " + lpFile);
                        skipped++;
                        continue;
                    }

                    final String fileName = real.getFileName().toString();
                    final String baseName = stripExtension(fileName);

                    // Reuse the existing main of SATModelEnumerator (spawn new JVM)
                    java.util.List<String> forwarded = new java.util.ArrayList<>();
                    forwarded.add(real.toString());
                    forwarded.add(String.valueOf(numModels));
                    forwarded.add(String.valueOf(numInitialModels));
                    forwarded.add(String.valueOf(printModels));
                    forwarded.add(String.valueOf(extraAnalysis));

                    try {
                        Object[] objects = forwarded.toArray();
                        String[] newArgs = new String[objects.length];
                        for (int i = 0; i < objects.length; i++) {
                            Object object = objects[i];
                            newArgs[i] = object.toString();
                        }
//                        SATModelEnumerator.main(newArgs);
                        java.util.List<String> cmd = new java.util.ArrayList<>();
                        String javaBin = System.getProperty("java.home")
                                + java.io.File.separator + "bin" + java.io.File.separator
                                + (System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java");
                        cmd.add(javaBin);
                        cmd.add("-cp");
                        cmd.add(System.getProperty("java.class.path"));
                        cmd.add("org.datalog.SATModelEnumerator"); // fully-qualified class name
                        cmd.addAll(forwarded);

                        System.out.println("[CMD] " + String.join(" ", cmd));

                        Process p = new ProcessBuilder(cmd)
                                .inheritIO()          // stream child's stdout/stderr to this process
                                .start();

                        boolean finished = p.waitFor(PROCESS_TIME_OUT,
                                java.util.concurrent.TimeUnit.MINUTES);

                        int exitCode;
                        if (!finished) {
                            p.destroyForcibly();
                            p.waitFor();
                            exitCode = 124; // timeout
                        } else {
                            exitCode = p.exitValue();
                        }

                        if (exitCode != 0) {
                            throw new RuntimeException("SATModelEnumerator exited with code " + exitCode);
                        }

                        processed++;
                    } catch (Throwable t) {
                        System.err.println("[ERROR] Failure running SATModelEnumerator for " + fileName + ": " + t.getMessage());
                        t.printStackTrace(System.err);
                    }
                } catch (java.io.IOException ioe) {
                    System.err.println("[WARN] Skipping unreadable entry: " + lpFile + " (" + ioe.getMessage() + ")");
                    skipped++;
                }
            }

            System.out.println("[SATBatchRunner] Done. Processed=" + processed + ", Skipped=" + skipped);
        } catch (java.io.IOException e) {
            System.err.println("[ERROR] Failed to walk folder: " + e.getMessage());
            System.exit(3);
        }

    }

    // ----- Helpers -----

    private static int parseIntArg(String s, int defVal) {
        try { return Integer.parseInt(s); } catch (Exception e) { return defVal; }
    }

    private static boolean parseBoolArg(String s, boolean defVal) {
        if (s == null) return defVal;
        String v = s.trim().toLowerCase();
        if (v.equals("true") || v.equals("1") || v.equals("yes") || v.equals("y")) return true;
        if (v.equals("false") || v.equals("0") || v.equals("no") || v.equals("n")) return false;
        return defVal;
    }

    private static String stripExtension(String name) {
        int i = name.lastIndexOf('.');
        return (i >= 0 ? name.substring(0, i) : name);
    }

    private static String escapeForQuotedArg(String s) {
        // Escape embedded double quotes for safety; leave underscores/digits/letters as-is.
        return s.replace("\"", "\\\"");
    }

    private static String buildGoal(String predicate, String arg1, String arg2) {
        // Always quote both args to match your Python-produced queries.
        String a1 = escapeForQuotedArg(arg1);
        String a2 = escapeForQuotedArg(arg2);
        return predicate + "(\"" + a1 + "\",\"" + a2 + "\")";
    }
}

