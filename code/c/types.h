#pragma once

#include <graph_lite/graph_lite.h>

namespace dexp {

typedef graph_lite::Graph<  int, // node type
                            void,   // node property
                            int, // edge property
                            graph_lite::EdgeDirection::DIRECTED, // directed edges
                            graph_lite::MultiEdge::DISALLOWED, //no multi-graph
                            graph_lite::SelfLoop::ALLOWED, // allow self loops
                            graph_lite::Map::UNORDERED_MAP, //adj_list container
                            graph_lite::Container::VEC, // neighbours container
                            graph_lite::Logging::DISALLOWED> atom_graph; 

typedef graph_lite::Graph<  std::string, // node type
                            void,   // node property
                            int, // edge property
                            graph_lite::EdgeDirection::DIRECTED, // directed edges
                            graph_lite::MultiEdge::ALLOWED, //no multi-graph
                            graph_lite::SelfLoop::ALLOWED, // allow self loops
                            graph_lite::Map::UNORDERED_MAP, //adj_list container
                            graph_lite::Container::VEC, // neighbours container
                            graph_lite::Logging::DISALLOWED> pre_graph; 
};
