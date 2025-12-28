#ifdef WHYSAT_ENABLE_EVAL_MAX_SAT

#include "solver.h"
#include "solver_utils.h"
#include <EvalMaxSAT.h>


    dexp::solver::solver(){
        m_solver.setIncremental();
    }

    dexp::solver::~solver(){ }

    void dexp::solver::add_clause(const std::vector<int>& clause, std::optional<uint64_t> weight) {
        m_solver.addClause(clause,weight);
        m_total_weight += weight.value_or(0);
    }

    void dexp::solver::add_xor_clause(const std::vector<int>& clause, std::optional<uint64_t> weight) {
        if(clause.size() == 0 || (weight.has_value() && weight.value() == 0)) {
            return;
        }

        if(clause.size() == 1){
            m_solver.addClause(clause, weight);
        }else if(clause.size() == 2){
            m_solver.addClause(clause,weight);
            m_solver.addClause({-clause[0],-clause[1]},weight);
        }else{
            auto aux_variable = dexp::axiomatize_xor(*this,clause);
            m_solver.addClause({aux_variable},weight);
        }

        m_total_weight += weight.value_or(0);
    }

    int dexp::solver::new_var(){
        return m_solver.newVar();
    }

    size_t dexp::solver::num_vars(){
        return m_solver.nVars();
    }

    bool dexp::solver::solve(){
        return m_solver.solve();
    }

    uint64_t dexp::solver::obj_value(){
        return m_total_weight - m_solver.getCost();
    }

    bool dexp::solver::lit_value(int lit){
        return m_solver.getValue(lit);
    }
#endif