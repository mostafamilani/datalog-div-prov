#pragma once

#include "solver.h"

namespace dexp{
    /**
        Adds to the given solver a set of or clauses that simulate the xor of the literals
        in the given vector. and returns a variable that is true iff the xor is true 
        in the formula. 

        It is important to note that the function does *not* enforce the xor in the formula,
        but it only prepares the formula so that the returned variable is true in the formula
        iff the xor is true.

        The function issues an assert if the vector of literals is empty, while when the
        vector contains only one variable, it simply returns the variable itself, and no
        or clauses are added. For more than one variable, the functions adds the necessary
        or clauses, and returns a fresh auxiliary variable with the above property.
     */
    int axiomatize_xor(dexp::solver& solver, const std::vector<int>& literals);
}