import sys
import csv

if len(sys.argv) != 3:
    print("Usage: <csv-file> <predicate-name>")
    sys.exit()

pred_name = sys.argv[2]

with open(sys.argv[1],'r') as input:
    reader = csv.reader(input,delimiter=',')
    for line in reader:
        tuple = ",".join(["\""+term+"\"" if (term[0].isupper() or term[0].isnumeric() or term[0] == '_') else term for term in line])
        print("{}({}).".format(pred_name,tuple))

