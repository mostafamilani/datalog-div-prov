#!/usr/bin/env bash
set -u

ROOT="/data/project/dblab/datalog-provdb"
JAVA_BIN="/usr/lib/jvm/java-21-openjdk-21.0.5.0.11-1.fc39.x86_64/bin/java"
INPUT_DIR="$ROOT/experiments/downward_top20_dag_unambigous"
OUT_DIR="$ROOT/experiments/logs/LP/dag-standard"
CONFIG="$ROOT/config/runner-top20-10min.properties"
MASTER_LOG="$OUT_DIR/run.master.log"
EXIT_FILE="$OUT_DIR/run.exitcode"

rm -rf "$OUT_DIR"
mkdir -p "$OUT_DIR"

{
  echo "[START] $(date -Is)"
  echo "[INFO] input=$INPUT_DIR"
  echo "[INFO] output=$OUT_DIR"
  echo "[INFO] config=$CONFIG"
  echo "[INFO] mode=LP numModels=-1 numInitialModels=2 print=true extra=false proof=dag ch=standard"
  echo "[INFO] per-goal LP timeout=10 min (lp.processTimeoutMinutes); BatchRunner guard timeout=11 min"
  echo "------------------------------------------------------------"
} > "$MASTER_LOG"

GRB_LICENSE_FILE="$HOME/.gurobi/gurobi.lic" \
LD_LIBRARY_PATH="$ROOT/code/lib:${LD_LIBRARY_PATH:-}" \
"$JAVA_BIN" \
  -Djava.library.path="$ROOT/code/lib" \
  -cp "$ROOT/build:$ROOT/code/lib/*" \
  BatchRunner \
  "$INPUT_DIR" \
  "$OUT_DIR" \
  LP \
  -1 \
  2 \
  true \
  false \
  dag \
  standard \
  "$CONFIG" \
  >> "$MASTER_LOG" 2>&1

rc=$?
echo "------------------------------------------------------------" >> "$MASTER_LOG"
echo "[END] $(date -Is) rc=$rc" >> "$MASTER_LOG"
printf "%s\n" "$rc" > "$EXIT_FILE"

exit "$rc"
