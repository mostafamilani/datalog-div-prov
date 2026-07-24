# datalog-div-prov

The repository accompanies the draft paper *Diverse Why-Provenance for
Datalog Queries*. In addition to the implementation, it includes a compact
[experimental-results artifact](results/README.md) with a raw solver log,
51,164 experiment measurements, plotting code, and representative figures.

## What Is Included

- `code/java/`: Java batch runner, model enumeration, and related utilities.
- `code/python/`: scripts for grounding, preprocessing, and log processing.
- `code/c/`: native solver support code.
- `code/lib/`: bundled runtime dependencies used by the Java code.
- `config/`: single runner configuration file.
- `scripts/`: helper scripts for common execution modes.
- `examples/`: small downward-closure inputs for validating an installation.
- `results/`: paper measurements, plotting scripts, figures, and a raw example
  log; see the [results guide](results/README.md).

## Requirements

- Linux
- Java 21
- Python 3.10+
- Native libraries required by the bundled solver components
- Optional: Gurobi, if you use a mode that depends on it

## Basic Usage

### Compile

```bash
make compile
```

### Run on a single downward-closure file

```bash
./scripts/run_lp_single.sh <downward_file.lp> <out_dir> <config.properties> [k] [print] [proof] [mode]
```

Example:

```bash
./scripts/run_lp_single.sh path/to/file.lp out/single config/runner.properties 1 true dag unambiguous
```

### Run on a folder of downward-closure files

```bash
make run-batch INPUT_DIR=path/to/downward OUTPUT_DIR=out/logs/LP K=2 PRINT=true PROOF=dag MODE=unambiguous CONFIG=config/runner.properties
```

### Generate downward-closure files

```bash
python3 code/python/build-downward-closure.py <scenario-name> <base-out-dir> [database-name]
```

## Example

The repository includes a small verified sample input at:

```text
examples/downward/graph_analytics/bitcoin_path_9005418_9005413.lp
```

Its exact contents are:

```prolog
{dcedge(2,2,path,9005418,9005413,e,9005418,9005413,pnil,nil,nil)}
```

Run it with:

```bash
./scripts/run_lp_single.sh \
	examples/downward/graph_analytics/bitcoin_path_9005418_9005413.lp \
	out/test-example \
	config/runner.properties \
	1 \
	true \
	dag \
	unambiguous
```

This creates the log file:

```text
out/test-example/bitcoin_path_9005418_9005413-initial-1-total-0-proof-dag-ch-unambiguous.lp.log
```

A successful run produces output like:

```text
Number of variables : 4
Number of clauses   : 6
Number of acyclicity variables : 0
Number of acyclicity clauses : 0
Number of DB variables (facts) : 1

Build time    : 322 ms
Program creation time (variables+constraints): 327 ms

Solving initial phase to find 1 explanation(s)...
Initial phase time : 0 ms
Initial diversity  : 0
-- Initial Model #0 --
	e(9005418,9005413) = true
	(#true vars: 1)
End-to-end time        : 406 ms
Exit!
```

## Validated Configurations

The following commands were validated on 2026-07-23 using the simplified option surface.

### 1) Single-file, dag + unambiguous

```bash
make run-single \
	INPUT_FILE=examples/downward/graph_analytics/bitcoin_path_9005418_9005413.lp \
	OUT_DIR=out/readme-validate/dag-unambig \
	K=1 \
	PRINT=true \
	PROOF=dag \
	MODE=unambiguous \
	CONFIG=config/runner.properties
```

Generated log:

```text
out/readme-validate/dag-unambig/bitcoin_path_9005418_9005413-initial-1-total-0-proof-dag-ch-unambiguous.lp.log
```

Excerpt:

```text
RUN @ 2026-07-23T20:53:15.473737003-04:00
goal          : path(9005418,9005413)
numInitial    : 1
proof         : dag
mode          : unambiguous
Initial phase time : 0 ms
-- Initial Model #0 --
	e(9005418,9005413) = true
	(#true vars: 1)
End-to-end time        : 406 ms
Exit!
```

### 2) Single-file, hypertree + arbitrary

```bash
make run-single \
	INPUT_FILE=examples/downward/graph_analytics/bitcoin_path_9005418_9005413.lp \
	OUT_DIR=out/readme-validate/hypertree-arbitrary \
	K=1 \
	PRINT=true \
	PROOF=hypertree \
	MODE=arbitrary \
	CONFIG=config/runner.properties
```

Generated log:

```text
out/readme-validate/hypertree-arbitrary/bitcoin_path_9005418_9005413-initial-1-total-0-proof-hypertree-ch-arbitrary.lp.log
```

Excerpt:

```text
RUN @ 2026-07-23T20:54:19.421888121-04:00
goal          : path(9005418,9005413)
numInitial    : 1
proof         : hypertree
mode          : arbitrary
Initial phase time : 1 ms
-- Initial Model #0 --
	e(9005418,9005413) = true
	(#true vars: 1)
End-to-end time        : 151 ms
Exit!
```

### 3) Batch run over graph_analytics folder

```bash
make run-batch \
	INPUT_DIR=examples/downward/graph_analytics \
	OUTPUT_DIR=out/readme-validate/batch \
	K=1 \
	PRINT=true \
	PROOF=dag \
	MODE=unambiguous \
	CONFIG=config/runner.properties
```

Generated log:

```text
out/readme-validate/batch/bitcoin_path_9005418_9005413-initial-1-total-0-proof-dag-ch-unambiguous.lp.log
```

Excerpt:

```text
RUN @ 2026-07-23T20:53:27.269116856-04:00
goal          : path(9005418,9005413)
numInitial    : 1
proof         : dag
mode          : unambiguous
Initial phase time : 0 ms
-- Initial Model #0 --
	e(9005418,9005413) = true
	(#true vars: 1)
End-to-end time        : 126 ms
Exit!
```

## Configuration

Main defaults live in `config/runner.properties`.

Useful settings include:

- `defaults.k`
- `defaults.print`
- `batch.processTimeoutMinutes`
- `lp.maxInitial`
- `lp.maxIteration`
- `lp.processTimeoutMinutes`

## Canonical Options

The run flow uses only these options:

- `proof`: `dag` or `hypertree`
- `mode`: `arbitrary` or `unambiguous`
- `k`: number of initial explanations
- `print`: print explanation facts (`true`/`false`)

## Output

The Java runners write logs and outputs into the output directory you choose. The exact filenames depend on the input file, selected proof mode, characterization mode, and timeout settings.

## Paper Results

The publication artifact is documented in
[`results/README.md`](results/README.md). It includes a raw reproducible log,
the merged CSV used by the paper plotting pipeline, Python dependencies and an
exact plot-regeneration command, and representative exact-runtime,
approximate-runtime, and approximation-quality figures.

For example, see the
[Facebook approximation-quality figure](results/figures/rate-graph_analytics-facebook-unambigous-dag.pdf).

The included snapshot supports inspection and plot regeneration. Re-running
the complete benchmark suite additionally requires the benchmark databases and
generated downward-closure inputs; the checked-in examples are intended for
installation validation rather than full-paper reproduction.

## Citation

Paper citation details can be added here later.
