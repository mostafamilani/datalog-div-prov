import org.datalog.DatalogLP;

public class ProbeAcyclicityMetrics {
    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("Usage: ProbeAcyclicityMetrics <lpFile> <goal> [numInitial] [tree]");
            System.exit(2);
        }

        String lpFile = args[0];
        String goal = args[1];
        int numInitial = args.length >= 3 ? Integer.parseInt(args[2]) : 1;
        DatalogLP.ProofTree tree = args.length >= 4 && args[3].equalsIgnoreCase("standard")
                ? DatalogLP.ProofTree.STANDARD
                : DatalogLP.ProofTree.UNAMBIGUOUS;

        DatalogLP lp = new DatalogLP();
        long handle = lp.getLPEncodingWithNumSolverCopiesHandle(
                lpFile,
                goal,
                numInitial,
                DatalogLP.Characterization.PROOF_DAG,
                tree
        );

        System.out.println("after_handle vars=" + lp.getNumVarsAcyclicity(handle));
        System.out.println("after_handle clauses=" + lp.getNumClausesAcyclicity(handle));

        long solver = lp.getLPEncodingSolverHandle(handle);
        long solverCopy = lp.getLPEncodingCopySolverHandle(handle);
        System.out.println("after_solver_handles vars=" + lp.getNumVarsAcyclicity(handle));
        System.out.println("after_solver_handles clauses=" + lp.getNumClausesAcyclicity(handle));
        System.out.println("solver_vars=" + lp.getSolverNumVariables(solver));
        System.out.println("solver_constraints=" + lp.getSolverNumConstraints(solver));
        System.out.println("solver_copy_vars=" + lp.getSolverNumVariables(solverCopy));
        System.out.println("solver_copy_constraints=" + lp.getSolverNumConstraints(solverCopy));

        boolean solved = lp.solve(solverCopy);
        System.out.println("copy_solved=" + solved);
        System.out.println("after_solve vars=" + lp.getNumVarsAcyclicity(handle));
        System.out.println("after_solve clauses=" + lp.getNumClausesAcyclicity(handle));

        lp.freeLPEncoding(handle);
    }
}
