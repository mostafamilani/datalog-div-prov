# Diverse Why-Provenance for Datalog Queries: Implementation Artifact

This repository accompanies *Diverse Why-Provenance for Datalog Queries*.
It provides ILP-based computation of diverse why-provenance explanations,
experiment runners, preprocessing tools, and an
[experimental-results artifact](results/README.md) for the exact and
approximation algorithms studied in the paper. This README follows the
terminology, notation, and broader scope of the **arXiv extended draft**,
including both arbitrary and unambiguous proof trees.

## Datalog and Why-Provenance

A Datalog query consists of a program of logical rules and an output predicate.
The rules derive intensional (IDB) facts from extensional (EDB) facts in an input
database. A query answer is a tuple derived for the output predicate.

A **proof tree** is a rooted tree whose nodes are labeled with facts. The root
is labeled with the answer atom, each internal node is justified by a ground
rule instance whose body facts label its children, and leaves are labeled with
database facts. Its **support** is the set of facts labeling its leaves. A
**why-provenance explanation** is such a support, viewed as a subset of the
input database. Different proof trees can have the same support, so distinct
explanations are distinct sets of database facts. Explanations are not required
to be inclusion-minimal.

An **unambiguous proof tree** requires that any two nodes labeled with the same
fact have isomorphic rooted, labeled subtrees. Thus, each repeated fact has a
consistent derivation within that tree. The implementation also supports
**arbitrary proof trees**, without this restriction. The family of supports of
unambiguous proof trees can be a strict subset of the family obtained from
arbitrary proof trees; both semantics preserve the existence of an explanation
for every query answer.

Following the arXiv draft, `why_ALL(t, D, Q)` denotes the family of supports
of arbitrary proof trees, and `why_UN(t, D, Q)` denotes the family of supports
of unambiguous proof trees. Here, `Q` is the Datalog query, `D` is the input
database, and `t` is the answer tuple. We have
`why_UN(t, D, Q) ⊆ why_ALL(t, D, Q)`.

## Diverse Why-Provenance

The task is to select a set of **k distinct why-provenance explanations** for
one query answer that maximizes their diversity. The arXiv draft calls this
optimization problem **★-DiverseWhyProvenance**, where `★` is `ALL` or `UN`
according to the chosen proof-tree family. Thus, the two settings are
`ALL-DiverseWhyProvenance` and `UN-DiverseWhyProvenance`.

For two explanations, their distance is the number of database facts in their
symmetric difference. The diversity of a set is the sum of these distances
over all unordered pairs:

$$
d(D_i,D_j)=|(D_i\setminus D_j)\cup(D_j\setminus D_i)|,
\qquad
\delta(\{D_1,\ldots,D_k\})=\sum_{1\leq i<j\leq k} d(D_i,D_j).
$$

The exact task returns one set of maximum diversity, if at least `k`
distinct explanations exist; otherwise it fails. It does not require
enumerating every proof tree or every explanation. For `k=1`, the diversity is
zero, and any explanation is optimal.

### Exact and Approximation Algorithms

The arXiv draft uses **CompDiverse[BuildILPEnc]** for the generic exact algorithm.
It constructs an integer linear programming (ILP) encoding of the selected
why-provenance semantics and jointly optimizes a set of `k` explanations.
The simplified Make and shell commands below run this exact computation.

The arXiv draft uses **Approx[BuildILPEnc]** for the generic approximation algorithm.
It selects an initial explanation and repeatedly adds a distinct explanation
that maximizes the **marginal diversity**, the increase in the diversity of the
selected set. The arXiv draft proves a **2-approximation** guarantee: the resulting
set has at least half the optimal diversity, assuming the required optimization
subproblems are solved optimally. The results artifact contains measurements
for both exact and approximation algorithms. The advanced Java interface
exposes the initial and iterative selection phases separately.

### ILP Encodings

The implementation offers a **hypergraph-based ILP encoding** and a
**graph-based ILP encoding**. The latter uses **proof DAGs** (directed acyclic
graphs). These are encodings of explanations; a returned explanation
is still a set of database facts.

The CLI preserves the label `hypertree` for the hypergraph-based encoding.
The arXiv notation maps to the CLI settings as follows:

| CLI settings | ILP encoding |
| --- | --- |
| `PROOF=hypertree MODE=arbitrary` | `hyper-ILP_ALL` |
| `PROOF=hypertree MODE=unambiguous` | `hyper-ILP_UN` |
| `PROOF=dag MODE=arbitrary` | `graph-ILP_ALL` |
| `PROOF=dag MODE=unambiguous` | `graph-ILP_UN` |

For any encoding in the table, `CompDiverse[encoding]` names the exact
algorithm and `Approx[encoding]` names the approximation algorithm; for
example, `CompDiverse[hyper-ILP_UN]` and `Approx[hyper-ILP_UN]`.
`PROOF` selects the encoding, while `MODE` selects the proof-tree family.
Neither option selects between exact and approximate computation.

### Downward Closure

The **downward closure** of the answer atom is the subhypergraph of the
**graph of rule instances (GRI)** reachable from that atom. Its nodes are facts;
a hyperedge connects the head of a ground rule instance to its body facts.
It combines the possible proof trees relevant to the answer in a finite
representation used to construct the ILP encodings.

The Python preprocessing scripts serialize this hypergraph as `.lp` files
containing `dcedge(...)` facts. The Java runners read these files, construct the
chosen encoding, and optimize over its database variables to obtain supports.

The simplified workflow is:

1. Generate a downward-closure file for a query answer.
2. Choose an ILP encoding (`PROOF=dag|hypertree`) and a proof-tree family
   (`MODE=unambiguous|arbitrary`).
3. Set the diversity parameter `K` and compute a set of `K` explanations with
   maximum diversity using the exact algorithm.
4. Inspect the runtime, diversity, and explanation facts in the logs.

## What This Repository Provides

- Java runners for ILP-based diverse explanation computation and batch execution.
- Native solver libraries, JNI bindings, and upstream native sources.
- Python scripts for preprocessing and downward-closure generation.
- Small downward-closure inputs for installation validation.
- An experimental-results snapshot with merged measurements, plotting scripts,
  and representative figures for exact and approximation algorithms.

Main directories:

- `code/java/`: ILP runners and additional enumeration utilities.
- `code/c/`: symlink to the upstream native solver sources.
- `code/java/native/`: symlink to the upstream JNI sources.
- `external/marco/`: pinned Git submodule of Marco Calautti's repository.
- `code/lib/`: bundled Java and native solver dependencies.
- `code/python/`: preprocessing and grounding helpers.
- `config/`: default runtime configuration.
- `scripts/`: convenience wrappers.
- `examples/downward/`: small validation inputs.
- `results/`: experimental-results artifact.

## Upstream Native Source

The solver and JNI sources come from Marco Calautti's
[Datalog Diverse Why-Provenance repository](https://gitlab.com/mcalautti/datalog-diverse-why-provenance).
They are referenced through the Git submodule at `external/marco/`:

- `code/c` links to `external/marco/why-sat/src`.
- `code/java/native` links to `external/marco/why-sat/java/native`.

This repository tracks the submodule reference and these relative symlinks;
the C++ sources and headers are tracked by the upstream repository.

The submodule is pinned to commit
[`4873700e11412c9242b6249f7aea0f488982f8ef`](https://gitlab.com/mcalautti/datalog-diverse-why-provenance/-/commit/4873700e11412c9242b6249f7aea0f488982f8ef),
which exactly matches all 19 previously copied solver and JNI source/header
files. This preserves the native source version used by this project. The upstream
code retains its own [license](external/marco/LICENSE).

Clone this project and initialize the upstream source:

```bash
git clone https://github.com/mostafamilani/datalog-div-prov.git
cd datalog-div-prov
git submodule update --init external/marco
```

For an existing checkout, run the same `git submodule update` command after
pulling changes. Initialize only `external/marco` for this project's bundled
Java/JNI workflow: the pinned upstream revision has an incomplete nested
submodule configuration, so recursive initialization can fail. To rebuild the
upstream native solver, consult its [build instructions](external/marco/why-sat/README.md)
and resolve those native dependencies separately.

GitHub's source ZIP does not include the submodule's files; use Git and the
initialization command above to obtain them. After switching project revisions,
rerun that command to check out the upstream commit recorded by the project.

## Requirements

- Linux
- Java 21
- Python 3.10+
- Native libraries required by bundled solver components in `code/lib/`
- Gurobi license if your selected run mode requires it

## Quick Start

Compile Java sources:

```bash
make compile
```

Run one included example:

```bash
make run-single \
  INPUT_FILE=examples/downward/graph_analytics/bitcoin_path_9005418_9005413.lp \
  OUT_DIR=out/test-example \
  K=1 \
  PRINT=true \
  PROOF=dag \
  MODE=unambiguous \
  CONFIG=config/runner.properties
```

Expected log file:

```text
out/test-example/bitcoin_path_9005418_9005413-initial-1-total-0-proof-dag-ch-unambiguous.lp.log
```

## Computing Diverse Explanations

### Option Semantics

Canonical options for the simplified workflow are:

- `PROOF`: ILP encoding; `dag` (graph-based) or `hypertree` (hypergraph-based).
- `MODE`: proof-tree family; `unambiguous` or `arbitrary`.
- `K`: diversity parameter, the requested number of distinct explanations.
- `PRINT`: `true`/`false`; whether to print the facts of each explanation in logs.

Defaults come from [config/runner.properties](config/runner.properties):

- `defaults.k=2`
- `defaults.print=true`
- `defaults.proof=dag`
- `defaults.mode=unambiguous`

The current configuration sets `lp.processTimeoutMinutes=30` and
`batch.processTimeoutMinutes=150`. The experiments reported in the arXiv draft use
a 10-minute timeout; the plotting script also uses that threshold. Set runtime
limits explicitly when reproducing the experimental setup.

### Run a Single Downward File

Using Make:

```bash
make run-single \
  INPUT_FILE=path/to/file.lp \
  OUT_DIR=out/single \
  K=2 \
  PRINT=true \
  PROOF=dag \
  MODE=unambiguous \
  CONFIG=config/runner.properties
```

Using the shell wrapper:

```bash
./scripts/run_lp_single.sh <downward_file.lp> <out_dir> <config.properties> [k] [print] [proof] [mode]
```

Wrapper defaults:

- `k=2`
- `print=true`
- `proof=dag`
- `mode=unambiguous`

### Run a Batch of Downward Files

```bash
make run-batch \
  INPUT_DIR=path/to/downward \
  OUTPUT_DIR=out/logs/LP \
  K=2 \
  PRINT=true \
  PROOF=dag \
  MODE=unambiguous \
  CONFIG=config/runner.properties
```

`run-batch` recursively scans `INPUT_DIR` for `.lp` files and writes per-input logs under `OUTPUT_DIR`.

### Advanced Direct Java Invocation (Optional)

`LPModelEnumerator` supports a wider positional CLI:

```text
java LPModelEnumerator <downwardFile> <outDir> [numModels] [numInitialModels] [print] [extraAnalysis] [proof] [mode] [metricsOnly] [configFile]
```

Important behavior of `numModels`:

- `-1`: add explanations iteratively until none remain or `lp.maxIteration`
  additional explanations have been selected.
- `0`: jointly optimize only the initial set of `numInitialModels` explanations.
- `>0`: target this total number of explanations, including the initial set;
  stop earlier if no further explanation exists.

The parameter names and the word "Model" in logs are implementation labels;
the extracted database facts represent why-provenance explanations. The
Make/shell workflow uses `numModels=0` and `numInitialModels=K` for exact
computation of a set of `K` explanations with maximum diversity.

To follow the greedy selection used by `Approx`, set `numInitialModels=1` and
`numModels=k` in the advanced interface. With a larger initial set, the runner
first optimizes that set jointly and then extends it greedily; this is a
different initialization from Algorithm 2 in the arXiv draft.

## Downward-Closure Generation

Downward-closure generation is implemented in `code/python/` and produces the `.lp` inputs consumed by the Java ILP-based computation pipeline.

At a high level, the generator:

1. Reads a scenario program, database facts, and one query atom.
2. Uses DLV rewriting (`--print-rewriting`) to focus grounding around the goal.
3. Adds edge-construction rules that encode reachable derivation structure.
4. Emits only downward-closure edge facts via a predicate filter (`dcedge/...`).

The resulting file represents the downward-closure hypergraph for one answer
atom. The ILP computation extracts explanation supports from this structure.

### Scripts

- `build-downward-closure.py`: original generator.
- `build-downward-closure-namefix.py`: safer variant for query text and filenames (normalizes Unicode/smart quotes and slugifies output filenames).

### Expected Input Layout

Given `<base_out_dir>` and `<scenario>`:

```text
<base_out_dir>/datasets/<scenario>/program.lp
<base_out_dir>/datasets/<scenario>/queries/*.txt
<base_out_dir>/datasets/<scenario>/databases/*.facts
```

Query/database pairing is by filename stem:

- `queries/db1.txt` pairs with `databases/db1.facts`

Each query line in the `.txt` file is treated as a goal atom.

### Run Commands

Generate for all query files in a scenario:

```bash
python3 code/python/build-downward-closure.py <scenario> <base_out_dir>
```

Generate for one database/query file stem only:

```bash
python3 code/python/build-downward-closure.py <scenario> <base_out_dir> <database_stem>
```

Recommended robust variant:

```bash
python3 code/python/build-downward-closure-namefix.py <scenario> <base_out_dir> [database_stem]
```

Outputs are written to:

```text
<base_out_dir>/downward/<scenario>/
```

Timing rows are appended to:

```text
<base_out_dir>/times/ground_times.tsv
```

### Prerequisite for Grounding

Both generators call a DLV2 binary at:

```text
code/python/bin/dlv2
```

Ensure this binary exists and is executable, otherwise generation fails with `FileNotFoundError` for `.../code/python/bin/dlv2`.

## Validated Commands in This Repository State

Validated on 2026-08-09:

- `make run-single` on `examples/downward/graph_analytics/bitcoin_path_9005418_9005413.lp` completed and produced the expected log naming pattern.
- `python3 code/python/build-downward-closure.py` (no args) prints correct usage.
- End-to-end downward generation currently requires providing `code/python/bin/dlv2` in this checkout.

## Results Artifact

The experimental-results artifact is described in [results/README.md](results/README.md). It includes:

- raw representative logs,
- merged CSV used for plotting,
- plotting scripts and dependencies,
- representative output figures.

The CSV column `mode` uses `optimal` for exact computation and `greedy` for
approximation. This algorithm label is separate from CLI `MODE`, which selects
the proof-tree family. Historical source identifiers and filenames retain
`hypertree`, `standard` (arbitrary proof trees), and the misspelling `unambigous`;
these labels are preserved for compatibility with the measurements and plots.

Approximation quality is measured by the ratio of optimal diversity to
approximate diversity; a ratio of one means that the approximation attains the
optimal diversity. For `k=1`, both diversities are zero, and the plotting script
reports the ratio as one by convention.

The snapshot supports inspection and plot regeneration. Full benchmark reruns
additionally require the benchmark databases and generated downward-closure
inputs described in the arXiv draft.

## Citation

Paper citation details can be added here when finalized.
