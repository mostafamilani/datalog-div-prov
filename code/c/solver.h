#pragma once

#include <vector>
#include <optional>
#include <cstdint>

#if defined(WHYSAT_ENABLE_EVAL_MAX_SAT)
    #include <EvalMaxSAT.h>
    #include <glucoseinterface.h>
#elif defined(WHYSAT_IPAMIR_MAX_SAT)
    
#else
    #error "No SAT solver defined"
#endif

namespace dexp {

/**
    Generic interface to a solver. Literals are non-zero positive/negative integers.
    When adding hard clauses, only the specified literals are used, and thus if they
    already occur in the formula, no new variables are introduced.

    When is a new variable created outside the user control?
    - When a soft clause with more than one literal is added.
    - When any xor clause with more than two literals is added.
*/
class solver {
public:
    
    solver();
    ~solver();

    void add_clause(const std::vector<int>& clause, std::optional<uint64_t> weight = {});

    /**
        Adds a xor clause. Regardless of how this is internally implemented, it is guaranteed
        that semantically the added xor clause contributes only the given weight. That is,
        if the xor is implemented with multiple clauses, when finding an optimum solution,
        if the solution satisfies the xor clause, the contribution of the xor clause is
        only the given weight.
    */
    void add_xor_clause(const std::vector<int>& clause, std::optional<uint64_t> weight = {});
    int new_var();
    size_t num_vars();
    bool solve();
    uint64_t obj_value();
    bool lit_value(int lit);

private:
#if defined(WHYSAT_ENABLE_EVAL_MAX_SAT)

#if defined(WHYSAT_EVAL_MAX_SAT_SOLVER_CADICAL)
    typedef Solver_cadical EvalMaxSAT_SolverType;
#elif defined(WHYSAT_EVAL_MAX_SAT_SOLVER_GLUCOSE)
    typedef MonGlucose41 EvalMaxSAT_SolverType;
#else
    #error "No SAT solver for EvalMaxSAT defined"
#endif
    EvalMaxSAT<EvalMaxSAT_SolverType> m_solver;
    uint64_t m_total_weight{};
#elif defined(WHYSAT_IPAMIR_MAX_SAT)
    void * m_solver;
    size_t m_num_vars{};
    uint64_t m_weight_slope{};
#else
#error "No SAT solver enabled"
#endif
};
}