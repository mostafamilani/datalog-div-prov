# process-logs.py
from pathlib import Path
import re
import csv
from typing import List, Dict, Optional, Tuple

# --------- regex patterns (top-level fields) ---------
RE_DOWNWARD_FILE = re.compile(r"^downwardFile\s*:\s*(.+\.lp)\s*$", re.MULTILINE)
RE_GOAL         = re.compile(r"^goal\s*:\s*(.+)\s*$", re.MULTILINE)
RE_VARS         = re.compile(r"^Number of variables\s*:\s*(\d+)\s*$", re.MULTILINE)
RE_INIT_TIME    = re.compile(r"^Initial phase time\s*:\s*(\d+)\s*ms\s*$", re.MULTILINE)
RE_INIT_DIV     = re.compile(r"^Initial diversity\s*:\s*(\d+)\s*$", re.MULTILINE)
RE_NUM_INITIAL  = re.compile(r"^numInitial\s*:\s*(\d+)\s*$", re.MULTILINE)

# Model blocks (Initial/Iterative) — robust to CRLF, extra lines, etc.
RE_MODEL_BLOCK = re.compile(
    r"--\s*(?P<kind>Initial|Iterative)\s+Model\s+#(?P<idx>\d+)\s*--\s*"
    r"(?P<body>.*?)(?=^\s*--\s*(?:Initial|Iterative)\s+Model\s+#|\Z)",
    re.MULTILINE | re.DOTALL
)

# Lines inside a model block
RE_TRUE_LINE = re.compile(r"^\s*(?P<atom>.+?)\s*=\s*true\s*$", re.MULTILINE)
RE_TIME_LINE = re.compile(r"^\s*time\s*:\s*(\d+)\s*ms\s*$", re.MULTILINE)
RE_DIV_LINE  = re.compile(r"^\s*diversity\s*:\s*(\d+)\s*$", re.MULTILINE)

def extract_dataset_from_path(lp_path: str) -> Optional[str]:
    parts = Path(lp_path).parts
    if "downward" in parts:
        i = parts.index("downward")
        return parts[i + 1] if i + 1 < len(parts) else None
    return None

def symdiff_size(a: set, b: set) -> int:
    return len(a.symmetric_difference(b))

def pairwise_symdiff_sum(sets: List[set]) -> int:
    total = 0
    for i in range(len(sets)):
        for j in range(i + 1, len(sets)):
            total += symdiff_size(sets[i], sets[j])
    return total

def parse_model_blocks_with_atoms(txt: str):
    """
    Returns:
      init_sets: list[set] of initial models #0.. in order (may be empty if facts not printed)
      iter_sets: dict[int -> set] mapping Iterative Model index (1-based) -> set (may be empty)
      iter_time: dict[int -> int] ms from the iterative model block (if present)
      iter_div : dict[int -> int] diversity from the iterative model block (if present)
    """
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

def parse_log_file(p: Path, check_diversity: bool) -> List[Dict[str, str]]:
    txt = p.read_text(encoding="utf-8", errors="ignore")
    rows: List[Dict[str, str]] = []

    m_lp = RE_DOWNWARD_FILE.search(txt)
    m_goal = RE_GOAL.search(txt)
    m_vars = RE_VARS.search(txt)
    m_init_time = RE_INIT_TIME.search(txt)
    m_init_div = RE_INIT_DIV.search(txt)
    m_num_initial = RE_NUM_INITIAL.search(txt)

    lp = m_lp.group(1).strip() if m_lp else ""
    dataset = extract_dataset_from_path(lp) or ""
    goal = m_goal.group(1).strip() if m_goal else ""
    nvars = int(m_vars.group(1)) if m_vars else None
    init_time = int(m_init_time.group(1)) if m_init_time else 0
    init_div = int(m_init_div.group(1)) if m_init_div else None
    k0 = int(m_num_initial.group(1)) if m_num_initial else 1

    # Parse all blocks once
    init_sets, iter_sets_atoms, iter_time_map, iter_div_map = parse_model_blocks_with_atoms(txt)
    iter_indices = sorted(iter_sets_atoms.keys() | iter_time_map.keys() | iter_div_map.keys())

    if iter_indices:
        # ========= GREEDY: initial row + one per iterative model =========
        mode = "greedy"
        cumulative_time = init_time

        if init_div is not None and k0 >= 1:
            rows.append({
                "dataset": dataset,
                "lp_file": lp,
                "goal": goal,
                "k": str(k0),
                "vars": str(nvars) if nvars is not None else "",
                "diversity": str(init_div),
                "time_ms": str(cumulative_time),
                "mode": mode,
                "log_file": str(p)
            })

        # Prepare diversity checking state
        if check_diversity:
            # Validate initial cumulative vs logged Initial diversity
            if init_sets and len(init_sets) >= k0 and init_div is not None:
                recomputed_init_cum = pairwise_symdiff_sum(init_sets[:k0])
                if recomputed_init_cum != init_div:
                    print(f"[DIVERSITY MISMATCH][initial] {p}")
                    print(f"  expected cumulative={recomputed_init_cum}, logged initial_div={init_div}, k0={k0}")
            selected_sets = init_sets[:k0] if init_sets and len(init_sets) >= k0 else []
            cumulative_expected = pairwise_symdiff_sum(selected_sets) if selected_sets else 0

        # Emit rows per iterative model, in order
        k = k0
        for idx in iter_indices:
            k += 1
            t = iter_time_map.get(idx, 0)
            d_logged = iter_div_map.get(idx, None)
            cumulative_time += t

            rows.append({
                "dataset": dataset,
                "lp_file": lp,
                "goal": goal,
                "k": str(k),
                "vars": str(nvars) if nvars is not None else "",
                "diversity": "" if d_logged is None else str(d_logged),
                "time_ms": str(cumulative_time),
                "mode": mode,
                "log_file": str(p)
            })

            # Optional consistency check
            if check_diversity:
                current_atoms = iter_sets_atoms.get(idx)
                if selected_sets and current_atoms is not None:
                    marginal_expected = sum(symdiff_size(current_atoms, s) for s in selected_sets)
                    cumulative_expected += marginal_expected

                    if d_logged is None:
                        print(f"[DIVERSITY INFO][iter {idx}] {p}")
                        print(f"  marginal_expected={marginal_expected}, cumulative_expected={cumulative_expected} (no logged value)")
                    elif d_logged == marginal_expected and d_logged != cumulative_expected:
                        print(f"[OK as MARGINAL][iter {idx}] {p}")
                        print(f"  logged={d_logged}, marginal={marginal_expected}, cumulative={cumulative_expected}")
                    elif d_logged == cumulative_expected:
                        print(f"[OK as CUMULATIVE][iter {idx}] {p}")
                        print(f"  logged={d_logged}, cumulative={cumulative_expected}")
                    else:
                        print(f"[DIVERSITY MISMATCH][iter {idx}] {p}")
                        print(f"  logged={d_logged}, marginal_expected={marginal_expected}, cumulative_expected={cumulative_expected}")

                    selected_sets.append(current_atoms)
                # else: cannot check (no atoms printed in this log)

    else:
        # ========= OPTIMAL / single-row logs =========
        mode = "greedy" if k0 == 1 else "optimal"
        rows.append({
            "dataset": dataset,
            "lp_file": lp,
            "goal": goal,
            "k": str(k0),
            "vars": str(nvars) if nvars is not None else "",
            "diversity": str(init_div) if init_div is not None else "",
            "time_ms": str(init_time),
            "mode": mode,
            "log_file": str(p)
        })

        # Optional initial-only check
        if check_diversity:
            if init_sets and len(init_sets) >= k0 and init_div is not None:
                recomputed_init_cum = pairwise_symdiff_sum(init_sets[:k0])
                if recomputed_init_cum != init_div:
                    print(f"[DIVERSITY MISMATCH][initial-only] {p}")
                    print(f"  expected cumulative={recomputed_init_cum}, logged initial_div={init_div}, k0={k0}")

    return rows

def parse_logs_to_csv(root_dir: str, out_csv: str, check_diversity: bool = False) -> None:
    root = Path(root_dir)
    all_rows: List[Dict[str, str]] = []
    for log_path in root.rglob("*.log"):
        try:
            # Enable diversity checks ONLY for files ending with "-1.log"
            effective_check = check_diversity and log_path.name.endswith("-1.log")
            all_rows.extend(parse_log_file(log_path, effective_check))
        except Exception as e:
            all_rows.append({
                "dataset": "",
                "lp_file": "",
                "goal": "",
                "k": "",
                "vars": "",
                "diversity": "",
                "time_ms": "",
                "mode": "",
                "log_file": str(log_path),
                "error": f"{type(e).__name__}: {e}"
            })

    fieldnames = ["dataset", "lp_file", "goal", "k", "vars", "diversity", "time_ms", "mode", "log_file"]
    if any("error" in r for r in all_rows):
        fieldnames.append("error")

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
    #   python process-logs.py <root_dir> <out_csv> [--check|--no-check]
    root = sys.argv[1] if len(sys.argv) > 1 else "."
    out  = sys.argv[2] if len(sys.argv) > 2 else "explanation_sets_summary.csv"
    flag = sys.argv[3] if len(sys.argv) > 3 else "--no-check"
    check = flag.strip().lower() in ("--check", "--check-diversity", "--debug", "-c")
    print(f"[info] scanning: {root}")
    print(f"[info] diversity check: {'ON' if check else 'OFF'}")
    parse_logs_to_csv(root, out, check_diversity=check)
    print(f"[done] wrote: {Path(out).resolve()}")
