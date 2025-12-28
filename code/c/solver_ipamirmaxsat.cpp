#include "solver.h"

#ifdef WHYSAT_IPAMIR_MAX_SAT

#include "solver_utils.h"

#include <ipamir.h>
#include <algorithm>
#include <cassert>

dexp::solver::solver(){
    m_solver = ipamir_init();
}

dexp::solver::~solver(){
    ipamir_release(m_solver);
}

void dexp::solver::add_clause(const std::vector<int>& clause, std::optional<uint64_t> weight) {
    if(clause.size() == 0 || (weight.has_value() && weight.value() == 0)) {
        return;
    }

    for(int lit : clause){
        assert(m_num_vars >= std::abs(lit));
    }

    if(!weight.has_value()) {
        for(int lit : clause){
            ipamir_add_hard(m_solver, lit);
        }
        ipamir_add_hard(m_solver, 0);
    }else if(clause.size() == 1){
        ipamir_add_soft_lit(m_solver, -clause[0],weight.value());
    }else{
        for(int lit : clause){
            ipamir_add_hard(m_solver, lit);
        }
        auto aux_var = this->new_var();
        ipamir_add_hard(m_solver, aux_var);
        ipamir_add_hard(m_solver, 0);
        ipamir_add_soft_lit(m_solver,aux_var,weight.value());            
    }
}

void dexp::solver::add_xor_clause(const std::vector<int>& clause, std::optional<uint64_t> weight) {
    if(clause.size() == 0 || (weight.has_value() && weight.value() == 0)) {
        return;
    }

    if(clause.size() == 1){
        this->add_clause(clause, weight);
    }else if(clause.size() == 2){
        this->add_clause(clause,weight);
        this->add_clause({-clause[0],-clause[1]},weight);
        m_weight_slope -= weight.value_or(0);
    }else{
        auto aux_variable = dexp::axiomatize_xor(*this,clause);
        this->add_clause({aux_variable},weight);
    }
}

int dexp::solver::new_var(){
    return ++m_num_vars;
}

size_t dexp::solver::num_vars(){
    return m_num_vars;
}

bool dexp::solver::solve(){
    auto result = ipamir_solve(m_solver);
    return result == 10 || result == 30;
}

uint64_t dexp::solver::obj_value(){
    return ipamir_val_obj(m_solver) - m_weight_slope;
}

bool dexp::solver::lit_value(int lit){
    return ipamir_val_lit(m_solver,lit) == lit;
}

#endif
