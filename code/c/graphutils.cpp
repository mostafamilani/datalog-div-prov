#include "graphutils.h"
#include <queue>
#include <stack>

class node_entry {
public:

    node_entry(int n, int tot) {
        this->node = n;
        this->value = tot;
    }

    bool operator<(const node_entry& other) const {
        return this->value > other.value;
    }

    int node;
    int value;
};

static void tarjan_visit(   const dexp::atom_graph& graph,
                            int u0,
                            std::vector<int>& stack,
                            std::map<int,int>& low,
                            std::map<int,int>& dis,
                            std::vector<dexp::atom_graph>& result) {

    std::stack<node_entry> work;
    work.emplace(u0,0);

    while (!work.empty()) {
        auto entry = work.top();
        work.pop();
        auto u = entry.node;
        auto j = entry.value;

        if(j == 0){
            auto k = dis.size();
            dis[u] = k;
            low[u] = k;
            stack.push_back(u);
        }

        bool recurse = false;
        auto edge_it = graph.out_neighbors(u);

        int i = j;
        for(auto it = edge_it.first + j; it != edge_it.second; ++it){
            auto v = it->first;
            if(!low.contains(v)){
                work.emplace(u,i+1);
                work.emplace(v,0);
                recurse = true;
                break;
            }else if(std::find(stack.cbegin(),stack.cend(),v) != stack.cend()){
                low[u] = std::min(low[u],dis[v]);
            }
            i++;
        }
        if(!recurse){
            if(low[u] == dis[u]){
                dexp::atom_graph comp;
                while(true){
                    auto v = stack.back();
                    stack.pop_back();
                    comp.add_nodes(v);
                    if(u == v){
                        break;
                    }
                }
                for(auto& source: comp){
                    auto edge_it = graph.out_neighbors(source);

                    for(auto it = edge_it.first; it != edge_it.second; ++it){
                        auto target = it->first;
                        if(comp.has_node(target)){
                            //TODO: check the iterator over nodes of comp is not invalidated
                            comp.add_edge_with_prop(source,target,it->second.prop());
                        }
                    }
                }

                result.push_back(std::move(comp));
            }

            if(!work.empty()){
                auto v = u;
                u = work.top().node;
                low[u] = std::min(low[u],low[v]);
            }
        }
    }
}

static void copy_graph(const dexp::atom_graph& graph, dexp::atom_graph& out_graph, int* base_index = nullptr) {
    for(auto& source: graph) {
        out_graph.add_nodes(source);
        auto edge_it = graph.out_neighbors(source);

        for(auto it = edge_it.first; it != edge_it.second; ++it){
            auto target = it->first;
            out_graph.add_nodes(target);

            if(!base_index) {
                out_graph.add_edge_with_prop(source,target,it->second.prop());
            }else{
                out_graph.add_edge_with_prop(source,target,*base_index);
                (*base_index)++;
            }
        }
    }
}

static void prepare_priority_queue(const dexp::atom_graph& graph, std::priority_queue<node_entry>& queue) {
    for(auto& node: graph) {
        int tot = graph.count_in_neighbors(node);
        tot += graph.count_out_neighbors(node);
        node_entry entry(node,tot);
        queue.push(entry);
    }
}

static int find_best_node(const dexp::atom_graph& graph) {

    int min_neighbours = 2 * graph.size();
    int min_node = -1;

    for(auto& node: graph) {
        int cur_num_neighbours = 0;
        cur_num_neighbours += graph.count_out_neighbors(node);
        cur_num_neighbours += graph.count_in_neighbors(node);

        if( cur_num_neighbours < min_neighbours ) {
            min_neighbours = cur_num_neighbours;
            min_node = node;
        }
    }

    return min_node;
}

void dexp::tarjan_scc(const dexp::atom_graph& graph, std::vector<dexp::atom_graph>& components) {
    std::vector<int> stack;
    std::map<int,int> low;
    std::map<int,int> dis;
    for(auto& v: graph){
        if(!low.contains(v)){
            tarjan_visit(graph, v, stack, low, dis, components);
        }
    }
}

void dexp::reduce_graph(const dexp::atom_graph& graph, dexp::atom_graph& reduced_graph) {
    copy_graph(graph,reduced_graph);

    std::vector<int> nodes_no_neighbours;

    for(auto& source: reduced_graph){
        if( reduced_graph.count_in_neighbors(source) == 0 || reduced_graph.count_out_neighbors(source) == 0) {
            nodes_no_neighbours.push_back(source);
        }
    }

    for(size_t i = 0; i < nodes_no_neighbours.size(); i++) {

        auto source = nodes_no_neighbours[i];
        bool no_in = reduced_graph.count_in_neighbors(source) == 0;

        auto edge_it = no_in? reduced_graph.out_neighbors(source) : reduced_graph.in_neighbors(source);


        for(auto it = edge_it.first; it != edge_it.second; ++it){
            auto other_node = it->first;
            auto other_neighbors = no_in?   reduced_graph.count_in_neighbors(other_node) - 1 :
                                            reduced_graph.count_out_neighbors(other_node) - 1;
            if(other_neighbors == 0){
                nodes_no_neighbours.push_back(other_node);
            }
        }

        reduced_graph.remove_nodes(source);
    }
}

int dexp::vertex_elimination(  const dexp::atom_graph& graph, 
                                dexp::atom_graph& out_graph,
                                std::set<std::tuple<int,int,int>>& triangles, int base_edge_id) {

    std::priority_queue<node_entry> queue;
    prepare_priority_queue(graph, queue);

    int cur_edge_id = base_edge_id;

    copy_graph(graph, out_graph, &cur_edge_id);

    size_t num_nodes = graph.size();

    atom_graph last_graph;
    copy_graph(graph, last_graph);

    while(!queue.empty()) {
        //if current node has increased its total degree, must be moved
        while(true) {
            auto entry = queue.top();
            int tot = last_graph.count_in_neighbors(entry.node);
            tot += last_graph.count_out_neighbors(entry.node);
            if(entry.value != tot){
                queue.pop();
                entry.value = tot;
                queue.push(entry);
            }else{
                break;
            }
        }

        int node = queue.top().node;
        queue.pop();

        dexp::atom_graph delta_graph;

        auto out_edge_it = last_graph.out_neighbors(node);
        auto in_edge_it = last_graph.in_neighbors(node);

        if(in_edge_it.first != in_edge_it.second && out_edge_it.first != out_edge_it.second){ 

            for(auto in_it = in_edge_it.first; in_it != in_edge_it.second; ++in_it){
                auto prev_node = in_it->first;
                auto out_edge_it = last_graph.out_neighbors(node);

                for(auto out_it = out_edge_it.first; out_it != out_edge_it.second; ++out_it){
                    auto next_node = out_it->first;

                    if( prev_node != next_node){
                        triangles.insert(std::make_tuple(prev_node,node,next_node));

                        if (!last_graph.find_out_neighbor(prev_node, next_node).first) {
                            delta_graph.add_nodes(prev_node);
                            delta_graph.add_nodes(next_node);
                            delta_graph.add_edge_with_prop(prev_node,next_node, cur_edge_id);
                            cur_edge_id++;
                        }
                    }
                }
            }
        }

        last_graph.remove_nodes(node);
        for(auto& source: delta_graph) {
            auto edge_it = delta_graph.out_neighbors(source);

            for(auto it = edge_it.first; it != edge_it.second; ++it){
                auto target = it->first;
                out_graph.add_edge_with_prop(source, target, it->second.prop());
                last_graph.add_edge_with_prop(source,target, it->second.prop());
            }
        }
    }
    return cur_edge_id;
}

void dexp::reachable_from(const dexp::pre_graph& graph, const std::string& source, dexp::pre_graph& out_graph){

    std::vector<std::string> queue;

    queue.push_back(source);
    
    out_graph.add_nodes(source);

    size_t i = 0;
    while (i < queue.size()) {
        auto v = queue[i++];

        auto edge_it = graph.out_neighbors(v);

        for(auto it = edge_it.first; it != edge_it.second; ++it){
            auto target = it->first;
            if(!out_graph.has_node(target)){
                queue.push_back(target);
                out_graph.add_nodes(target);
            }
            out_graph.add_edge_with_prop(v,target,it->second.prop());
        }
    }
}