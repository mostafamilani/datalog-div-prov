#pragma once

#include "types.h"

#include <string>
#include <iostream>

namespace dexp {

typedef struct {
    std::string predicate;
    std::vector<std::string> terms;
} atom_t;

class ground_parser {

public:
    void to_graph(std::istream& input, pre_graph& out_graph, size_t &num_nodes, size_t& num_hyperedges);

private:
    bool parse_atom(std::istream& input, atom_t& atom);
};

}