#pragma once

#include "cnfbuilder.h"
#include "types.h"
#include "rule.h"

#include <set>
#include <map>
#include <string>

namespace dexp {

using fact_map = std::map<std::string,int>;
using reverse_fact_map = std::map<int,std::string>;
using db_vars_map = std::map<int,std::string>;

using rules_map = std::map<int,std::set<rule>>;

class sat_encoding {

public:
    enum class acyclicity_mode {
        builtin,
        vertex_elimination,
        transitive_closure
    };

    sat_encoding(cnfbuilder& builder);
    void set_acyclicity_mode(acyclicity_mode mode) { m_acyc_mode = mode; }

    void set_goal(const std::string& goal);
    const db_vars_map& vars_to_db_map() const { return m_db_vars_map; }

    dexp::cnfbuilder& builder() const { return m_builder; }
    
    void build(const pre_graph& graph);

private:

    void add_rule(const std::string& head, const std::set<std::string>& body);

    int add_vertex_if_not_present(const std::string& fact);

    void add_edge_if_not_present(int source, int target);

    int make_edge_var(int edge_id) const{
        return edge_id + 1;
    }

    int make_node_var(int node_id) const{
        return m_last_edge_id + node_id + 1;
    }

    int make_rule_var(int rule_id) const{
        return m_last_edge_id + m_last_id + rule_id + 1;
    }
    
    int make_acyclicity_edge_var(int acyclicity_edge_id) const{
        return m_last_edge_id + m_last_id + m_last_rule_id + acyclicity_edge_id + 1;
    }

    void import_graph(const pre_graph& graph);

    void prepare_cnf_formula();
    void make_graph_clauses();
    void make_goal_clauses();
    void make_prooftree_clauses();
    void make_acyclicity_clauses();
    
    std::tuple<size_t,size_t> prepare_vertex_elimination();
    void make_vertex_elimination_clauses();

    std::tuple<size_t,size_t> prepare_transitive_closure();
    void make_transitive_closure_clauses();

    cnfbuilder& m_builder;

    fact_map m_facts_id;
    reverse_fact_map m_reverse_facts_id;

    rules_map m_rules;

    atom_graph m_graph;
    atom_graph m_vertex_elimination_graph;
    std::vector<dexp::atom_graph> m_components;
    std::set<std::tuple<int,int,int>> m_triangles;
    
    dexp::db_vars_map m_db_vars_map;

    int m_goal{};
    std::string m_goal_str;

    int m_last_id{};
    int m_last_edge_id{};
    int m_last_rule_id{};

    acyclicity_mode m_acyc_mode;
};

}