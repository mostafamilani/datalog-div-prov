#include "groundparser.h"

#include <stdexcept>
#include <set>
#include <iostream>

void dexp::ground_parser::to_graph(std::istream& input, pre_graph& out_graph, size_t &num_nodes, size_t& num_hyperedges){
    char cur_char{};
    int rule_id = 0;

    num_nodes = 0;
    num_hyperedges = 0;

    input >> cur_char;

    if(cur_char != '{'){
        throw std::invalid_argument("Expected open curly bracket");
    }

    while(true){
        atom_t edge_atom;
        auto result = parse_atom(input,edge_atom);

        if(!result){
            break;
        }

        if( (input >> cur_char) && (cur_char == ',' || cur_char == '}') ){

            auto max_arity = std::stoi(edge_atom.terms[0]);
            auto max_body = std::stoi(edge_atom.terms[1]);

            std::string head = edge_atom.terms[2]+"(";

            for(size_t i=0;i<max_arity;i++){
                auto term = edge_atom.terms[3+i];
                if(term == "nil") {
                    head += ")";
                    break;
                }else if (i == 0){
                    head += term;
                }else {
                    head += ","+term;
                }

                if(i == max_arity -1){
                    head += ")";
                }
            }

            out_graph.add_nodes(head);
            
            for(size_t i=0;i<max_body;i++){
                auto body_predicate = edge_atom.terms[2 + max_arity+1 + i*(max_arity+1)];
                if(body_predicate == "pnil") {
                    break;
                }

                std::string body = body_predicate+"(";

                for(size_t j=0;j<max_arity;j++){
                    auto term = edge_atom.terms[3 + max_arity+1 + i*(max_arity+1) + j];

                    if(term == "nil") {
                        body += ")";
                        break;
                    }else if (j == 0){
                        body += term;
                    }else {
                        body += ","+term;
                    }

                    if(j == max_arity -1){
                        body += ")";
                    }
                }
                out_graph.add_nodes(body);
                out_graph.add_edge_with_prop(head,body,rule_id);
            }
            
            rule_id++;
            num_hyperedges++;

        }else {
            throw std::invalid_argument("Expected end of atom or grounding");
        }
    }
    num_nodes = out_graph.size();
}

bool dexp::ground_parser::parse_atom(std::istream& input, atom_t& atom){

    int state = 0;
    char cur_char{};

    std::string cur_term;

    while(true){

        switch(state){

            case 0: //read predicate name
                input >> cur_char;
                
                if(!input){
                    return false;
                }

                if(cur_char == '('){
                    state = 1;
                }else{
                    atom.predicate += cur_char;
                }

            break;

            case 1: //read terms
                input >> cur_char;
                if(!input){
                    return false;
                }

                if(cur_char == ')'){
                    atom.terms.push_back(cur_term);
                    return true;
                }

                cur_term += cur_char;

                if(cur_char == '"'){
                    state = 2;
                }else{
                    state = 3;
                }
            break;

            case 2: //term is enclosed in quotes
                input >> cur_char;

                if(!input || cur_char == ')' || cur_char == ','){
                    return false;
                }

                cur_term += cur_char;
                if(cur_char == '"'){
                    input >> cur_char;
                    if(!input){
                        return false;
                    }

                    if(cur_char == ')'){
                        atom.terms.push_back(cur_term);
                        return true;
                    }else if (cur_char == ','){
                        atom.terms.push_back(cur_term);
                        cur_term.clear();
                        state = 1;
                    }else{
                        return false;
                    }
                }
            break;

            case 3: //term is plain
                input >> cur_char;
                if(!input){
                    return false;
                }

                if(cur_char == ')'){
                    atom.terms.push_back(cur_term);
                    return true;
                }else if (cur_char == ','){
                    atom.terms.push_back(cur_term);
                    cur_term.clear();
                    state = 1;
                }else{
                    cur_term += cur_char;
                }
            break;
        }
    }
    return false;
}