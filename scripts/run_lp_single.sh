#!/usr/bin/env bash
set -euo pipefail

# Usage:
#   ./scripts/run_lp_single.sh <downward_file.lp> <out_dir> <config.properties> [proof] [ch] [metricsOnly]
# Example:
#   ./scripts/run_lp_single.sh /tmp/q.lp out/single config/runner.properties dag unambiguous false

if [[ $# -lt 3 || $# -gt 6 ]]; then
  echo "Usage: $0 <downward_file.lp> <out_dir> <config.properties> [proof] [ch] [metricsOnly]"
  exit 1
fi

DOWNWARD_FILE="$1"
OUT_DIR="$2"
CONFIG_FILE="$3"
PROOF="${4:-dag}"
CH="${5:-unambiguous}"
METRICS_ONLY="${6:-false}"

if [[ ! -f "$DOWNWARD_FILE" ]]; then
  echo "Input file not found: $DOWNWARD_FILE"
  exit 2
fi

if [[ ! -f "$CONFIG_FILE" ]]; then
  echo "Config file not found: $CONFIG_FILE"
  exit 2
fi

mkdir -p "$OUT_DIR"
mkdir -p build

JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"
JAVA_BIN="$JAVA_HOME/bin/java"
JAVAC_BIN="$JAVA_HOME/bin/javac"

if [[ ! -x "$JAVA_BIN" || ! -x "$JAVAC_BIN" ]]; then
  echo "Java 21 not found at JAVA_HOME=$JAVA_HOME"
  echo "Set JAVA_HOME to your Java 21 installation and retry."
  exit 2
fi

CLASSPATH="build:code/lib/*:code/lib/Gralog/*:code/lib/Gralog/plugins/*"

"$JAVAC_BIN" -cp "code/lib/*:code/lib/Gralog/*:code/lib/Gralog/plugins/*" -d build code/java/*.java

LD_LIBRARY_PATH="code/lib:${LD_LIBRARY_PATH:-}" \
"$JAVA_BIN" -Djava.library.path=code/lib -cp "$CLASSPATH" \
  LPModelEnumerator \
  "$DOWNWARD_FILE" \
  "$OUT_DIR" \
  -1 \
  2 \
  true \
  false \
  "$PROOF" \
  "$CH" \
  "$METRICS_ONLY" \
  "$CONFIG_FILE"
