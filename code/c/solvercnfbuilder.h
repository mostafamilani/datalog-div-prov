#pragma once

#include "cnfbuilder.h"
#include "solver.h"

namespace dexp {

class solvercnfbuilder : public cnfbuilder {

public:

    solvercnfbuilder(): m_builtin(true) {}
    virtual ~solvercnfbuilder() {}

    virtual cnfbuilder& set_cnf_info(size_t num_vars, size_t num_clauses, const std::vector<atom_graph>& components) override{
        while (m_current_solver.num_vars() < num_vars) {
            m_current_solver.new_var();
            for(size_t i = 0; i < m_num_additional_copies; i++){
                m_additional_copy_solver.new_var();
            }
        }

        if(m_builtin){}
    
        return *this;
    }

    virtual cnfbuilder& begin_clause() override {
        if(m_working_on_clause){
            throw std::logic_error("Another clause is already open, please close it first!");
        }

        m_working_on_clause = true;
        m_num_literals_current_clause = 0;
        m_lits.clear();

        return *this;    
    }
    
    virtual cnfbuilder& end_clause() override {
        if(!m_working_on_clause){
        throw std::logic_error("No clause has been opened. Cannot close");
    }

    if(m_num_literals_current_clause == 0){
        throw std::logic_error("Trying to add an empty clause!");
    }

	m_current_solver.add_clause(m_lits);

    size_t num_vars = m_current_solver.num_vars();
    for(size_t i = 0; i < m_num_additional_copies; i++){
        std::vector<int> copied_clause;
        copied_clause.reserve(m_lits.size());

        int slope = i*num_vars;
        for(auto lit : m_lits){
            copied_clause.push_back(lit + (lit > 0?  slope : -slope));
        }
        m_additional_copy_solver.add_clause(copied_clause);
    }
    
    m_working_on_clause = false;

    return *this;
    }

    virtual cnfbuilder& add_pos_literal(int var_id) override {
        while (var_id > m_current_solver.num_vars()) m_current_solver.new_var();
        m_lits.push_back(translate_var_id(var_id));
        m_num_literals_current_clause++;
        return *this;
    }

    virtual cnfbuilder& add_neg_literal(int var_id) override {
        while (var_id > m_current_solver.num_vars()) m_current_solver.new_var();
        m_lits.push_back(-translate_var_id(var_id));
        m_num_literals_current_clause++;
        return *this;
    }
    
    void use_builtin_acyclicity(bool value) { m_builtin = value; }
    void produce_additional_copies(size_t num_copies) { m_num_additional_copies = num_copies; }

    dexp::solver& solver() { return m_current_solver; }
    dexp::solver& additional_copy_solver() { return m_additional_copy_solver; }

    inline int translate_var_id(int var_id, size_t copy_id = 0) { return m_current_solver.num_vars()*copy_id + var_id; }

private:

    std::vector<int> m_lits;

    dexp::solver m_current_solver;
    dexp::solver m_additional_copy_solver;

    bool m_working_on_clause{};
    size_t m_num_literals_current_clause{};

    bool m_builtin{};
    size_t m_num_additional_copies{};
};
}