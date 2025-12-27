import sys
import os
import datetime
import csv
import subprocess

EXP_PATH = os.path.join(os.path.dirname(os.path.realpath(__file__)),"bin","whysat-bin")

def main():
    argc = len(sys.argv)
    if argc != 6 and argc != 7:
        print(f"Usage: {sys.argv[0]} <scenario name> <out dir> <timeout> <num-supports> <num-initial-supports> [database name]")
        sys.exit(0)

    scenarioname=sys.argv[1]
    base_outdir = sys.argv[2]
    scenariodir = f"{base_outdir}/datasets/{scenarioname}"
    groundingdir = f"{base_outdir}/downward/{scenarioname}"
    querydir = f"{scenariodir}/queries/"

    queryfiles= [qfile for qfile in os.listdir(querydir) if qfile.endswith('.txt')]

    if argc == 7:
        queryfiles=[sys.argv[6]+".txt"]

    timeout = sys.argv[3]
    supports = sys.argv[4]
    initial_supports = sys.argv[5]
        
    with open(os.path.join(base_outdir,"times","exp_times.tsv"),"a") as exp_time_file:
        with open(os.path.join(base_outdir,".","models.txt"),"a") as models_file:

            time_csv_writer = csv.writer(exp_time_file,delimiter="\t")

            for qfile in queryfiles:
                dbname = os.path.splitext(qfile)[0]

                print(f"The database is: {dbname}")

                query_data = ""
                qfile_fullpath = os.path.join(querydir,qfile)

                with open(qfile_fullpath,"r") as f:
                    query_data = [q for q in f.read().splitlines() if q.strip()]

                for query in query_data:
                    print(f"  Explaining query: {query}")

                    groundingfile = f"{groundingdir}/{dbname}_{query}.lp"

                    exp_proc = subprocess.run(["timeout","-s", "SIGTERM", timeout, EXP_PATH, "--goal", query, "--num-supports", supports, "--num-initial-supports", initial_supports, "--print", groundingfile], stdout=subprocess.PIPE, stderr=subprocess.PIPE, encoding='ascii')

                    ret_code = exp_proc.returncode
                    exp_out = exp_proc.stdout
                    models_out = exp_proc.stderr
                    exp_data = exp_out.split(' ')
                    models_data = models_out.split('!')
                    timedout = False
                    if ret_code == 124: #terminated earlier
                        print("Timed out")
                        timedout = True
                        #if exp_data[-1]: #drop last partial result
                        #    exp_data[:len(exp_data)-1]

                    row=[scenarioname,dbname,query,timedout]
                    row.extend(exp_data)
                    time_csv_writer.writerow(row)
                    models_file.write(scenarioname+" "+dbname+" "+query+"\n")
                    for model in models_data:
                        models_file.write(model+"\n")
                    models_file.flush()
                    exp_time_file.flush()


if __name__ == "__main__":
    main()

    
