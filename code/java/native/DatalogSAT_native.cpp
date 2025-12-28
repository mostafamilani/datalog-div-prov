#include "org_datalog_DatalogSAT.h"

#include <iostream>
#include <fstream>
#include <optional>


#include "groundparser.h"
#include "satencoding.h"
#include "solvercnfbuilder.h"
#include "solver.h"
#include "solver_utils.h"

class pair_t
{
public:
  pair_t(dexp::sat_encoding* enc, dexp::solvercnfbuilder* b) :
    encoding(enc), builder(b){}

  ~pair_t()
  {
    delete encoding;
    delete builder;
  }

  dexp::sat_encoding* encoding;
  dexp::solvercnfbuilder* builder;
};

static jobject stdmap_to_jmap(JNIEnv* env, const std::map<int, std::string>& nativeMap) {
    // Get HashMap class and constructor
    jclass hashMapClass = env->FindClass("java/util/HashMap");
    jmethodID init = env->GetMethodID(hashMapClass, "<init>", "()V");
    jobject hashMap = env->NewObject(hashMapClass, init);

    // Get method ID for put(Object key, Object value)
    jmethodID putMethod = env->GetMethodID(
        hashMapClass, "put",
        "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"
    );

    // Get Long and String classes and constructors
    jclass longClass = env->FindClass("java/lang/Long");
    jmethodID longConstructor = env->GetMethodID(longClass, "<init>", "(J)V");

    for (const auto& pair : nativeMap) {
        // Create Java Long key
        jobject key = env->NewObject(longClass, longConstructor, static_cast<jlong>(pair.first));
        // Create Java String value
        jstring value = env->NewStringUTF(pair.second.c_str());

        // Put key-value pair into HashMap
        env->CallObjectMethod(hashMap, putMethod, key, value);

        // Clean up local references
        env->DeleteLocalRef(key);
        env->DeleteLocalRef(value);
    }

    return hashMap;
}

static std::vector<int> jlist_to_stdvector(JNIEnv* env, jobject list) {
    std::vector<int> result;

    // Get classes and method IDs
    jclass listClass = env->GetObjectClass(list);
    jmethodID iteratorMethod = env->GetMethodID(listClass, "iterator", "()Ljava/util/Iterator;");

    jobject iterator = env->CallObjectMethod(list, iteratorMethod);

    jclass iteratorClass = env->GetObjectClass(iterator);
    jmethodID hasNextMethod = env->GetMethodID(iteratorClass, "hasNext", "()Z");
    jmethodID nextMethod = env->GetMethodID(iteratorClass, "next", "()Ljava/lang/Object;");

    jclass longClass = env->FindClass("java/lang/Long");
    jmethodID intValueMethod = env->GetMethodID(longClass, "longValue", "()J");
    
    // Iterate through the list
    while (env->CallBooleanMethod(iterator, hasNextMethod)) {
        jobject integerObject = env->CallObjectMethod(iterator, nextMethod);

        if (integerObject != nullptr) {
            jint value = env->CallLongMethod(integerObject, intValueMethod);
            result.push_back(static_cast<int>(value));
            env->DeleteLocalRef(integerObject);
        }
    }

    // Clean up
    env->DeleteLocalRef(iterator);

    return result;
}

static std::string jstring_to_stdstring(JNIEnv* env, jstring jStr) {
    if (!jStr) return "";

    const char* chars = env->GetStringUTFChars(jStr, nullptr);
    std::string result(chars);
    env->ReleaseStringUTFChars(jStr, chars);

    return result;
}

static pair_t* build_solvers(
          const std::string& downward_file,
          const std::string& goal,
          int num_copies = 0)
{
  dexp::ground_parser parser;
  dexp::pre_graph grounding_graph;

  size_t num_nodes = 0;
  size_t num_hyper_edges = 0;
  std::ifstream in(downward_file);

  if(!in){
      std::cerr << "Cannot open file " << downward_file << std::endl;
      return nullptr;
  }

  parser.to_graph(in, grounding_graph, num_nodes, num_hyper_edges);
  
  dexp::solvercnfbuilder* builder = new dexp::solvercnfbuilder();
  builder->use_builtin_acyclicity(false);
  if(num_copies > 0)
  {
    builder->produce_additional_copies(num_copies);
  }

  dexp::sat_encoding* encoding = new dexp::sat_encoding(*builder);
  encoding->set_acyclicity_mode(dexp::sat_encoding::acyclicity_mode::vertex_elimination);
  encoding->set_goal(goal);
  encoding->build(grounding_graph);

  return new pair_t(encoding, builder);
}


JNIEXPORT jlong JNICALL Java_org_datalog_DatalogSAT_getSATEncodingHandle
  (JNIEnv *env, jobject _this, jstring _downward_file, jstring _goal)
{
  std::string downward_file = jstring_to_stdstring(env, _downward_file);
  std::string goal = jstring_to_stdstring(env, _goal);
  return reinterpret_cast<jlong>(build_solvers(downward_file, goal));
}

JNIEXPORT jlong JNICALL Java_org_datalog_DatalogSAT_getSATEncodingWithNumSolverCopiesHandle
   (JNIEnv *env, jobject _this, jstring _downward_file, jstring _goal, jint num_copies)
{
  std::string downward_file = jstring_to_stdstring(env, _downward_file);
  std::string goal = jstring_to_stdstring(env, _goal);
  return reinterpret_cast<jlong>(build_solvers(downward_file, goal, num_copies));
}

JNIEXPORT jlong JNICALL Java_org_datalog_DatalogSAT_getVariableNameInCopy
  (JNIEnv *env, jobject _this, jlong sat_encoding_handle, jlong base_var, jint copy_id)
{
  pair_t* handle = reinterpret_cast<pair_t*>(sat_encoding_handle);
  return handle->builder->translate_var_id(base_var, copy_id);
}

JNIEXPORT jobject JNICALL Java_org_datalog_DatalogSAT_getSATEncodingDBVarMap
  (JNIEnv *env, jobject _this, jlong sat_encoding_handle)
{
  pair_t* handle = reinterpret_cast<pair_t*>(sat_encoding_handle);
  
  return stdmap_to_jmap(env, handle->encoding->vars_to_db_map());
}

JNIEXPORT void JNICALL Java_org_datalog_DatalogSAT_freeSATEncoding
  (JNIEnv *, jobject _this, jlong sat_encoding_handle)
{
  pair_t* handle = reinterpret_cast<pair_t*>(sat_encoding_handle);
  delete handle;
}

JNIEXPORT jlong JNICALL Java_org_datalog_DatalogSAT_getSATEncodingSolverHandle
  (JNIEnv *, jobject _this, jlong sat_encoding_handle)
{
  dexp::solver* solver = &reinterpret_cast<pair_t*>(sat_encoding_handle)->builder->solver();
  return reinterpret_cast<jlong>(solver);
}

JNIEXPORT jlong JNICALL Java_org_datalog_DatalogSAT_getSATEncodingCopySolverHandle
  (JNIEnv *, jobject _this, jlong sat_encoding_handle)
{
  dexp::solver* solver = &reinterpret_cast<pair_t*>(sat_encoding_handle)->builder->additional_copy_solver();
  return reinterpret_cast<jlong>(solver);
}

JNIEXPORT jlong JNICALL Java_org_datalog_DatalogSAT_newSolverVariable
  (JNIEnv *, jobject _this, jlong solver_handle)
{
  dexp::solver* solver = reinterpret_cast<dexp::solver*>(solver_handle);
  return solver->new_var();
}

JNIEXPORT void JNICALL Java_org_datalog_DatalogSAT_addSolverClause
  (JNIEnv *env, jobject _this, jlong solver_handle, jobject clause_list, jlong weight)
{
  dexp::solver* solver = reinterpret_cast<dexp::solver*>(solver_handle);
  std::vector<int> clause = jlist_to_stdvector(env, clause_list);

  if(!weight)
  {
    solver->add_clause(clause);
  }else{
    solver->add_clause(clause, std::make_optional(weight));
  }
}


JNIEXPORT void JNICALL Java_org_datalog_DatalogSAT_addSolverXorClause
  (JNIEnv *env, jobject _this, jlong solver_handle, jobject clause_list, jlong weight)
{
  dexp::solver* solver = reinterpret_cast<dexp::solver*>(solver_handle);
  std::vector<int> clause = jlist_to_stdvector(env, clause_list);

  if(!weight)
  {
    solver->add_xor_clause(clause);
  }else{
    solver->add_xor_clause(clause, std::make_optional(weight));
  }
}

JNIEXPORT jlong JNICALL Java_org_datalog_DatalogSAT_getSolverNumVariables
  (JNIEnv *env, jobject _this, jlong solver_handle)
{
  dexp::solver* solver = reinterpret_cast<dexp::solver*>(solver_handle);
  return solver->num_vars();
}

JNIEXPORT jboolean JNICALL Java_org_datalog_DatalogSAT_solve
  (JNIEnv *env, jobject _this, jlong solver_handle)
{
  dexp::solver* solver = reinterpret_cast<dexp::solver*>(solver_handle);
  return static_cast<jboolean>(solver->solve());
}

JNIEXPORT jlong JNICALL Java_org_datalog_DatalogSAT_getSolverCurrentSolutionCost
  (JNIEnv *env, jobject _this, jlong solver_handle)
{
  dexp::solver* solver = reinterpret_cast<dexp::solver*>(solver_handle);
  return solver->obj_value();
}

JNIEXPORT jboolean JNICALL Java_org_datalog_DatalogSAT_getSolverCurrentSolutionLiteralValue
  (JNIEnv *env, jobject _this, jlong solver_handle, jlong lit)
{
  dexp::solver* solver = reinterpret_cast<dexp::solver*>(solver_handle);
  return static_cast<jboolean>(solver->lit_value(lit));
}

JNIEXPORT jlong JNICALL Java_org_datalog_DatalogSAT_addXorAxiomatizationToSolver
  (JNIEnv *env, jobject _this, jlong solver_handle, jobject clause_list)
{
  dexp::solver* solver = reinterpret_cast<dexp::solver*>(solver_handle);
  std::vector<int> clause = jlist_to_stdvector(env, clause_list);

  int aux_variable = dexp::axiomatize_xor(*solver,clause);

  return aux_variable;
}