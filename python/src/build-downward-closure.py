import sys
import os
import datetime
import csv
import re
import subprocess
from dataclasses import dataclass

DLV_PATH = os.path.join(os.path.dirname(os.path.realpath(__file__)),"bin","dlv2")

@dataclass
class Atom():
    predicate: str
    terms: list[str]

    def __str__(self):
        if not self.terms or len(self.terms) == 0:
            return self.predicate
        
        return self.predicate + "(" + ",".join(self.terms)+")"

@dataclass
class Rule():
    head: Atom
    body: list[Atom]

    def __str__(self):
        if not self.body or len(self.body) == 0:
            return str(self.head)+"."
        
        return str(self.head)+ ":-"+",".join([str(b) for b in self.body])+"."

def next_token(input, idx):
    n = len(input)
    while idx < n and input[idx] == ' ':
        idx += 1
    return (input[idx],idx)

def parse_atom(input, idx):

    atom = Atom(None,[])
    state = 0
    cur_char = ''

    predicate = ""
    cur_term = ""

    n = len(input)
    while True:
        if idx >= n:
            return (None,idx)

        cur_char, idx = next_token(input,idx)
        idx += 1
        if state == 0:     

            if cur_char == '(':
                state = 1
                atom.predicate = predicate
            elif cur_char.isalnum() or cur_char in '_':
                predicate += cur_char
            else:
                idx -= 1
                atom.predicate = predicate
                return (atom,idx)


        elif state == 1:

            if cur_char == ')':
                atom.terms.append(cur_term)
                return (atom,idx)

            cur_term += cur_char

            if cur_char == '"':
                state = 2
            else:
                state = 3

        elif state == 2:

            cur_term += cur_char

            if cur_char == '"':
                if idx >= n:
                    return (None,idx)
                
                cur_char, idx = next_token(input,idx)
                idx += 1

                if cur_char == ')':
                    atom.terms.append(cur_term)
                    return (atom,idx)
                elif cur_char == ',':
                    atom.terms.append(cur_term)
                    cur_term=""
                    state = 1
                else:
                    return (None,idx)
                
        elif state == 3: 

            if cur_char == ')':
                atom.terms.append(cur_term)
                return (atom,idx)
            elif cur_char == ',':
                atom.terms.append(cur_term)
                cur_term = ""
                state = 1
            else:
                cur_term += cur_char     

def parse_program(input):
    
    rules = []
    max_arity = 0
    max_body = 0

    for rule_str in input:
        idx = 0
        n = len(rule_str)
        while True:
            r = Rule(None,[])
            head,idx = parse_atom(rule_str,idx);

            if max_arity < len(head.terms):
                max_arity = len(head.terms)

            if not head:
                break

            r.head = head
            if idx < n - 1:
                cur_char1, idx = next_token(rule_str,idx)
                idx += 1
                cur_char2, idx = next_token(rule_str,idx)
                idx += 1
                if cur_char1 == ':' and cur_char2 == '-':

                    body = []

                    while True:
                        fact,idx = parse_atom(rule_str,idx);

                        if not fact:
                            raise "Expected body fact, but found EOF"

                        if max_arity < len(fact.terms):
                            max_arity = len(fact.terms)
                        
                        body.append(fact)
                        
                        if idx < n:
                            cur_char, idx = next_token(rule_str,idx)
                            idx += 1
                            if cur_char == '.':
                                break
                            elif idx >= n or cur_char != ',':
                                raise "Expected comma separator after fact"
                    r.body = body
                    if max_body < len(r.body):
                        max_body = len(r.body)
                    
                    rules.append(r)
                    break
                else:
                    raise "Expected rule separator"
            else:
                raise "Expected rule separator"

    return (rules,max_arity,max_body)

def create_edge_rules(program, goal):
    rules, max_arity, max_body = parse_program(program)
    goal_arity = 2+(max_body+1)*(max_arity+1)

    cur_node_tuple = [goal.predicate]+goal.terms
    cur_node_tuple.extend(["nil"]*(max_arity-len(goal.terms)))
    cur_node_fact = Rule(Atom("dcnode",cur_node_tuple),None)

    #goal = Atom("dcedge",["X"+str(i) for i in range(2+(max_body+1)*(max_arity+1))])

    edge_rules = [cur_node_fact]
    for rule in rules:
        head = rule.head
        new_body = rule.body.copy()

        edge_atom = Atom("dcedge",[str(max_arity),str(max_body)])
        edge_atom.terms.append(head.predicate)
        edge_atom.terms.extend(head.terms)
        edge_atom.terms.extend(["nil"]*(max_arity-len(head.terms)))

        cur_node_atom = Atom("dcnode",[head.predicate])
        cur_node_atom.terms.extend(head.terms)
        cur_node_atom.terms.extend(["nil"]*(max_arity-len(head.terms)))
        new_body.insert(0,cur_node_atom)

        for body_id in range(max_body):

            target = None
            if body_id < len(rule.body):
                target = rule.body[body_id]
            
            if target:
                edge_atom.terms.append(target.predicate)
                edge_atom.terms.extend(target.terms)
                edge_atom.terms.extend(["nil"]*(max_arity-len(target.terms)))
            else:
                edge_atom.terms.append("pnil")
                edge_atom.terms.extend(["nil"]*max_arity)
            
    
            if target:
                next_node_atom = Atom("dcnode",[target.predicate])

                next_node_atom.terms.extend(target.terms)
                next_node_atom.terms.extend(["nil"]*(max_arity-len(target.terms)))

                edge_rules.append(Rule(next_node_atom,new_body))
        
        edge_rules.append(Rule(edge_atom,new_body))

    return (edge_rules,"dcedge/"+str(goal_arity))


def ground(dbfile,progfile,query,out_grounding_file):

    with open(progfile,"r") as f:
        program = f.read().splitlines()
        programwithoutquery = [line for line in program if line.strip() and not line.endswith("?")]
        
        programwithgoal = []
        programwithgoal.extend(programwithoutquery)
        programwithgoal.append(query+"?")

        programwithgoal = "\n".join(programwithgoal)

        arity = str(query).count(',')+1
        bstring = "b"*arity
        fstring = "f"*arity
        
        dlv_proc = subprocess.run([DLV_PATH, "--silent", "--no-projection", "--print-rewriting", "--stdin"], stdout=subprocess.PIPE, stderr=subprocess.PIPE, input=programwithgoal, encoding='ascii')
        magic_rew_raw = dlv_proc.stderr

        magic_rew_raw = magic_rew_raw.replace('#','')
        magic_rew_program = [line for line in magic_rew_raw.splitlines() if not line.startswith("-")]

        magic_rew_program, _, _ = parse_program(magic_rew_program)

        goal_atom,_ = parse_atom(query,0)
        magic_goalb = Rule(Atom("magic"+goal_atom.predicate+bstring,goal_atom.terms),None)
        magic_goalf = Rule(Atom("magic"+goal_atom.predicate+fstring,None),None)

        magic_rew_program.insert(0,magic_goalf)
        magic_rew_program.insert(0,magic_goalb)

        #create edge rules
        edge_rules,goal_predicate = create_edge_rules(programwithoutquery,goal_atom)
        magic_rew_program.extend(edge_rules)
        final_program = "\n".join([str(r) for r in magic_rew_program])+"\n\n"
        
        #end edge rules
                
        with open(out_grounding_file,"w") as out_f:
            dlv_proc = subprocess.run([DLV_PATH, f"--filter={goal_predicate}", "--silent", dbfile, "--stdin"], stdout=out_f, stderr=subprocess.PIPE, input=final_program, encoding='ascii')
            print(dlv_proc.stderr)

def main():
    argc = len(sys.argv)
    if argc != 3 and argc != 4:
        print(f"Usage: {sys.argv[0]} <scenario name> <out dir> [database name]")
        sys.exit(0)

    scenarioname = sys.argv[1]
    base_outdir = sys.argv[2]

    scenariodir = f"{base_outdir}/datasets/{scenarioname}"
    progfile = f"{scenariodir}/program.lp"
    querydir = f"{scenariodir}/queries/"
    groundingdir = f"{base_outdir}/downward/{scenarioname}"
            
    os.makedirs(groundingdir, exist_ok=True)

    queryfiles= [qfile for qfile in os.listdir(querydir) if qfile.endswith('.txt')]

    if argc == 4:
        queryfiles=[sys.argv[3]+".txt"]

    
    with open(os.path.join(base_outdir,"times","ground_times.tsv"),"a") as ground_time_file:
        time_csv_writer = csv.writer(ground_time_file,delimiter="\t")

        for qfile in queryfiles:
            dbname = os.path.splitext(qfile)[0]

            dbfile = f"{scenariodir}/databases/{dbname}.facts"
            
            print(f"The database is: {dbname}")
            
            query_data = ""
            qfile_fullpath = os.path.join(querydir,qfile)

            with open(qfile_fullpath,"r") as f:
                query_data = [q for q in f.read().splitlines() if q.strip()]

            for query in query_data:
                print(f"  Grounding with query: {query}")
                
                groundingfile = f"{groundingdir}/{dbname}_{query}.lp"
                if os.path.exists(groundingfile):
                    os.remove(groundingfile)
                
                start_time = datetime.datetime.now()
                ground(dbfile,progfile,query,groundingfile) 
                end_time = datetime.datetime.now()

                elapsed = int((end_time - start_time).total_seconds() * 1000)
                time_csv_writer.writerow((scenarioname,dbname,query,elapsed))

if __name__ == "__main__":
    main()

    
