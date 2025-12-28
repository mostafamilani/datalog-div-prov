#include "groundparser.h"
#include "satencoding.h"
#include "solvercnfbuilder.h"
#include "solver.h"
#include "solver_utils.h"
#include "timer.h"

#include <argparse/argparse.hpp>

#include <iostream>
#include <fstream>
#include <csignal>
#include <chrono>
#include <atomic>
#include <set>

static dexp::timer<std::chrono::microseconds,std::chrono::steady_clock> timer;

void add_data(int64_t data){
    std::cout << data << " " << std::flush;
}

void enforce_fresh_model(dexp::solver& solver, const std::vector<int>& literals){
    solver.add_clause(literals);
    for(auto lit: literals){
        solver.add_clause({lit},1);
    }
}
std::pair<int,uint64_t> enumerate_models(  
                        dexp::solvercnfbuilder& builder,
                        int num_models,
                        int num_initial_models,
                        const dexp::db_vars_map& db_vars_map,
                        bool must_print_models) {

    timer.tick();
    
    dexp::solver& solver = builder.solver();
    dexp::solver& additional_copy_solver = builder.additional_copy_solver();

    std::vector<std::vector<int>> models;

    int model_count = 0;
    uint64_t total_diversity = 0;

    // First, find the initial optimally different explanations.
    if(num_initial_models >= 2){
        timer.tick();

        std::vector<int> aux_variables(db_vars_map.size());

        for(size_t i = 0; i < num_initial_models; i++){
            for(size_t j = i + 1; j < num_initial_models; j++){

                int k = 0;
                for(auto& entry : db_vars_map) {
                    auto variable_copy_0 = builder.translate_var_id(entry.first,i);
                    auto variable_copy_1 = builder.translate_var_id(entry.first,j);

                    // Xor asking the two explanations are different on the current database fact
                    auto aux_variable = dexp::axiomatize_xor(
                                            additional_copy_solver,
                                            {variable_copy_0,variable_copy_1});

                    // Soft constraint to ask for the two explanations to possibly be different
                    // on the current fact
                    additional_copy_solver.add_clause({aux_variable},1);
                    aux_variables[k++] = aux_variable;
                }
                
                // Hardly require the two explanations to be different in at least one fact.
                // That is, at least one xor is true.
                additional_copy_solver.add_clause(aux_variables);
            }
        }

        auto result = additional_copy_solver.solve();

        timer.tock();
        add_data(timer.duration().count());

        timer.tick();
        // If not possible to find num_initial_models different models.
        if(!result){
            return std::make_pair(0,0);
        }

        // Else, prepare the base solver to find a new model, different than the ones found
        total_diversity = additional_copy_solver.obj_value();
        model_count += num_initial_models;

        std::vector<int> model_blocker(db_vars_map.size());

        for(size_t i = 0; i < num_initial_models; i++){
            std::vector<int> model;
            std::vector<std::string> model_facts;
            size_t k = 0;
            for(auto& entry : db_vars_map) {
                auto variable = builder.translate_var_id(entry.first);
                auto& fact_name = entry.second;
    
                auto copy_variable = builder.translate_var_id(entry.first,i);
                bool copy_var_value = additional_copy_solver.lit_value(copy_variable);

                model_blocker[k++] = (copy_var_value? -variable : variable);

                if(copy_var_value) {
                    if(must_print_models){
                        model_facts.push_back(fact_name);
                    }
    
                    model.push_back(variable);
                }
            }
            
            models.push_back(model);

            if(must_print_models){
                for(auto& fact_name : model_facts){
                    std::cerr << fact_name << " ";
                }
                std::cerr << " !" << std::endl;
            }
            enforce_fresh_model(solver, model_blocker);
        }
    }
   
    while(true) {
        bool ret = solver.solve();
        timer.tock();
        add_data(timer.duration().count());

        if(!ret) return std::make_pair(model_count,total_diversity);

        timer.tick();

        total_diversity = solver.obj_value();

        std::vector<int> model_blocker(db_vars_map.size());

        std::vector<int> model;
        std::vector<std::string> model_facts;
        
        size_t k = 0;
        for(auto& entry : db_vars_map) {
            auto variable = builder.translate_var_id(entry.first);
            auto& fact_name = entry.second;
            bool var_value = solver.lit_value(variable);

            if(var_value) {
                if(must_print_models){
                    model_facts.push_back(fact_name);
                }

                model.push_back(variable);
            }
            model_blocker[k++] = (var_value? -variable : variable);
        }

        models.push_back(model);

        if(must_print_models){
            for(auto& fact_name : model_facts){
                std::cerr << fact_name << " ";
            }
            std::cerr << " !" << std::endl;
        }

        // If we built all requested models, stop
        model_count++;
        if(num_models > 0 && model_count >= num_models){
            return std::make_pair(model_count,total_diversity);
        }

        // Now enforce with a hard clause that we want a fresh model, in this way,
        // if there are no fresh models, the solver will report UNSAT. Moreover, we
        // add the soft constraints to find the most diverse fresh model.
        enforce_fresh_model(solver, model_blocker);
    }
}


struct cli_args : public argparse::Args {
    std::string& goal           = kwarg("g,goal", "The query goal fact");
    int& num_supports           = kwarg("n,num-supports", "Number of supports to generate (0 means all supports)").set_default(0);
    int& num_initial_supports   = kwarg("i,num-initial-supports", 
                                    "initial number of optimally diverse supports (minimum 2)."
                                    "When num-supports=num-initial-supports,"
                                    "exact optimal computation is performed.").set_default(2);

    bool& print                 = flag("p,print", "If specified, prints the supports");

    std::optional<std::string>& downward_file = 
    arg("The file containing the downward closure built with DLV (if not specified, stdin will be used)");
};

int main(int argc, char* argv[]){

    try {
        auto args = argparse::parse<cli_args>(argc,argv,true);
        
        timer.tick();

        dexp::ground_parser parser;
        dexp::pre_graph grounding_graph;

        size_t num_nodes = 0;
        size_t num_hyper_edges = 0;
        if (!args.downward_file.has_value()){ //Read from stdin
            parser.to_graph(std::cin, grounding_graph, num_nodes, num_hyper_edges);
        } else{ //Read from file
            std::ifstream in(args.downward_file.value());

            if(!in){
                std::cerr << "Cannot open file " << args.downward_file.value() << std::endl;
                return -1;
            }

            parser.to_graph(in, grounding_graph, num_nodes, num_hyper_edges);
        }

        
        dexp::solvercnfbuilder builder;
        builder.use_builtin_acyclicity(false);
        builder.produce_additional_copies(args.num_initial_supports);

        dexp::sat_encoding encoding(builder);
        encoding.set_acyclicity_mode(dexp::sat_encoding::acyclicity_mode::vertex_elimination);
        
        encoding.set_goal(args.goal);

        encoding.build(grounding_graph);
        
        //Measure time to build the formula
        timer.tock();
        auto formula_build_time = timer.duration().count();

        auto& solver = builder.solver();

        add_data(num_nodes);
        add_data(num_hyper_edges);
        add_data(solver.num_vars());
        add_data(formula_build_time);
        
        auto result = 
            enumerate_models(builder, args.num_supports, args.num_initial_supports, encoding.vars_to_db_map(), args.print);

        add_data(result.first);
        add_data(result.second);
        

    } catch (const std::runtime_error &e) {
        std::cerr << e.what() << std::endl;
        std::cerr << "Please use --help for usage instructions" << std::endl;
        return -1;
    }

    return 0;
} 
