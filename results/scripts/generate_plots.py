from __future__ import annotations

import argparse
import inspect
import os
import re
from pathlib import Path

import matplotlib.pyplot as plt
import matplotlib.ticker as mticker
import numpy as np
import pandas as pd
import seaborn as sns
from matplotlib.collections import PolyCollection


PALETTE = {"greedy": "#1b9e77", "optimal": "#377eb8"}
TIME_COMPARE_SOURCES = ["hypertree-standard", "hypertree-unambigous", "dag-unambigous"]
RATE_COMPARE_SOURCES = ["hypertree-standard", "hypertree-unambigous", "dag-unambigous"]
TIMEOUT_COMPARE_SOURCES = {"dag-unambigous", "hypertree-unambigous"}
VALID_MODES = {"greedy", "optimal"}
PLOT_K_MIN = 1
PLOT_K_MAX = 5
RATE_FILTER_ONLY_COMPLETE_K = True
TIMEOUT_MINUTES = 10
TIMEOUT_MS = TIMEOUT_MINUTES * 60 * 1000

FONT_SIZE = 25
VIOLIN_ALPHA = 0.40
BOX_ALPHA = 0.70
BOX_WIDTH = 0.4
BOX_LINEWIDTH = 0.8
VIOLIN_WIDTH = 0.80
BW_ADJUST = 0.8
GRIDSIZE = 400
POINT_SIZE_NORMAL = 2
POINT_SIZE_TIMEOUT = 2
POINT_SIZE_RATE = 2
FIG_HEIGHT = 5.5
FIG_WIDTH_LEFT = 6.6
FIG_WIDTH_OTHER = 5.8
RATE_YLOG = False
TIMEOUT_ANNOTATION_FONT_SIZE = 16


def parse_args() -> argparse.Namespace:
    base_dir = Path(__file__).resolve().parent
    parser = argparse.ArgumentParser(description="Generate PDF plots from results.csv")
    parser.add_argument(
        "--csv",
        default=str(base_dir / "results.csv"),
        help="Path to the input CSV file.",
    )
    parser.add_argument(
        "--output-dir",
        default=str(base_dir / "plots"),
        help="Directory where generated PDFs will be written.",
    )
    parser.add_argument(
        "--k-min",
        type=int,
        default=PLOT_K_MIN,
        help="Minimum k value to include in plots.",
    )
    parser.add_argument(
        "--k-max",
        type=int,
        default=PLOT_K_MAX,
        help="Maximum k value to include in plots.",
    )
    return parser.parse_args()


def configure_plotting() -> None:
    plt.rcParams.update(
        {
            "font.size": FONT_SIZE,
            "axes.labelsize": FONT_SIZE,
            "xtick.labelsize": FONT_SIZE,
            "ytick.labelsize": FONT_SIZE,
            "legend.fontsize": FONT_SIZE,
        }
    )
    sns.set_theme(style="whitegrid")
    sns.set_palette(list(PALETTE.values()))


def _token_pat(alts: str) -> str:
    return rf"(?:^|[^0-9a-zA-Z])(?:{alts})(?:$|[^0-9a-zA-Z])"


def _first_token(alts: str, value: str) -> str | None:
    match = re.search(_token_pat(alts), value)
    if not match:
        return None
    return match.group(0).strip(" _-/.,:;()[]{}")


def _safe_name(value: str) -> str:
    safe = str(value).strip().lower()
    safe = safe.replace(os.sep, "_").replace(" ", "_")
    return safe


def _source_labels(source: str) -> tuple[str, str]:
    source_lower = str(source).lower()

    if "standard" in source_lower:
        proof = "arbitrary"
    elif "unambigous" in source_lower:
        proof = "unambigous"
    else:
        proof = "unknown"

    if "dag" in source_lower:
        structure = "dag"
    elif "hypertree" in source_lower or "hypergraph" in source_lower:
        structure = "hypergraph"
    else:
        structure = "unknown"

    return proof, structure


def _algo_label(algo: str) -> str:
    return {"optimal": "exact", "greedy": "approximate"}.get(str(algo).lower(), _safe_name(algo))


def _build_time_filename(scenario: str, dataset: str, algo: str, source: str) -> str:
    proof, structure = _source_labels(source)
    approx = _algo_label(algo)
    return f"{_safe_name(scenario)}-{_safe_name(dataset)}-{approx}-{proof}-{structure}.pdf"


def _build_rate_filename(scenario: str, dataset: str, source: str) -> str:
    proof, structure = _source_labels(source)
    return f"rate-{_safe_name(scenario)}-{_safe_name(dataset)}-{proof}-{structure}.pdf"


def _facts_label(value: object) -> str:
    if pd.isna(value) or str(value).strip() == "":
        return "nofacts"
    return str(value)


def _is_leftmost_source(source: str) -> bool:
    return str(source).lower() == "hypertree-standard"


def _needs_rate_ylabel(source: str) -> bool:
    proof, structure = _source_labels(source)
    return proof == "arbitrary" and structure == "hypergraph"


def _violin_kwargs() -> dict[str, str]:
    signature = inspect.signature(sns.violinplot)
    if "density_norm" in signature.parameters:
        return {"density_norm": "width"}
    return {"scale": "width"}


def _required_k_set() -> set[int]:
    return set(range(PLOT_K_MIN, PLOT_K_MAX + 1))


def _filter_complete_k(
    frame: pd.DataFrame,
    goal_col: str = "goal",
    required_k: set[int] | None = None,
) -> pd.DataFrame:
    if frame.empty or goal_col not in frame.columns or "k" not in frame.columns:
        return frame

    numeric_k = pd.to_numeric(frame["k"], errors="coerce")
    tmp = frame.assign(_knum=numeric_k).dropna(subset=["_knum"]).copy()
    tmp["_knum"] = tmp["_knum"].astype(int)

    required_k = _required_k if ( _required_k := required_k ) is not None else _required_k_set()
    ks_by_goal = tmp.groupby(goal_col, observed=True)["_knum"].apply(lambda values: set(values.unique()))
    complete_goals = ks_by_goal[ks_by_goal.apply(lambda values: required_k.issubset(values))].index
    return tmp[tmp[goal_col].isin(complete_goals)].drop(columns=["_knum"])


def _drop_k1_timeout_goals(frame: pd.DataFrame, goal_col: str = "goal") -> pd.DataFrame:
    if frame.empty or goal_col not in frame.columns or "k" not in frame.columns or "time_ms" not in frame.columns:
        return frame

    k_num = pd.to_numeric(frame["k"], errors="coerce")
    time_str = frame["time_ms"].astype(str).str.strip().str.lower()
    is_timeout = time_str.eq("timeout")
    if "row_status" in frame.columns:
        # Only drop goals with a real timeout detected at k=1, not synthetic rows.
        is_timeout &= frame["row_status"].astype(str).eq("timeout_detected")

    timeout_goals = frame.loc[(k_num == 1) & is_timeout, goal_col].dropna().unique()
    if len(timeout_goals) == 0:
        return frame

    return frame[~frame[goal_col].isin(timeout_goals)].copy()


def _plot_timeout_mask(frame: pd.DataFrame) -> pd.Series:
    if frame.empty:
        return pd.Series(False, index=frame.index)

    time_str = frame["time_ms"].astype(str).str.strip().str.lower()
    time_ms_num = pd.to_numeric(frame["time_ms"], errors="coerce")
    is_timeout = time_str.eq("timeout") | time_ms_num.gt(TIMEOUT_MS)
    if "row_status" in frame.columns:
        is_timeout |= frame["row_status"].astype(str).isin({"timeout_synth", "timeout_detected", "timeout_cascade"})
    if "is_timeout" in frame.columns:
        is_timeout |= frame["is_timeout"].fillna(False).astype(bool)
    return is_timeout


def _k1_timeout_goals(frame: pd.DataFrame, goal_col: str = "goal") -> set[str]:
    if frame.empty or goal_col not in frame.columns or "k" not in frame.columns or "time_ms" not in frame.columns:
        return set()

    k_num = pd.to_numeric(frame["k"], errors="coerce")
    time_str = frame["time_ms"].astype(str).str.strip().str.lower()
    is_timeout = time_str.eq("timeout")
    if "row_status" in frame.columns:
        is_timeout &= frame["row_status"].astype(str).eq("timeout_detected")

    timeout_goals = frame.loc[(k_num == 1) & is_timeout, goal_col].dropna().astype(str).unique().tolist()
    return set(timeout_goals)


def _k1_plot_timeout_goals(frame: pd.DataFrame, goal_col: str = "goal") -> set[str]:
    if frame.empty or goal_col not in frame.columns or "k" not in frame.columns or "time_ms" not in frame.columns:
        return set()

    k_num = pd.to_numeric(frame["k"], errors="coerce")
    timeout_goals = frame.loc[(k_num == 1) & _plot_timeout_mask(frame), goal_col].dropna().astype(str).unique().tolist()
    return set(timeout_goals)


def _cascade_plot_timeouts(frame: pd.DataFrame, goal_col: str = "goal") -> pd.DataFrame:
    if frame.empty or goal_col not in frame.columns or "k" not in frame.columns or "time_ms" not in frame.columns:
        return frame

    out = frame.copy()
    out["_knum"] = pd.to_numeric(out["k"], errors="coerce")
    timeout_mask = _plot_timeout_mask(out)
    timeout_rows = out[timeout_mask & out["_knum"].notna()].copy()
    if timeout_rows.empty:
        return out.drop(columns=["_knum"])

    first_timeout_by_goal = timeout_rows.groupby(goal_col, observed=True)["_knum"].min()
    for goal_value, first_timeout_k in first_timeout_by_goal.items():
        if pd.isna(first_timeout_k):
            continue
        cascade_mask = out[goal_col].eq(goal_value) & out["_knum"].ge(int(first_timeout_k))
        out.loc[cascade_mask, "time_ms"] = "timeout"
        out.loc[cascade_mask, "row_status"] = "timeout_cascade"
        if "diversity" in out.columns:
            out.loc[cascade_mask, "diversity"] = np.nan

    return out.drop(columns=["_knum"])


def _validate_k1_greedy_exact(frame: pd.DataFrame, key_cols: list[str]) -> None:
    if frame.empty or "diversity" not in frame.columns or "mode" not in frame.columns or "k" not in frame.columns:
        return

    compare_cols = [column for column in key_cols if column != "k"]
    if len(compare_cols) < 3:
        return

    sub = frame[frame["mode"].isin(VALID_MODES)].copy()
    sub["k_num"] = pd.to_numeric(sub["k"], errors="coerce")
    sub = sub[sub["k_num"] == 1].copy()
    if sub.empty:
        print("Validated k=1 greedy/exact diversity pairs: 0")
        return

    sub["diversity_num"] = pd.to_numeric(sub["diversity"], errors="coerce")
    grouped = (
        sub.groupby(compare_cols + ["mode"], as_index=False, dropna=False, observed=True)["diversity_num"]
        .mean()
    )
    pivot = grouped.pivot(index=compare_cols, columns="mode", values="diversity_num")
    if not {"greedy", "optimal"}.issubset(set(pivot.columns)):
        print("Validated k=1 greedy/exact diversity pairs: 0")
        return

    paired = pivot.dropna(subset=["greedy", "optimal"]).copy()
    mismatch = paired[~np.isclose(paired["greedy"], paired["optimal"], equal_nan=True)]
    if not mismatch.empty:
        raise ValueError(
            "k=1 greedy/exact diversity mismatch detected for "
            f"{len(mismatch)} grouped rows. First mismatches:\n{mismatch.head(20)}"
        )

    print(f"Validated k=1 greedy/exact diversity pairs: {len(paired)}")


def load_and_prepare_data(csv_path: Path) -> pd.DataFrame:
    if not csv_path.exists():
        raise FileNotFoundError(f"Missing target CSV: {csv_path}")

    frame = pd.read_csv(csv_path)
    if "source" not in frame.columns:
        frame["source"] = csv_path.stem

    print("Loaded CSV file:", csv_path.name)
    print("Raw columns:", list(frame.columns))

    frame["time_ms"] = pd.to_numeric(frame["time_ms"], errors="coerce")

    frame["k"] = pd.to_numeric(frame["k"], errors="coerce")
    frame = frame[frame["k"].between(PLOT_K_MIN, PLOT_K_MAX, inclusive="both")].copy()

    if "diversity" in frame.columns:
        frame["diversity"] = pd.to_numeric(frame["diversity"], errors="coerce")
    if "vars" in frame.columns:
        frame["vars"] = pd.to_numeric(frame["vars"], errors="coerce")

    frame["dataset"] = frame["dataset"].astype(str)
    frame = frame[frame["dataset"].str.lower() != "bugs"].copy()

    frame["facts"] = pd.Series("", index=frame.index, dtype="string")

    search_cols = [column for column in ["dataset", "lp_file", "log_file", "source", "goal"] if column in frame.columns]
    combined = frame[search_cols].astype(str).agg(" ".join, axis=1).str.lower()
    dataset_lower = frame["dataset"].str.lower().str.replace("-", "_").str.strip()

    mask_doc = dataset_lower.str.startswith("doctors_") | dataset_lower.eq("doctors")
    mask_csda = dataset_lower.str.startswith("csda") | dataset_lower.str.startswith("csdn")
    mask_and = dataset_lower.str.startswith("andersen")
    mask_galen = dataset_lower.str.startswith("galen")
    mask_graph = dataset_lower.str.startswith("graph_analytics") | dataset_lower.str.startswith("graph")

    frame.loc[mask_doc, "facts"] = "100k"
    frame.loc[mask_csda & combined.str.contains(_token_pat("httpd"), regex=True), "facts"] = "httpd"
    frame.loc[mask_csda & combined.str.contains(r"postgres(?:ql)?", regex=True), "facts"] = "postgresql"
    frame.loc[mask_csda & combined.str.contains(_token_pat("linux|ubuntu|debian|centos"), regex=True), "facts"] = "linux"
    frame.loc[mask_graph & combined.str.contains(_token_pat("facebook|fb"), regex=True), "facts"] = "facebook"
    frame.loc[mask_graph & combined.str.contains(_token_pat("bitcoin"), regex=True), "facts"] = "bitcoin"

    and_size = combined.apply(lambda value: _first_token("1000|5000|10000|50000|100000", value))
    frame.loc[mask_and & and_size.notna(), "facts"] = and_size[mask_and & and_size.notna()].astype(str)

    galen_size = combined.apply(lambda value: _first_token("15|25|40|50", value))
    frame.loc[mask_galen & galen_size.notna(), "facts"] = galen_size[mask_galen & galen_size.notna()].astype(str)

    frame["facts"] = (
        frame["facts"].astype(str).str.strip().str.lower().replace({"100000": "100k"})
    )

    missing_facts = frame["facts"].eq("") | frame["facts"].eq("nan")
    missing_count = int(missing_facts.sum())
    if missing_count > 0:
        print("\n" + "!" * 100)
        print(f"WARNING: Could not extract 'facts' for {missing_count} rows. Showing up to 80 examples.")
        cols_show = [column for column in ["source", "dataset", "facts", "lp_file", "log_file", "goal", "k"] if column in frame.columns]
        print(frame.loc[missing_facts, cols_show].head(80).to_string(index=False))
        print("!" * 100)

    if "source" not in frame.columns:
        raise ValueError("Expected 'source' column in CSV.")

    frame["source"] = frame["source"].astype(str)
    frame["mode"] = frame["mode"].astype(str)
    frame["time_ms"] = frame["time_ms"].astype(object)
    frame["row_status"] = "observed"

    # Detect true timeouts: time=0 with diversity=NaN means the solver did not finish.
    # time=0 with diversity=0 is a legitimate fast completion (0 diverse proofs found).
    if "diversity" in frame.columns:
        timed_out_mask = (
            (pd.to_numeric(frame["time_ms"], errors="coerce") == 0)
            & frame["diversity"].isna()
        )
        frame.loc[timed_out_mask, "time_ms"] = "timeout"
        frame.loc[timed_out_mask, "row_status"] = "timeout_detected"
        print(f"Detected timeout rows (time=0, diversity=NaN): {int(timed_out_mask.sum())}")

    subset = frame[frame["mode"].isin(VALID_MODES)].copy()
    key_cols = [column for column in ["source", "dataset", "goal", "k", "lp_file"] if column in frame.columns]
    if len(key_cols) < 4:
        raise ValueError(f"Not enough key columns found for timeout synthesis. Found: {key_cols}")

    missing_rows: list[pd.Series] = []
    k1_copy_count = 0
    timeout_synth_count = 0
    for _, group in subset.groupby(key_cols, dropna=False):
        modes_present = set(group["mode"].unique())
        if modes_present == VALID_MODES:
            continue
        if modes_present == {"greedy"}:
            missing_mode = "optimal"
        elif modes_present == {"optimal"}:
            missing_mode = "greedy"
        else:
            continue

        base = group.iloc[0].copy()
        base["mode"] = missing_mode
        k_value = pd.to_numeric(base.get("k"), errors="coerce")
        source_value = str(base.get("source"))

        # For k=1, greedy and optimal coincide, so reuse the observed row for every
        # source instead of dropping the missing counterpart or marking it as timeout.
        if pd.notna(k_value) and int(k_value) == 1:
            base["row_status"] = "k1_copy"
            k1_copy_count += 1
        elif source_value in TIMEOUT_COMPARE_SOURCES:
            base["time_ms"] = "timeout"
            if "diversity" in base.index:
                base["diversity"] = np.nan
            if "vars" in base.index and pd.isna(base["vars"]):
                base["vars"] = np.nan
            base["row_status"] = "timeout_synth"
            timeout_synth_count += 1
        else:
            continue
        missing_rows.append(base)

    if missing_rows:
        frame = pd.concat([frame, pd.DataFrame(missing_rows)], ignore_index=True)

    _validate_k1_greedy_exact(frame, key_cols)

    print(f"Synthesized k=1 greedy/exact copies: {k1_copy_count}")
    print(f"Synthesized timeout rows: {timeout_synth_count}")

    # Cascade propagation: if k=n is a timeout for (source, dataset, goal, mode, lp_file),
    # then k=n+1 ... PLOT_K_MAX must also be timeouts (harder to solve, so they would also time out).
    # Add synthetic timeout rows for any such larger-k slots that have no entry at all.
    cascade_key_cols = [c for c in ["source", "dataset", "goal", "mode", "lp_file"] if c in frame.columns]
    is_timeout_row = frame["row_status"].isin({"timeout_detected", "timeout_synth"}) | (
        frame["time_ms"].astype(str).str.strip().str.lower() == "timeout"
    )
    timeout_frame = frame[is_timeout_row & frame["mode"].isin(VALID_MODES)].copy()
    cascade_rows: list[pd.Series] = []
    cascade_count = 0
    if not timeout_frame.empty and cascade_key_cols:
        for group_key, group in timeout_frame.groupby(cascade_key_cols, dropna=False, observed=True):
            min_timeout_k = int(pd.to_numeric(group["k"], errors="coerce").min())
            if min_timeout_k >= PLOT_K_MAX:
                continue
            key_dict = dict(zip(cascade_key_cols, group_key if isinstance(group_key, tuple) else (group_key,)))
            mask = pd.Series([True] * len(frame), index=frame.index)
            for col, val in key_dict.items():
                mask &= frame[col].astype(str) == str(val)
            existing_ks = set(pd.to_numeric(frame.loc[mask, "k"], errors="coerce").dropna().astype(int).unique())
            base = group.iloc[0].copy()
            for k_val in range(min_timeout_k + 1, PLOT_K_MAX + 1):
                if k_val in existing_ks:
                    continue
                new_row = base.copy()
                new_row["k"] = k_val
                new_row["time_ms"] = "timeout"
                new_row["row_status"] = "timeout_cascade"
                if "diversity" in new_row.index:
                    new_row["diversity"] = np.nan
                cascade_rows.append(new_row)
                cascade_count += 1
    if cascade_rows:
        frame = pd.concat([frame, pd.DataFrame(cascade_rows)], ignore_index=True)
    print(f"Cascade timeout rows added: {cascade_count}")

    dataset_order = sorted(frame["dataset"].astype(str).unique())
    facts_order = sorted(frame["facts"].astype(str).unique())
    source_order = sorted(frame["source"].astype(str).unique())

    frame["dataset"] = pd.Categorical(frame["dataset"].astype(str), categories=dataset_order, ordered=True)
    frame["facts"] = pd.Categorical(frame["facts"].astype(str), categories=facts_order, ordered=True)
    frame["source"] = pd.Categorical(frame["source"].astype(str), categories=source_order, ordered=True)

    sort_cols = [column for column in ["source", "dataset", "facts", "k", "mode", "lp_file", "goal", "row_status"] if column in frame.columns]
    frame = frame.sort_values(sort_cols).reset_index(drop=True)

    print("Loaded rows:", len(frame))
    print("Datasets:", list(frame["dataset"].cat.categories))
    print("Facts:", list(frame["facts"].cat.categories))
    print("Sources:", list(frame["source"].cat.categories))
    print("Modes:", sorted(frame["mode"].astype(str).unique()))
    print("k:", sorted(frame["k"].dropna().unique()))
    print("row_status:", frame["row_status"].value_counts(dropna=False).to_dict())
    vars_missing = int(pd.to_numeric(frame["vars"], errors="coerce").isna().sum()) if "vars" in frame.columns else "n/a"
    print("vars missing:", vars_missing)
    print(frame[["k", "mode"]].drop_duplicates().sort_values(["k", "mode"]).to_string(index=False))

    return frame


def prep_time_data(frame: pd.DataFrame) -> pd.DataFrame:
    if frame.empty:
        return frame

    sub = frame.copy()
    time_ms_num = pd.to_numeric(sub["time_ms"], errors="coerce")
    
    # Note: time_ms values are integers (no fractional ms). time=0 with diversity=NaN was already
    # detected as a timeout in load_and_prepare_data. Remaining time=0 rows (diversity=0 or > 0)
    # are legitimate fast completions; they get clamped to 1 ms below.

    sub["is_timeout"] = _plot_timeout_mask(sub)
    sub["time_ms_num"] = time_ms_num
    sub.loc[sub["is_timeout"], "time_ms_num"] = TIMEOUT_MS
    sub.loc[sub["time_ms_num"] < 1, "time_ms_num"] = 1
    sub = sub.dropna(subset=["time_ms_num"])
    if sub.empty:
        return sub

    sub["log_time"] = np.log10(sub["time_ms_num"])
    return sub


def draw_time_panel(ax: plt.Axes, frame: pd.DataFrame, algo: str) -> None:
    before = len(ax.collections)
    violin_kwargs = _violin_kwargs()

    sns.violinplot(
        data=frame,
        x="k",
        y="log_time",
        inner=None,
        cut=1.5,
        linewidth=0,
        width=VIOLIN_WIDTH,
        bw_adjust=BW_ADJUST,
        gridsize=GRIDSIZE,
        color=PALETTE.get(algo),
        ax=ax,
        **violin_kwargs,
    )

    for collection in ax.collections[before:]:
        if isinstance(collection, PolyCollection):
            collection.set_alpha(VIOLIN_ALPHA)

    sns.boxplot(
        data=frame,
        x="k",
        y="log_time",
        width=BOX_WIDTH,
        fliersize=0,
        whis=(0, 100),
        linewidth=BOX_LINEWIDTH,
        color=PALETTE.get(algo),
        ax=ax,
    )

    for patch in ax.patches:
        try:
            patch.set_alpha(BOX_ALPHA)
        except Exception:
            pass

    sns.stripplot(
        data=frame[~frame["is_timeout"]],
        x="k",
        y="log_time",
        color="black",
        size=POINT_SIZE_NORMAL,
        alpha=0.30,
        dodge=False,
        ax=ax,
    )
    sns.stripplot(
        data=frame[frame["is_timeout"]],
        x="k",
        y="log_time",
        color="red",
        size=POINT_SIZE_TIMEOUT,
        alpha=0.95,
        dodge=False,
        ax=ax,
    )

    for spine in ax.spines.values():
        spine.set_linewidth(1.1)
        spine.set_color("black")


def annotate_timeout_ratios(ax: plt.Axes, frame: pd.DataFrame, ymin: float, ymax: float) -> None:
    if frame.empty or "is_timeout" not in frame.columns:
        return

    x_values = sorted(pd.to_numeric(frame["k"], errors="coerce").dropna().astype(int).unique().tolist())
    if not x_values:
        return

    x_index = {k_value: idx for idx, k_value in enumerate(x_values)}
    y_span = max(float(ymax - ymin), 1.0)

    for k_value, group in frame.groupby("k", observed=True):
        try:
            k_int = int(k_value)
        except Exception:
            continue

        timeout_count = int(group["is_timeout"].sum())
        total_count = int(len(group))
        if timeout_count <= 0 or total_count <= 0:
            continue

        timeout_pct = round(100.0 * timeout_count / total_count)
        group_top = float(group["log_time"].max())
        y_text = min(group_top + 0.05 * y_span, ymax - 0.08 * y_span)
        ax.text(
            x_index[k_int],
            y_text,
            f"{timeout_pct}%",
            ha="center",
            va="bottom",
            fontsize=TIMEOUT_ANNOTATION_FONT_SIZE,
            color="red",
        )


def log_time_tick_labels(yticks: np.ndarray) -> list[str]:
    return ["0" if int(tick) == -1 else f"$10^{{{int(tick)}}}$" for tick in yticks]


def apply_time_axes_style(ax: plt.Axes, is_left: bool, yticks: np.ndarray) -> None:
    ax.set_xlabel("k", fontsize=FONT_SIZE)
    if is_left:
        ax.set_ylabel("Time (ms)", fontsize=FONT_SIZE)
        ax.set_yticklabels(log_time_tick_labels(yticks), fontsize=FONT_SIZE)
    else:
        ax.set_ylabel("")
        ax.tick_params(axis="y", labelleft=False)
        ax.set_yticklabels([])
    ax.tick_params(axis="x", labelsize=FONT_SIZE)
    ax.tick_params(axis="y", labelsize=FONT_SIZE)


def _time_axis_limits(frames: list[pd.DataFrame]) -> tuple[float, float, np.ndarray] | None:
    prepared = [frame for frame in frames if frame is not None and not frame.empty]
    if not prepared:
        return None

    all_logs = pd.concat([frame["log_time"] for frame in prepared], ignore_index=True)
    if all_logs.empty:
        return None

    ymin = np.floor(all_logs.min()) - 1
    ymax = np.ceil(all_logs.max()) + 1
    yticks = np.arange(ymin, ymax + 1)
    return ymin, ymax, yticks


def generate_time_plots(frame: pd.DataFrame, output_dir: Path) -> list[Path]:
    saved_files: list[Path] = []
    datasets_all = list(frame["dataset"].cat.categories) if hasattr(frame["dataset"], "cat") else sorted(frame["dataset"].astype(str).unique())
    facts_all = list(frame["facts"].cat.categories) if hasattr(frame["facts"], "cat") else sorted(frame["facts"].astype(str).unique())
    sources_all = list(frame["source"].cat.categories) if hasattr(frame["source"], "cat") else sorted(frame["source"].astype(str).unique())
    sources = [source for source in TIME_COMPARE_SOURCES if source in set(map(str, sources_all))]

    for dataset in datasets_all:
        frame_dataset = frame[frame["dataset"] == dataset].copy()
        if frame_dataset.empty:
            continue

        facts_present = [fact for fact in facts_all if (frame_dataset["facts"] == fact).any()]
        for fact in facts_present:
            frame_fact = frame_dataset[frame_dataset["facts"] == fact].copy()
            if frame_fact.empty:
                continue

            scenario_label = dataset
            dataset_label = _facts_label(fact)

            for algo in ["greedy", "optimal"]:
                prepared_by_source: dict[str, pd.DataFrame] = {}
                for source in sources:
                    raw = frame_fact[(frame_fact["source"] == source) & (frame_fact["mode"] == algo)].copy()
                    if raw.empty:
                        continue

                    required_k = _required_k_set()

                    k_num = pd.to_numeric(raw["k"], errors="coerce")
                    raw = raw[k_num.isin(required_k)].copy()
                    if raw.empty:
                        continue

                    # Apply complete-k filtering for consistent population across k
                    raw = _filter_complete_k(raw, goal_col="goal", required_k=required_k)
                    if raw.empty:
                        continue
                    raw = _cascade_plot_timeouts(raw, goal_col="goal")

                    prepared = prep_time_data(raw)
                    if prepared.empty:
                        continue
                    prepared_by_source[source] = prepared

                axis_limits = _time_axis_limits([prepared_by_source.get(source) for source in sources])
                if axis_limits is None:
                    continue

                ymin, ymax, yticks = axis_limits

                for source in sources:
                    prepared = prepared_by_source.get(source)
                    if prepared is None or prepared.empty:
                        continue

                    is_left = _is_leftmost_source(source)
                    fig_width = FIG_WIDTH_LEFT if is_left else FIG_WIDTH_OTHER
                    fig, ax = plt.subplots(figsize=(fig_width, FIG_HEIGHT))

                    draw_time_panel(ax, prepared, algo)
                    ax.set_title("")
                    ax.set_ylim(ymin, ymax)
                    ax.set_yticks(yticks)
                    ax.yaxis.set_minor_locator(mticker.NullLocator())
                    annotate_timeout_ratios(ax, prepared, ymin, ymax)
                    apply_time_axes_style(ax, is_left=is_left, yticks=yticks)

                    fig.tight_layout()
                    outpath = output_dir / _build_time_filename(scenario_label, dataset_label, algo, source)
                    fig.savefig(outpath, format="pdf", bbox_inches="tight")
                    plt.close(fig)

                    saved_files.append(outpath)
                    print("Saved:", outpath)

    print(f"\nDone. Saved {len(saved_files)} time PDF files.")
    return saved_files


def compute_rate(frame: pd.DataFrame) -> pd.DataFrame:
    empty = pd.DataFrame(columns=["dataset", "facts", "goal", "k", "greedy", "optimal", "rate"])

    sub = frame[frame["mode"].isin(["greedy", "optimal"])].copy()
    if sub.empty:
        return empty

    if "row_status" in sub.columns:
        sub = sub[sub["row_status"].astype(str) != "timeout_synth"].copy()

    sub["diversity_num"] = pd.to_numeric(sub["diversity"], errors="coerce")
    sub = sub.dropna(subset=["diversity_num"]).copy()
    if sub.empty:
        return empty

    agg = (
        sub.groupby(["dataset", "facts", "goal", "k", "mode"], as_index=False, observed=True)["diversity_num"]
        .mean()
    )

    pivot = (
        agg.pivot(index=["dataset", "facts", "goal", "k"], columns="mode", values="diversity_num")
        .reset_index()
    )

    if "greedy" not in pivot.columns:
        pivot["greedy"] = np.nan
    if "optimal" not in pivot.columns:
        pivot["optimal"] = np.nan

    pivot = pivot.dropna(subset=["greedy", "optimal"]).copy()
    both_zero = pivot["greedy"].eq(0) & pivot["optimal"].eq(0)
    pivot = pivot[both_zero | pivot["greedy"].ne(0)].copy()
    if pivot.empty:
        return empty

    pivot["rate"] = pivot["optimal"] / pivot["greedy"]
    pivot.loc[both_zero.reindex(pivot.index, fill_value=False), "rate"] = 1.0
    pivot["k"] = pd.to_numeric(pivot["k"], errors="coerce")
    pivot = pivot.dropna(subset=["k"]).copy()
    pivot["k"] = pivot["k"].astype(int)
    return pivot


def style_rate_axes(ax: plt.Axes, source: str) -> None:
    ax.set_xlabel("k", fontsize=FONT_SIZE)
    if _needs_rate_ylabel(source):
        ax.set_ylabel("Ratio", fontsize=FONT_SIZE)
    else:
        ax.set_ylabel("")
        ax.tick_params(axis="y", labelleft=False)

    ax.tick_params(axis="x", labelsize=FONT_SIZE)
    ax.tick_params(axis="y", labelsize=FONT_SIZE)
    for spine in ax.spines.values():
        spine.set_edgecolor("black")
        spine.set_linewidth(1.1)


def _rate_axis_limits(frames: list[pd.DataFrame]) -> tuple[float, float] | None:
    prepared = [frame for frame in frames if frame is not None and not frame.empty]
    if not prepared:
        return None

    all_rates = pd.concat([frame["rate"] for frame in prepared], ignore_index=True)
    if all_rates.empty:
        return None

    ymin = all_rates.min()
    ymax = all_rates.max()
    pad = 0.05 * (ymax - ymin) if ymax > ymin else 0.1
    ymin = max(0.0, ymin - pad)
    ymax = ymax + pad
    return ymin, ymax


def generate_rate_plots(frame: pd.DataFrame, output_dir: Path) -> list[Path]:
    saved_files: list[Path] = []
    datasets_all = list(frame["dataset"].cat.categories) if hasattr(frame["dataset"], "cat") else sorted(frame["dataset"].astype(str).unique())
    facts_all = list(frame["facts"].cat.categories) if hasattr(frame["facts"], "cat") else sorted(frame["facts"].astype(str).unique())
    sources_all = list(frame["source"].cat.categories) if hasattr(frame["source"], "cat") else sorted(frame["source"].astype(str).unique())
    sources = [source for source in RATE_COMPARE_SOURCES if source in set(map(str, sources_all))]
    datasets = [dataset for dataset in datasets_all if str(dataset) != "andersen"]

    for dataset in datasets:
        frame_dataset = frame[frame["dataset"] == dataset].copy()
        if frame_dataset.empty:
            continue

        facts_present = [fact for fact in facts_all if (frame_dataset["facts"] == fact).any()]
        for fact in facts_present:
            frame_fact = frame_dataset[frame_dataset["facts"] == fact].copy()
            if frame_fact.empty:
                continue

            scenario_label = dataset
            dataset_label = _facts_label(fact)
            rates_by_source: dict[str, pd.DataFrame] = {}

            for source in sources:
                source_frame = frame_fact[frame_fact["source"] == source].copy()
                blocked_goals = _k1_timeout_goals(source_frame, goal_col="goal")
                raw = frame_fact[frame_fact["source"] == source].copy()
                if raw.empty:
                    continue

                if blocked_goals:
                    raw = raw[~raw["goal"].astype(str).isin(blocked_goals)].copy()
                if raw.empty:
                    continue

                if RATE_FILTER_ONLY_COMPLETE_K:
                    raw = _filter_complete_k(raw, goal_col="goal")
                rates = compute_rate(raw)
                if rates.empty:
                    continue
                rates_by_source[source] = rates

            axis_limits = _rate_axis_limits([rates_by_source.get(source) for source in sources])
            if axis_limits is None:
                continue

            ymin, ymax = axis_limits

            for source in sources:
                rates = rates_by_source.get(source)
                if rates is None or rates.empty:
                    continue

                fig_width = FIG_WIDTH_LEFT if _is_leftmost_source(source) else FIG_WIDTH_OTHER
                fig, ax = plt.subplots(figsize=(fig_width, FIG_HEIGHT))

                sns.boxplot(
                    data=rates,
                    x="k",
                    y="rate",
                    color="#9467bd",
                    width=BOX_WIDTH,
                    fliersize=2,
                    linewidth=BOX_LINEWIDTH,
                    ax=ax,
                )
                sns.stripplot(
                    data=rates,
                    x="k",
                    y="rate",
                    color="black",
                    size=POINT_SIZE_RATE,
                    alpha=0.20,
                    dodge=False,
                    ax=ax,
                )

                ax.axhline(1.0, ls="--", lw=1.2, c="0.3")

                means = rates.groupby("k", observed=True)["rate"].mean().sort_index()
                for x_index, k_value in enumerate(list(means.index)):
                    mean_value = means.loc[k_value]
                    y_text = mean_value * 1.02 if mean_value > 0 else mean_value + 0.02
                    ax.text(x=x_index, y=y_text, s=f"{mean_value:.2f}", ha="center", va="bottom", fontsize=15, color="black")

                ax.set_title("")
                ax.set_ylim(ymin, ymax)
                if RATE_YLOG:
                    ax.set_yscale("log")
                style_rate_axes(ax, source)

                fig.tight_layout()
                outpath = output_dir / _build_rate_filename(scenario_label, dataset_label, source)
                fig.savefig(outpath, format="pdf", bbox_inches="tight")
                plt.close(fig)

                saved_files.append(outpath)
                print("Saved:", outpath)

    print(f"\nDone. Saved {len(saved_files)} rate PDF files.")
    return saved_files


def main() -> None:
    args = parse_args()
    if args.k_min > args.k_max:
        raise ValueError(f"Invalid k range: k-min ({args.k_min}) cannot be greater than k-max ({args.k_max}).")

    global PLOT_K_MIN, PLOT_K_MAX
    PLOT_K_MIN = int(args.k_min)
    PLOT_K_MAX = int(args.k_max)

    csv_path = Path(args.csv).resolve()
    output_dir = Path(args.output_dir).resolve()
    output_dir.mkdir(parents=True, exist_ok=True)

    configure_plotting()
    frame = load_and_prepare_data(csv_path)
    time_files = generate_time_plots(frame, output_dir)
    rate_files = generate_rate_plots(frame, output_dir)
    print(f"\nFinished. Generated {len(time_files) + len(rate_files)} PDF files in {output_dir}.")


if __name__ == "__main__":
    main()
