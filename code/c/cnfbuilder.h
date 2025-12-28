#pragma once

#include "types.h"

namespace dexp{

class cnfbuilder {

public:
    virtual ~cnfbuilder() {}
    virtual cnfbuilder& begin_clause() = 0;
    virtual cnfbuilder& end_clause() = 0;
    virtual cnfbuilder& add_pos_literal(int var_id) = 0;
    virtual cnfbuilder& add_neg_literal(int var_id) = 0;
    
    virtual cnfbuilder& set_cnf_info(size_t num_vars, size_t num_clauses, const std::vector<atom_graph>& components) = 0;
};

}