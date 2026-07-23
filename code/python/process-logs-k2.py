# -*- coding: utf-8 -*-
# process-logs-k2.py
from pathlib import Path
import re
import csv
from typing import List, Dict, Optional

# --------- regex patterns (top-level fields) ---------
RE_DOWNWARD_FILE = re.compile(r"^downwardFile\s*:\s*(.+\.lp)\s*$", re.MULTILINE)
RE_GOAL         = re.compile(r"^goal\s*:\s*(.+)\s*$", re.MULTILINE)
RE_VARS         = re.compile(r"^Number of variables\s*:\s*(\d+)\s*$", re.MULTILINE)
RE_CLAUSES      = re.compile(r"^Number of clauses\s*:\s*(\d+)\s*$", re.MULTILINE)
RE_ACYCLICITY_VARS = re.compile(r"^Number of acyclicity variables\s*:\s*(\d+)\s*$", re.MULTILINE)
RE_ACYCLICITY_CLAUSES = re.compile(r"^Number of acyclicity clauses\s*:\s*(\d+)\s*$", re.MULTILINE)
RE_METRICS_ONLY = re.compile(r"^metricsOnly\s*:\s*true\s*$", re.MULTILINE | re.IGNORECASE)
RE_INIT_TIME    = re.compile(r"^Initial phase time\s*:\s*(\d+)\s*ms\s*$", re.MULTILINE)
RE_PROGRAM_CREATE_TIME = re.compile(r"^Program creation time \(variables\+constraints\)\s*:\s*(\d+)\s*ms\s*$", re.MULTILINE)
RE_PROGRAM_CREATE_TIME_SO_FAR = re.compile(r"^Program creation time so far \(incomplete\)\s*:\s*(\d+)\s*ms\s*$", re.MULTILINE)
RE_BUILD_TIME   = re.compile(r"^Build time\s*:\s*(\d+)\s*ms\s*$", re.MULTILINE)
RE_INIT_DIV     = re.compile(r"^Initial diversity\s*:\s*(\d+)\s*$", re.MULTILINE)
RE_NUM_INITIAL  = re.compile(r"^numInitial\s*:\s*(\d+)\s*$", re.MULTILINE)

# Model blocks (Initial/Iterative) robust to CRLF, extra lines, etc.
RE_MODEL_BLOCK = re.compile(
    r"--\s*(?P<kind>Initial|Iterative)\s+Model\s+#(?P<idx>\d+)\s*--\s*"
    r"(?P<body>.*?)(?=^\s*--\s*(?:Initial|Iterative)\s+Model\s+#|\Z)",
    re.MULTILINE | re.DOTALL
)

# Lines inside a model block
RE_TRUE_LINE = re.compile(r"^\s*(?P<atom>.+?)\s*=\s*true\s*$", re.MULTILINE)
RE_TIME_LINE = re.compile(r"^\s*time\s*:\s*(\d+)\s*ms\s*$", re.MULTILINE)
RE_DIV_LINE  = re.compile(r"^\s*diversity\s*:\s*(\d+)\s*$", re.MULTILINE)
RE_EXIT_MARK = re.compile(r"^\s*(?:Exit!|Exist!)\s*$", re.MULTILINE)

# Filename policy:
# - keep greedy only from initial-2-total--1
# - keep optimal only from initial-{2,3,4,5}-total-0
# - ignore old greedy initial-1-total--1
RE_GREEDY_FILE = re.compile(r"initial-2-total--1")
RE_OPT_FILE    = re.compile(r"initial-([2-5])-total-0")
RE_METRICS_FILE = re.compile(r"metrics-only")

K_MIN = 2
K_MAX = 5


def extract_dataset_from_path(lp_path: str) -> Optional[str]:
    parts = Path(lp_path).parts
    if "downward" in parts:
        i = parts.index("downward")
        return parts[i + 1] if i + 1 < len(parts) else None
    return None


def extract_profile_from_log_path(log_path: Path) -> str:
    parts = log_path.parts
    # expected layout: .../logs/LP/<profile>/<dataset>/...log
    for i in range(len(parts) - 1):
        if parts[i] == "LP" and i + 1 < len(parts):
            return parts[i + 1]
    return ""


def symdiff_size(a: set, b: set) -> int:
    return len(a.symmetric_difference(b))


def pairwise_symdiff_sum(sets: List[set]) -> int:
    total = 0
    for i in range(len(sets)):
        for j in range(i + 1, len(sets)):
            total += symdiff_size(sets[i], sets[j])
    return total


def parse_model_blocks_with_atoms(txt: str):
    init_sets: List[set] = []
    iter_sets: Dict[int, set] = {}
    iter_time: Dict[int, int] = {}
    iter_div:  Dict[int, int] = {}

    for m in RE_MODEL_BLOCK.finditer(txt):
        kind = m.group("kind")
        idx  = int(m.group("idx"))
        body = m.group("body")

        atoms = set(a.group("atom").strip() for a in RE_TRUE_LINE.finditer(body))

        if kind == "Initial":
            while len(init_sets) <= idx:
                init_sets.append(set())
            init_sets[idx] = atoms
        else:
            iter_sets[idx] = atoms
            tm = RE_TIME_LINE.search(body)
            dv = RE_DIV_LINE.search(body)
            if tm:
                iter_time[idx] = int(tm.group(1))
            if dv:
                iter_div[idx] = int(dv.group(1))

    while init_sets and init_sets[-1] == set():
        init_sets.pop()

    return init_sets, iter_sets, iter_time, iter_div


def classify_log_file(log_name: str) -> Optional[str]:
    if RE_METRICS_FILE.search(log_name):
        return "metrics"
    if RE_GREEDY_FILE.search(log_name):
        return "greedy"
    if RE_OPT_FILE.search(log_name):
        return "optimal"
    return None


def parse_log_file(p: Path, check_diversity: bool, mode_hint: str) -> List[Dict[str, str]]:
    txt = p.read_text(encoding="utf-8", errors="ignore")
    rows: List[Dict[str, str]] = []
    finished = bool(RE_EXIT_MARK.search(txt))
    source = extract_profile_from_log_path(p)

    # Policy:
    # - optimal logs must be complete; if unfinished, ignore entirely
    # - greedy logs can contribute partial rows from completed parsed blocks
    if mode_hint == "optimal" and not finished:
        return rows

    m_lp = RE_DOWNWARD_FILE.search(txt)
    m_goal = RE_GOAL.search(txt)
    m_vars = RE_VARS.search(txt)
    m_clauses = RE_CLAUSES.search(txt)
    m_acyclicity_vars = RE_ACYCLICITY_VARS.search(txt)
    m_acyclicity_clauses = RE_ACYCLICITY_CLAUSES.search(txt)
    metrics_only = bool(RE_METRICS_ONLY.search(txt))
    m_init_time = RE_INIT_TIME.search(txt)
    m_program_create_time = RE_PROGRAM_CREATE_TIME.search(txt)
    m_program_create_time_so_far = RE_PROGRAM_CREATE_TIME_SO_FAR.search(txt)
    m_build_time = RE_BUILD_TIME.search(txt)
    m_init_div = RE_INIT_DIV.search(txt)
    m_num_initial = RE_NUM_INITIAL.search(txt)

    lp = m_lp.group(1).strip() if m_lp else ""
    dataset = extract_dataset_from_path(lp) or ""
    if dataset.lower() == "bugs":
        return rows
    goal = m_goal.group(1).strip() if m_goal else ""
    nvars = int(m_vars.group(1)) if m_vars else None
    nclauses = int(m_clauses.group(1)) if m_clauses else None
    n_acyclicity_vars = int(m_acyclicity_vars.group(1)) if m_acyclicity_vars else None
    n_acyclicity_clauses = int(m_acyclicity_clauses.group(1)) if m_acyclicity_clauses else None
    init_time = int(m_init_time.group(1)) if m_init_time else 0
    program_create_time = (
        int(m_program_create_time.group(1)) if m_program_create_time
        else (int(m_program_create_time_so_far.group(1)) if m_program_create_time_so_far
        else (int(m_build_time.group(1)) if m_build_time else None)
        )
    )
    init_div = int(m_init_div.group(1)) if m_init_div else None
    k0 = int(m_num_initial.group(1)) if m_num_initial else 1

    init_sets, iter_sets_atoms, iter_time_map, iter_div_map = parse_model_blocks_with_atoms(txt)
    iter_indices = sorted(iter_sets_atoms.keys() | iter_time_map.keys() | iter_div_map.keys())

    mismatch_count = 0

    if iter_indices:
        mode = mode_hint
        cumulative_time = init_time

        if init_div is not None and K_MIN <= k0 <= K_MAX:
            rows.append({
                "dataset": dataset,
                "lp_file": lp,
                "goal": goal,
                "k": str(k0),
                "vars": str(nvars) if nvars is not None else "",
                "clauses": str(nclauses) if nclauses is not None else "",
                "acyclicity_vars": str(n_acyclicity_vars) if n_acyclicity_vars is not None else "",
                "acyclicity_clauses": str(n_acyclicity_clauses) if n_acyclicity_clauses is not None else "",
                "metrics_only": "true" if metrics_only else "false",
                "diversity": str(init_div),
                "time_ms": str(cumulative_time),
                "program_creation_time_ms": str(program_create_time) if program_create_time is not None else "",
                "mode": mode,
                "source": source,
                "log_file": str(p)
            })

        if check_diversity:
            if init_sets and len(init_sets) >= k0 and init_div is not None:
                recomputed_init_cum = pairwise_symdiff_sum(init_sets[:k0])
                if recomputed_init_cum != init_div:
                    mismatch_count += 1
                    print(f"[DIVERSITY MISMATCH][initial] {p}")
                    print(f"  expected_cumulative={recomputed_init_cum}, logged_initial_div={init_div}, k0={k0}")

            selected_sets = init_sets[:k0] if init_sets and len(init_sets) >= k0 else []
            cumulative_expected = pairwise_symdiff_sum(selected_sets) if selected_sets else 0
        else:
            selected_sets = []
            cumulative_expected = 0

        k = k0
        for idx in iter_indices:
            k += 1
            t = iter_time_map.get(idx, 0)
            d_logged = iter_div_map.get(idx, None)
            cumulative_time += t

            if K_MIN <= k <= K_MAX:
                rows.append({
                    "dataset": dataset,
                    "lp_file": lp,
                    "goal": goal,
                    "k": str(k),
                    "vars": str(nvars) if nvars is not None else "",
                "clauses": str(nclauses) if nclauses is not None else "",
                "acyclicity_vars": str(n_acyclicity_vars) if n_acyclicity_vars is not None else "",
                "acyclicity_clauses": str(n_acyclicity_clauses) if n_acyclicity_clauses is not None else "",
                "metrics_only": "true" if metrics_only else "false",
                    "diversity": "" if d_logged is None else str(d_logged),
                    "time_ms": str(cumulative_time),
                    "program_creation_time_ms": str(program_create_time) if program_create_time is not None else "",
                    "mode": mode,
                    "source": source,
                    "log_file": str(p)
                })

            if check_diversity:
                current_atoms = iter_sets_atoms.get(idx)
                if selected_sets and current_atoms is not None:
                    marginal_expected = sum(symdiff_size(current_atoms, s) for s in selected_sets)
                    cumulative_expected += marginal_expected

                    if d_logged is not None and (d_logged != marginal_expected and d_logged != cumulative_expected):
                        mismatch_count += 1
                        print(f"[DIVERSITY MISMATCH][iter {idx}] {p}")
                        print(f"  logged={d_logged}, marginal_expected={marginal_expected}, cumulative_expected={cumulative_expected}")

                    selected_sets.append(current_atoms)

        if check_diversity and mismatch_count > 0:
            print(f"[DIVERSITY SUMMARY] {p} mismatches={mismatch_count}")

    else:
        mode = mode_hint
        if mode_hint == "metrics" or K_MIN <= k0 <= K_MAX:
            rows.append({
                "dataset": dataset,
                "lp_file": lp,
                "goal": goal,
                "k": str(k0),
                "vars": str(nvars) if nvars is not None else "",
                "clauses": str(nclauses) if nclauses is not None else "",
                "acyclicity_vars": str(n_acyclicity_vars) if n_acyclicity_vars is not None else "",
                "acyclicity_clauses": str(n_acyclicity_clauses) if n_acyclicity_clauses is not None else "",
                "metrics_only": "true" if metrics_only else "false",
                "diversity": str(init_div) if init_div is not None else "",
                "time_ms": str(init_time),
                "program_creation_time_ms": str(program_create_time) if program_create_time is not None else "",
                "mode": mode,
                "source": source,
                "log_file": str(p)
            })

        if check_diversity:
            if init_sets and len(init_sets) >= k0 and init_div is not None:
                recomputed_init_cum = pairwise_symdiff_sum(init_sets[:k0])
                if recomputed_init_cum != init_div:
                    print(f"[DIVERSITY MISMATCH][initial-only] {p}")
                    print(f"  expected_cumulative={recomputed_init_cum}, logged_initial_div={init_div}, k0={k0}")

    return rows


def parse_logs_to_csv(root_dir: str, out_csv: str, check_diversity: bool = False) -> None:
    root = Path(root_dir)
    all_rows: List[Dict[str, str]] = []

    kept_logs = 0
    skipped_logs = 0
    skipped_unfinished_optimal = 0
    dropped_timeout_rows = 0

    for log_path in root.rglob("*.log"):
        kind = classify_log_file(log_path.name)
        if kind is None:
            skipped_logs += 1
            continue

        kept_logs += 1
        try:
            effective_check = check_diversity and kind == "greedy"
            parsed_rows = parse_log_file(log_path, effective_check, kind)
            if kind == "optimal" and not parsed_rows:
                # likely unfinished/timeout optimal log
                skipped_unfinished_optimal += 1
                continue
            # Treat rows with missing diversity as timeout/incomplete and ignore them.
            # (For this workflow we keep only rows with concrete diversity values.)
            good_rows = parsed_rows if kind == "metrics" else [r for r in parsed_rows if str(r.get("diversity", "")).strip() != ""]
            dropped_timeout_rows += (len(parsed_rows) - len(good_rows))
            all_rows.extend(good_rows)
        except Exception as e:
            all_rows.append({
                "dataset": "",
                "lp_file": "",
                "goal": "",
                "k": "",
                "vars": "",
                "clauses": "",
                "acyclicity_vars": "",
                "acyclicity_clauses": "",
                "metrics_only": "",
                "diversity": "",
                "time_ms": "",
                "program_creation_time_ms": "",
                "mode": kind,
                "source": "",
                "log_file": str(log_path),
                "error": f"{type(e).__name__}: {e}"
            })

    fieldnames = ["dataset", "lp_file", "goal", "k", "vars", "clauses", "acyclicity_vars", "acyclicity_clauses", "metrics_only", "diversity", "time_ms", "program_creation_time_ms", "mode", "source", "log_file"]
    if any("error" in r for r in all_rows):
        fieldnames.append("error")

    print(f"[info] scanned logs: {kept_logs + skipped_logs}")
    print(f"[info] kept logs: {kept_logs}")
    print(f"[info] skipped logs: {skipped_logs}")
    print(f"[info] skipped unfinished optimal logs: {skipped_unfinished_optimal}")
    print(f"[info] dropped timeout/incomplete rows: {dropped_timeout_rows}")
    print(f"[info] rows to write: {len(all_rows)}")
    print(f"[info] output path: {out_csv}")

    with open(out_csv, "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=fieldnames)
        w.writeheader()
        for r in all_rows:
            w.writerow({k: r.get(k, "") for k in fieldnames})

    print("[done] CSV written.")


if __name__ == "__main__":
    import sys
    # Usage:
    #   python process-logs-k2.py <root_dir> <out_csv> [--check|--no-check]
    root = sys.argv[1] if len(sys.argv) > 1 else "."
    out  = sys.argv[2] if len(sys.argv) > 2 else "explanation_sets_summary_k2.csv"
    flag = sys.argv[3] if len(sys.argv) > 3 else "--no-check"
    check = flag.strip().lower() in ("--check", "--check-diversity", "--debug", "-c")

    print(f"[info] scanning: {root}")
    print(f"[info] diversity check: {'ON' if check else 'OFF'}")

    parse_logs_to_csv(root, out, check_diversity=check)

    print(f"[done] wrote: {Path(out).resolve()}")
