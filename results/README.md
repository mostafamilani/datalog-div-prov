# Experimental results

This directory contains a compact, inspectable snapshot of the experiments
reported in the draft paper *Diverse Why-Provenance for Datalog Queries*.
It answers three separate reproducibility questions:

1. What does a successful solver run look like?
2. What measurements were used to produce the paper plots?
3. How can the plots be regenerated?

## Contents

- `logs/`: a raw log from the small public example in the repository.
- `data/results_merged.csv`: 51,164 experiment records used by the plotting
  pipeline.
- `scripts/generate_plots.py`: plotting program used for the runtime and
  approximation-quality plots.
- `figures/`: representative PDF figures for two contrasting datasets.

The CSV columns are:

| Column | Meaning |
| --- | --- |
| `dataset` | Scenario/dataset identifier |
| `lp_file` | Original downward-closure input path |
| `goal` | Query answer being explained |
| `k` | Number of explanations requested |
| `vars` | Number of encoding variables, where recorded |
| `diversity` | Diversity of the returned explanation set |
| `time_ms` | End-to-end runtime in milliseconds, or a timeout marker |
| `mode` | `optimal` (exact) or `greedy` (approximate) |
| `source` | Proof representation and provenance semantics |
| `log_file` | Original experiment log path |

`lp_file` and `log_file` preserve paths from the experiment machine for
traceability. They are metadata and are not expected to exist on another
machine.

## Reproduce the included raw log

From the repository root:

```bash
make run-single \
	INPUT_FILE=examples/downward/graph_analytics/bitcoin_path_9005418_9005413.lp \
	OUT_DIR=out/reproduce-example \
	K=1 \
	PRINT=true \
	PROOF=dag \
	MODE=unambiguous \
	CONFIG=config/runner.properties
```

Compare the resulting log with the file in `logs/`. Runtime and memory values
will vary by machine; the goal, model count, explanation facts, and diversity
should agree.

## Regenerate plots

Create an isolated Python environment and run:

```bash
python3 -m venv .venv
source .venv/bin/activate
python3 -m pip install -r results/requirements.txt
python3 results/scripts/generate_plots.py \
	--csv results/data/results_merged.csv \
	--output-dir out/paper-plots \
	--k-min 1 \
	--k-max 5
```

The script produces one PDF per dataset, algorithm, proof representation, and
provenance semantics, plus approximation-ratio plots. This fine-grained output
corresponds to the panels composed into the paper figures.

## Included figure guide

The six PDFs give exact runtime, approximate runtime, and approximation quality
for:

- CSDA on `httpd`, using unambiguous provenance with the DAG encoding.
- Graph analytics on `facebook`, using unambiguous provenance with the DAG
  encoding.

The runtime PDFs use milliseconds on a logarithmic axis. The `rate-*` PDFs plot
the ratio `optimal diversity / approximate diversity`; a value of `1` means the
approximate algorithm matched the optimum.

## Scope and limitations

This is a results snapshot, not a claim that every experiment can be rerun from
the two small inputs currently checked into `examples/`. Full end-to-end
reproduction of every paper figure also requires the benchmark databases and
all generated downward-closure inputs described in the paper. The snapshot
does, however, make the reported measurements and plotting pipeline available
for inspection and figure regeneration.
