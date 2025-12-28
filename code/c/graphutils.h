#pragma once

#include "types.h"
#include <vector>
#include <tuple>
#include <set>

namespace dexp {

void tarjan_scc(const dexp::atom_graph& graph, std::vector<dexp::atom_graph>& components);

void reduce_graph(const dexp::atom_graph& graph, dexp::atom_graph& reduced_graph);

int vertex_elimination(const atom_graph& graph,
                        atom_graph& vertex_elimination_graph,
                        std::set<std::tuple<int,int,int>>& triangles, int base_edge_id);

void reachable_from(const pre_graph& graph, const std::string& node, pre_graph& reachable_graph);

}