# Diverse Why-Provenance for Datalog Queries: Implementation Artifact

The repository accompanies the draft paper *Diverse Why-Provenance for
Datalog Queries*. In addition to the implementation, it includes a compact
[experimental-results artifact](results/README.md) with a raw solver log,
51,164 experiment measurements, plotting code, and representative figures.

## Background: Datalog and Why-Provenance

Datalog is a rule-based query language where new facts are inferred from
existing facts by repeatedly applying logical rules until no new facts can be
derived. In this setting, base input facts are often called extensional facts
(EDB), while facts produced by rules are intensional facts (IDB).

Why-provenance asks why a specific query answer is true. For Datalog, the
answer is explained by identifying sets of EDB facts that can derive the goal
through valid rule applications. Since the same answer may admit many such
supports, this repository focuses on computing multiple exact explanations and
comparing them under different structural assumptions.

## Technical Context

### Why Multiple Explanations Matter

For a Datalog answer, there is often more than one valid derivation. If we only return one explanation, we hide alternatives that may be simpler, more robust, or rely on different base facts. This repository is designed to compute several exact explanations and expose structural choices between them.

### What a Proof Tree Means Here

Intuitively, a proof tree is a derivation witness for one goal fact:

- The root is the goal fact being explained.
- Each internal node corresponds to one rule application producing that fact.
- Children are the body facts needed by that rule instance.
- Leaves are database facts (EDB facts).

The explanation itself is the set of leaf database facts that support the root. Different trees can produce different supports, so explanation enumeration is fundamentally a search over proof trees.

### Arbitrary vs Unambiguous Proof Trees

This code supports two proof-tree families:

- `mode=arbitrary`: allows all valid proof trees.
- `mode=unambiguous`: enforces consistency in how repeated derived facts are justified.

The unambiguous mode avoids mixing incompatible local derivation choices for the same intermediate fact inside one explanation. In practice, this yields explanations that are usually easier to read and reason about, while still preserving completeness of answer semantics.

### Proof Structure Options: dag vs hypertree

The `proof` option controls the internal encoding family used by the solver:

- `proof=dag`: works with a DAG-style representation that can reuse shared sub-derivations.
- `proof=hypertree`: works with a hypergraph-oriented representation of rule instantiations.

Both target exact explanation computation, but they expose different structural views of the same derivation space and can behave differently in runtime and memory depending on how connected the downward closure is.

### Why Downward Closure Exists

Enumerating proof trees directly can explode combinatorially. The repository therefore relies on a compact intermediate object called the downward closure of a goal:

- It is a rooted, finite representation of all rule instantiations reachable from the goal.
- It compactly encodes the proof search space before optimization.
- The Java solvers then select edge sets inside this structure that correspond to valid explanations under the selected proof/mode semantics.

Operationally, the Python builder scripts generate this structure as `.lp` files (containing `dcedge(...)` facts). The Java runners consume those files to enumerate explanations.

### End-to-End View

1. Build a downward-closure file for a target goal.
2. Choose proof structure (`PROOF=dag|hypertree`) and characterization mode (`MODE=unambiguous|arbitrary`).
3. Run exact explanation enumeration for the first `K` solutions.
4. Inspect generated logs and extracted supporting facts.

## What This Repository Provides

- Java implementation of exact explanation enumeration and batch execution.
- Native SAT/MaxSAT/JNI components used by the Java layer.
- Python scripts for preprocessing and downward-closure generation.
- Checked-in, small downward examples for installation validation.
- A compact experimental artifact in [results/README.md](results/README.md), including merged measurements and plotting scripts.

Main directories:

- `code/java/`: runners and model enumerators.
- `code/c/`: native solver support.
- `code/python/`: preprocessing and grounding helpers.
- `config/`: default runtime configuration.
- `scripts/`: convenience wrappers.
- `examples/downward/`: small validation inputs.
- `results/`: paper artifact snapshot.

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

## Running Explanations

### Option Semantics

Canonical options for the simplified workflow are:

- `PROOF`: `dag` or `hypertree`
- `MODE`: `unambiguous` or `arbitrary`
- `K`: number of initial explanations to compute
- `PRINT`: `true`/`false`, prints model facts in logs

Defaults come from [config/runner.properties](config/runner.properties):

- `defaults.k=2`
- `defaults.print=true`
- `defaults.proof=dag`
- `defaults.mode=unambiguous`

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

- `-1`: iterative enumeration until saturation (capped by config `lp.maxIteration`)
- `0`: compute only initial explanations (`numInitialModels`)
- `>0`: total target models (initial + iterative)

The Make/shell simplified flow uses `numModels=0` (initial explanations only).

## Downward-Closure Generation

Downward-closure generation is implemented in `code/python/` and produces the `.lp` inputs consumed by the Java enumeration pipeline.

At a high level, the generator:

1. Reads a scenario program, database facts, and one query atom.
2. Uses DLV rewriting (`--print-rewriting`) to focus grounding around the goal.
3. Adds edge-construction rules that encode reachable derivation structure.
4. Emits only downward-closure edge facts via a predicate filter (`dcedge/...`).

The resulting file is a compact proof-search graph for one goal, not a final explanation by itself.

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

The publication artifact is described in [results/README.md](results/README.md). It includes:

- raw representative logs,
- merged CSV used for plotting,
- plotting scripts and dependencies,
- representative output figures.

The checked-in snapshot is meant for inspection and plot regeneration. Full benchmark reruns additionally require external benchmark databases and generated downward-closure inputs.

## Citation

Paper citation details can be added here when finalized.
