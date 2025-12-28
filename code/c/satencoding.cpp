#include "satencoding.h"
#include "graphutils.h"

dexp::sat_encoding::sat_encoding(cnfbuilder& builder)
:   m_builder(builder),
    m_acyc_mode(acyclicity_mode::builtin) {

}

int dexp::sat_encoding::add_vertex_if_not_present(const std::string& fact){
    auto res = m_facts_id.find(fact);
    if(res != m_facts_id.end()){
        return res->second;
    }

    m_graph.add_nodes(m_last_id);
    m_facts_id[fact] = m_last_id;
    m_reverse_facts_id[m_last_id] = fact;
    
    return m_last_id++;
}

void dexp::sat_encoding::add_edge_if_not_present(int source, int target){
    if(m_graph.add_edge_with_prop(source,target,m_last_edge_id)){
        m_last_edge_id++;
    }
}

void dexp::sat_encoding::set_goal(const std::string& fact){
    m_goal = add_vertex_if_not_present(fact);
    m_goal_str = fact;
}

void dexp::sat_encoding::add_rule(const std::string& head, const std::set<std::string>& body){
    //This kind of rules is never going to be used in a proof tree.
    if(body.find(head) != body.end())
        return;
    
    auto head_id = add_vertex_if_not_present(head);

    std::set<int> body_ids;
    for(auto& body_fact : body){
        auto body_id = add_vertex_if_not_present(body_fact);
        body_ids.insert(body_id);
        add_edge_if_not_present(head_id,body_id);
    }

    auto& bodies = m_rules[head_id];

    rule r(head_id,body_ids);
    r.set_global_id(m_last_rule_id);
    
    if(bodies.insert(r).second){
        m_last_rule_id++;
    }
}

void dexp::sat_encoding::build(const pre_graph& graph){
    import_graph(graph);

    prepare_cnf_formula();
    make_graph_clauses();
    make_goal_clauses();
    make_prooftree_clauses();
    make_acyclicity_clauses();
}

void dexp::sat_encoding::prepare_cnf_formula(){
    size_t additional_vars = 0, additional_clauses = 0;

    switch(m_acyc_mode){
        case acyclicity_mode::vertex_elimination:
            std::tie(additional_vars,additional_clauses) = prepare_vertex_elimination();
        break;

        case acyclicity_mode::transitive_closure:
            std::tie(additional_vars,additional_clauses) = prepare_transitive_closure();
        break;

        case acyclicity_mode::builtin:
            tarjan_scc(m_graph,m_components);
        break;

    }

    auto num_edges = m_graph.num_edges();

    auto num_nodes = m_graph.size();

    auto num_rules = m_last_rule_id;
    auto num_edges_to_goal = m_graph.count_in_neighbors(m_goal);

    auto num_head_atoms = m_rules.size();

    size_t num_clauses_rules = 0;
    for(const auto& entry : m_rules){
        auto head_id = entry.first;
        auto num_rules_with_head = entry.second.size();
        auto num_edges_from_head = m_graph.count_out_neighbors(head_id);

        num_clauses_rules += (num_rules_with_head * (num_edges_from_head ));
    }
    

    auto num_vars = num_nodes + num_edges + num_rules + additional_vars;

    auto num_clauses    =   2*num_edges +
                            1 + num_edges_to_goal +
                            (num_nodes - 1) +
                            num_head_atoms + num_clauses_rules +
                            additional_clauses;

    for(const auto& node : m_graph) {
        if(m_graph.count_out_neighbors(node) == 0){
            auto node_var = make_node_var(node);
            m_db_vars_map[node_var] = m_reverse_facts_id[node];
        }
    }
    
    m_builder.set_cnf_info(num_vars, num_clauses, m_components);
}

void dexp::sat_encoding::make_graph_clauses(){

    for(auto& source : m_graph){
        auto edge_it = m_graph.out_neighbors(source);

        for(auto it = edge_it.first; it != edge_it.second; ++it){
            auto target = it->first;
            auto edge_id = it-> second.prop();

            auto edge_var = make_edge_var(edge_id);

            m_builder   .begin_clause()
                        .add_neg_literal(edge_var)
                        .add_pos_literal(make_node_var(source))
                        .end_clause()

                        .begin_clause()
                        .add_neg_literal(edge_var)
                        .add_pos_literal(make_node_var(target))
                        .end_clause();
        }
    }
}

void dexp::sat_encoding::make_goal_clauses(){

    m_builder   .begin_clause()
                .add_pos_literal(make_node_var(m_goal))
                .end_clause();

    //Add all literals not e_ap
    auto edges_it = m_graph.in_neighbors(m_goal);
    for(auto it = edges_it.first; it != edges_it.second; ++it){
        auto edge_id = it->second.prop();

        m_builder   .begin_clause()
                    .add_neg_literal(make_edge_var(edge_id))
                    .end_clause();
    }

    //Here we also add the constraint and_(a != p) a -> or_b e_ba.
    for(auto& target : m_graph) {

        if(target != m_goal){
            
            m_builder   .begin_clause()
                        .add_neg_literal(make_node_var(target));
            
            auto in_edges_it = m_graph.in_neighbors(target);
            for(auto in_it = in_edges_it.first; in_it != in_edges_it.second; ++in_it){
                auto edge_id = in_it->second.prop();
                m_builder.add_pos_literal(make_edge_var(edge_id));
            }
            m_builder.end_clause();
        }
    }
}

void dexp::sat_encoding::make_prooftree_clauses(){

    for(auto& entry : m_rules){
        auto head_id = entry.first;
        auto& rules_with_head = entry.second;

        m_builder   .begin_clause()
                    .add_neg_literal(make_node_var(head_id));
        
        for(const rule& r : rules_with_head){
            m_builder.add_pos_literal(make_rule_var(r.get_global_id()));
        }

        m_builder.end_clause();

        for(const rule& r : rules_with_head){

            auto out_edges_head_it = m_graph.out_neighbors(head_id);
            for(auto it = out_edges_head_it.first; it != out_edges_head_it.second; ++it){
                auto edge_id = it->second.prop();

                auto target = it->first;

                m_builder   .begin_clause()
                            .add_neg_literal(make_rule_var(r.get_global_id()));
                
                auto& r_body = r.get_body_ids();
                if(r_body.find(target) != r_body.end()){
                    m_builder.add_pos_literal(make_edge_var(edge_id));
                }else{
                    m_builder.add_neg_literal(make_edge_var(edge_id));
                }

                m_builder.end_clause();
            } 
        }
    }
}

void dexp::sat_encoding::make_acyclicity_clauses() {
    switch(m_acyc_mode){
        case acyclicity_mode::vertex_elimination:
            make_vertex_elimination_clauses();
        break;

        case acyclicity_mode::transitive_closure:
            make_transitive_closure_clauses();
        break;
    }
}

void dexp::sat_encoding::import_graph(const pre_graph& graph){
    //pre_graph simplified_graph;
    //reachable_from(graph, m_goal_str, simplified_graph);

    //Convert graph using variable names for nodes, edges, etc., and fill auxiliary data structures.

    for(auto& source: graph) {
        std::map<int,std::set<std::string>> tmp_rules_map;
        
        auto edges_it = graph.out_neighbors(source);
        for(auto it = edges_it.first; it != edges_it.second; ++it){
            auto rule_id = it->second.prop();
            auto& target = it->first;

            tmp_rules_map[rule_id].insert(target);
        }

        for(const auto& entry : tmp_rules_map){
            add_rule(source,entry.second);
        }
    }   

}

void dexp::sat_encoding::make_vertex_elimination_clauses(){
    for(auto& comp : m_components){
        if(comp.size() > 1){
            for(auto& source: comp) {
                auto edges_it = comp.out_neighbors(source);
                for(auto it = edges_it.first; it != edges_it.second; ++it){
                    auto edge_id = it->second.prop();
                    auto target = it->first;

                    auto acyclicity_edge_id = m_vertex_elimination_graph.edge_prop(source,target);

                    m_builder   .begin_clause()
                                .add_neg_literal(make_edge_var(edge_id))
                                .add_pos_literal(make_acyclicity_edge_var(acyclicity_edge_id))
                                .end_clause();

                }
            }
        }
    }
    

    for(auto& source: m_vertex_elimination_graph) {
        auto edges_it = m_vertex_elimination_graph.out_neighbors(source);
        for(auto it = edges_it.first; it != edges_it.second; ++it){
            auto acyclicity_edge_id = it->second.prop();
            auto target = it->first;
            bool is_symmetric = m_vertex_elimination_graph.find_in_neighbor(source, target).first;

            if (source < target && is_symmetric) {
                auto reverse_edge_id = m_vertex_elimination_graph.edge_prop(target,source);
                m_builder   .begin_clause()
                            .add_neg_literal(make_acyclicity_edge_var(acyclicity_edge_id))
                            .add_neg_literal(make_acyclicity_edge_var(reverse_edge_id))
                            .end_clause();
            }
        }
    }

    for(auto& triangle: m_triangles) {
        int u = std::get<0>(triangle);
        int v = std::get<1>(triangle);
        int w = std::get<2>(triangle);

        auto first_edge_id = m_vertex_elimination_graph.edge_prop(u,v);
        auto second_edge_id = m_vertex_elimination_graph.edge_prop(v,w);
        auto third_edge_id = m_vertex_elimination_graph.edge_prop(u,w);

        m_builder   .begin_clause()
                    .add_neg_literal(make_acyclicity_edge_var(first_edge_id))
                    .add_neg_literal(make_acyclicity_edge_var(second_edge_id))
                    .add_pos_literal(make_acyclicity_edge_var(third_edge_id))
                    .end_clause();
    }
}

std::tuple<size_t,size_t> dexp::sat_encoding::prepare_vertex_elimination(){
    size_t num_edges_components = 0;

    int base_edge_id = 0;
    tarjan_scc(m_graph,m_components);

#ifndef NDEBUG
    std::cout << "Computed " << m_components.size() << " components" << std::endl;
#endif
    size_t i = 0, j = 0;
    for(auto& comp: m_components){
        if(comp.size() > 1){

#ifndef NDEBUG
            std::cout   << "Working on component "
                        << i << "/" << m_components.size() << " with "
                        << comp.size() << " nodes and "
                        << comp.num_edges() << " edges" << std::endl;
#endif
            num_edges_components += comp.num_edges();
            base_edge_id = vertex_elimination(comp, m_vertex_elimination_graph, m_triangles, base_edge_id);
            j++;
        }
        i++;
    }
#ifndef NDEBUG
    std::cout << "Found " << j << " non-singleton components" << std::endl;
#endif
    //compute additional variables and clauses
    
    auto num_edges_vertex_elimination = m_vertex_elimination_graph.num_edges();
    auto num_triangles = m_triangles.size();

    size_t num_symmetric_edges = 0;
    for(auto& source: m_vertex_elimination_graph) {
        auto edges_it = m_vertex_elimination_graph.out_neighbors(source);
        for(auto it = edges_it.first; it != edges_it.second; ++it){
            auto acyclicity_edge_id = it->second.prop();
            auto target = it->first;
            bool is_symmetric = m_vertex_elimination_graph.find_in_neighbor(source, target).first;

            if (source < target && is_symmetric) {
                num_symmetric_edges++;
            }
        }
    }

    return std::make_tuple(num_edges_vertex_elimination, num_edges_components + num_symmetric_edges + num_triangles);
}

std::tuple<size_t,size_t> dexp::sat_encoding::prepare_transitive_closure(){
    size_t additional_clauses = 0;
    size_t additional_vars = 0;

    tarjan_scc(m_graph,m_components);
    for(auto& comp: m_components){
        if(comp.size() > 1){
            size_t comp_edges = comp.num_edges();
            size_t comp_nodes = comp.size();
            additional_vars += (comp_nodes * (comp_nodes - 1));

            additional_clauses += (comp_edges + comp_edges*(comp_nodes - 2) + comp_nodes * (comp_nodes - 1));
        }
    }

    return std::make_tuple(additional_vars, additional_clauses);
}

#define BUILD_CLOSURE_EGDE_ID(s_id,t_id,n) s_id*(n-1) + (t_id < s_id? t_id : t_id - 1)
void dexp::sat_encoding::make_transitive_closure_clauses(){

    int closure_edge_base_id = 0;
    for(auto& comp : m_components){
        if(comp.size() > 1){

            auto comp_size = comp.size();

            std::map<int,int> nodes_id;
            int cur_node_id = 0;
            for(auto& node: comp){
                if(!nodes_id.contains(node)){
                    nodes_id[node] = cur_node_id++;
                }
            }

            //prepare clauses e_ab -> e*_ab and e_ab,e*_bc -> e*_ac
            for(auto& source: comp) {
                auto source_id = nodes_id[source];

                auto edges_it = comp.out_neighbors(source);
                for(auto it = edges_it.first; it != edges_it.second; ++it){
                    auto edge_id = it->second.prop();
                    auto target = it->first;
                    auto target_id = nodes_id[target];

                    //compute unique closure edge id
                    auto closure_edge_id =  closure_edge_base_id +
                                            BUILD_CLOSURE_EGDE_ID(source_id,target_id,comp_size);

                    //e_ab -> e*_ab
                    m_builder   .begin_clause()
                                .add_neg_literal(make_edge_var(edge_id))
                                .add_pos_literal(make_acyclicity_edge_var(closure_edge_id))
                                .end_clause();

                    //e_ab,e*_bc -> e*_ac
                    for(size_t c = 0; c < comp_size; c++){
                        if(c != source_id && c != target_id){
                            auto next_closure_edge_id = closure_edge_base_id +
                                                        BUILD_CLOSURE_EGDE_ID(target_id,c,comp_size);

                            auto next_next_closure_edge_id =    closure_edge_base_id +
                                                                BUILD_CLOSURE_EGDE_ID(source_id,c,comp_size);

                            m_builder   .begin_clause()
                                        .add_neg_literal(make_edge_var(edge_id))
                                        .add_neg_literal(make_acyclicity_edge_var(next_closure_edge_id))
                                        .add_pos_literal(make_acyclicity_edge_var(next_next_closure_edge_id))
                                        .end_clause();
                        }
                    }
                }
            }

            //prepare clauses e*_ab -> not e*_ba
            for(size_t a = 0; a < comp_size; a++){
                for(size_t b = 0; b < comp_size; b++){
                    if(a != b){
                        auto right_closure_edge_id = closure_edge_base_id + BUILD_CLOSURE_EGDE_ID(a,b,comp_size);
                        auto left_closure_edge_id = closure_edge_base_id + BUILD_CLOSURE_EGDE_ID(b,a,comp_size);

                        m_builder   .begin_clause()
                                    .add_neg_literal(make_acyclicity_edge_var(right_closure_edge_id))
                                    .add_neg_literal(make_acyclicity_edge_var(left_closure_edge_id))
                                    .end_clause();
                    }
                }
            }
            closure_edge_base_id += (comp_size *(comp_size - 1));
        }
    }
}