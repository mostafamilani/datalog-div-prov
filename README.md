# datalog-div-prov

Lightweight artifact repository for exact Datalog explanation enumeration.

This repository is a clean release variant of our internal project. It is prepared for:

- Paper readers and reviewers who want a reproducible, minimal setup.
- Collaborators (for example Marco) who want to modify the exact algorithm and test runtime ideas.

## Scope

The artifact supports two workflows:

1. End-to-end workflow:
- Start from Datalog input files and queries.
- Generate downward-closure encodings.
- Enumerate exact explanations.

2. Downward-ready workflow:
- Start from precomputed downward closure `.lp` files.
- Run exact enumeration directly.

Keeping both workflows makes experiments modular. Downward-closure generation and exact optimization can be benchmarked independently.

## Repository Layout

- `code/java/`: batch runner and exact model enumerators.
- `code/python/`: scripts for data preparation and downward-closure generation.
- `code/c/`: native solver components.
- `code/lib/`: runtime jars/native libraries used by Java code.
- `config/`: runner configuration profiles.
- `scripts/`: helper scripts for reproducible runs.

## Requirements

- Linux
- Java 21
- Python 3.10+
- Native solver dependencies expected by `code/lib/libwhysat.so`
- Optional: Gurobi installation/license if your chosen run mode uses it

## Quick Start

### 1) Compile Java

```bash
make compile
```

### 2) Run exact enumeration from precomputed downward closure files

Use either `BatchRunner` (folder-level) or `LPModelEnumerator` (single file).

Folder-level example:

```bash
make run-batch-lp \
	INPUT_DIR=examples/downward \
	OUTPUT_ROOT=out/logs/LP/ \
	PROOF=dag \
	CH=unambiguous \
	CONFIG=config/runner.properties
```

Single-file example:

```bash
./scripts/run_lp_single.sh \
	/absolute/path/to/downward_file.lp \
	out/single \
	config/runner.properties \
	dag \
	unambiguous
```

### 3) Generate downward closure (end-to-end mode)

The script below generates downward-closure files from scenario inputs:

```bash
python3 code/python/build-downward-closure.py <scenario-name> <base-out-dir> [database-name]
```

After generation, run the enumeration step on the produced `.lp` files.

## Configuration

Main defaults are in `config/runner.properties`.

Useful knobs:

- `defaults.numModels`
- `defaults.numInitialModels`
- `defaults.printModels`
- `defaults.extraAnalysis`
- `batch.processTimeoutMinutes`
- `lp.maxInitial`
- `lp.maxIteration`
- `lp.processTimeoutMinutes`

## For Algorithm Extensions

Two natural extension points for runtime optimization experiments:

1. Cross-solution reuse across k or across consecutive exact solutions.
2. Incremental/overlapping optimization state reuse in LP/MIP solving.

Recommended implementation locations:

- `code/java/LPModelEnumerator.java`
- `code/java/BatchRunner.java`

## Notes

- This artifact intentionally excludes large experiment outputs and machine-local files.
- Keep heavyweight datasets and logs in separate private/internal storage.
