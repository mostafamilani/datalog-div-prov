#include "solver_utils.h"

#include <cassert>

int dexp::axiomatize_xor(dexp::solver& solver, const std::vector<int>& literals){
    assert(literals.size() > 0);

    if(literals.size() == 1){
        return literals[0];
    }

    auto base_variable = literals[0];
    for(size_t i = 1; i < literals.size(); i++){
        auto aux_variable = solver.new_var();

        // Axiomatize base_variable xor literals[i] = aux_variable (a xor b = c)
        // (a∨b∨¬c)∧(a∨¬b∨c)∧(¬a∨b∨c)∧(¬a∨¬b∨¬c)
        solver.add_clause({base_variable,literals[i],-aux_variable});
        solver.add_clause({base_variable,-literals[i],aux_variable});
        solver.add_clause({-base_variable,literals[i],aux_variable});
        solver.add_clause({-base_variable,-literals[i],-aux_variable});
        base_variable = aux_variable;
    }

    return base_variable;
}