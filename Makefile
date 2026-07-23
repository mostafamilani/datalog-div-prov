# ======================
# Java + JNI Makefile
# ======================

# ---- Paths ----
SRC_DIR     := code/java
LIB_DIR     := code/lib
BUILD_DIR   := build
CONFIG      := config/runner.properties
EXPERIMENTS := examples/downward
OUTPUT_ROOT := out/logs/LP/

# ---- Java 21 ----
JAVA_HOME ?= /usr/lib/jvm/java-21-openjdk-amd64
JAVA      := $(JAVA_HOME)/bin/java
JAVAC     := $(JAVA_HOME)/bin/javac

# ---- Classpath ----
CLASSPATH := $(BUILD_DIR):$(LIB_DIR)/*:$(LIB_DIR)/Gralog/*:$(LIB_DIR)/Gralog/plugins/*

# ---- Runtime flags (IMPORTANT for JNI + Gurobi) ----
# java.library.path helps Java find JNI libs (e.g., libwhysat.so)
JAVA_LIBPATH := -Djava.library.path=$(LIB_DIR)

# LD_LIBRARY_PATH helps the OS loader find dependent .so libs (e.g., libgurobi.so)
define RUN_JAVA
LD_LIBRARY_PATH="$(LIB_DIR):$(JAVA_HOME)/lib:$(JAVA_HOME)/lib/server:$$LD_LIBRARY_PATH" \
$(JAVA) $(JAVA_LIBPATH) -cp "$(CLASSPATH)" $(1)
endef

# ---- Find sources ----
SOURCES := $(wildcard $(SRC_DIR)/*.java)

# ======================
# LP proof/ch parameters
# ======================
# You can set these when calling make:
#   make run-batch-lp PROOF=dag CH=unambiguous
#   make run-batch-lp PROOF=hypergraph CH=standard
#
# Or using a combo:
#   make run-batch-lp PROFILE=dag-standard
#   make run-batch-lp PROFILE=hypertree-unambigous
#
# Accepted:
#   PROOF  = dag | hypergraph | hypertree
#   CH     = standard | unambiguous | unambigous
#
# Defaults:
#PROOF   ?= dag
#CH      ?= unambiguous

PROOF   ?= hypertree
CH      ?= unambiguous

#PROFILE ?=

# If PROFILE is provided, derive PROOF/CH from it.
# PROFILE examples:
#   dag-standard
#   hypertree-unambigous
#   hypergraph-unambiguous
ifeq ($(strip $(PROFILE)),)
  # keep PROOF/CH as-is
else
  PROF1 := $(word 1,$(subst -, ,$(PROFILE)))
  PROF2 := $(word 2,$(subst -, ,$(PROFILE)))
  ifneq ($(strip $(PROF1)),)
    PROOF := $(PROF1)
  endif
  ifneq ($(strip $(PROF2)),)
    CH := $(PROF2)
  endif
endif

# A normalized label for output folder naming (keeps whatever user provided)
LP_TAG := $(PROOF)-$(CH)

# ======================
# Targets
# ======================

.PHONY: all info compile run clean check run-batch-lp run-batch-lp-metrics run-lp-bugs run-folder-range

all: run-batch-lp

info:
	@echo "JAVA_HOME   = $(JAVA_HOME)"
	@$(JAVA) -version
	@$(JAVAC) -version
	@echo "LIB_DIR     = $(LIB_DIR)"
	@echo "CLASSPATH   = $(CLASSPATH)"
	@echo "CONFIG      = $(CONFIG)"
	@echo "PROOF       = $(PROOF)"
	@echo "CH          = $(CH)"
	@echo "PROFILE     = $(PROFILE)"
	@echo "LP_TAG      = $(LP_TAG)"

compile:
	@mkdir -p $(BUILD_DIR)
	@$(JAVAC) -cp "$(LIB_DIR)/*:$(LIB_DIR)/Gralog/*:$(LIB_DIR)/Gralog/plugins/*" -d $(BUILD_DIR) $(SOURCES)

# Optional: only runs if config/runner.properties exists
run: compile
	@if [ -f "$(CONFIG)" ]; then \
		$(call RUN_JAVA, BatchRunner $(EXPERIMENTS) $(OUTPUT_ROOT) LP -1 2 true false $(PROOF) $(CH) $(CONFIG)); \
	else \
		echo "No $(CONFIG) found. Use 'make run-batch-lp' or create $(CONFIG)."; \
	fi

clean:
	@rm -rf $(BUILD_DIR)

# ---- Paths for LP batch run ----
INPUT_DIR   ?= $(EXPERIMENTS)

# Main target you are using
# NOTE: output folder becomes .../LP/<proof>-<ch>/
OUTPUT_DIR := $(OUTPUT_ROOT)$(LP_TAG)/

run-batch-lp: compile
	@echo "== Running BatchRunner (LP) with proof=$(PROOF), ch=$(CH) =="
	@echo "== OUTPUT_DIR = $(OUTPUT_DIR) =="
	@mkdir -p "$(OUTPUT_DIR)"
	@$(call RUN_JAVA, BatchRunner \
		$(INPUT_DIR) \
		$(OUTPUT_DIR) \
		LP \
		-1 \
		2 \
		true \
		false \
		$(PROOF) \
		$(CH) \
		$(CONFIG))

run-batch-lp-metrics: compile
	@echo "== Running BatchRunner (LP metrics only) with proof=$(PROOF), ch=$(CH) =="
	@echo "== OUTPUT_DIR = $(OUTPUT_ROOT)$(LP_TAG)-metrics/ =="
	@mkdir -p "$(OUTPUT_ROOT)$(LP_TAG)-metrics/"
	@$(call RUN_JAVA, BatchRunner \
		$(INPUT_DIR) \
		$(OUTPUT_ROOT)$(LP_TAG)-metrics/ \
		LP \
		0 \
		1 \
		false \
		false \
		$(PROOF) \
		$(CH) \
		true \
		$(CONFIG))

# ---- Run only LPs from a specific subfolder (e.g., downward/bugs) ----
INPUT_DIR_SUB ?= examples/downward/bugs/
OUTPUT_DIR_SUB ?= out/logs/bugs/
LOG_FILE := $(OUTPUT_DIR_SUB)/run-lp-bugs.log

run-lp-bugs: compile
	@mkdir -p "$(OUTPUT_DIR_SUB)"
	@echo "== Running BatchRunner (LP) on $(INPUT_DIR_SUB) with proof=$(PROOF), ch=$(CH) ==" > "$(LOG_FILE)"
	@echo "== OUTPUT_DIR = $(OUTPUT_DIR_SUB) ==" >> "$(LOG_FILE)"
	@$(call RUN_JAVA, BatchRunner \
		$(INPUT_DIR_SUB) \
		$(OUTPUT_DIR_SUB) \
		LP \
		-1 \
		1 \
		true \
		true \
		$(PROOF) \
		$(CH) \
		$(CONFIG)) >> "$(LOG_FILE)" 2>&1

# Quick sanity checks for runtime linking
check:
	@echo "== Checking that JNI + dependencies can be found =="
	@echo "-- ls $(LIB_DIR) --"
	@ls -lh $(LIB_DIR) || true
	@echo ""
	@echo "-- ldd on JNI library (expect libgurobi.so resolved) --"
	@ldd $(LIB_DIR)/libwhysat.so | egrep "gurobi|not found|whysat|stdc\+\+|gcc|libm|pthread|dl" || true
	@echo ""
	@echo "-- Showing LD_LIBRARY_PATH that will be used --"
	@echo "$(LIB_DIR):$$LD_LIBRARY_PATH"

# ======================
# run-folder-range (FIXED)
# ======================

FOLDER     ?= examples/downward/bugs
RESULT_DIR ?= out/logs/bugs

PRINT      ?= true
EXTRA      ?= false

MASTER_LOG := $(RESULT_DIR)/optimal.log

MIN_MODELS ?= 2
MAX_MODELS ?= 5

# --- timeout (minutes) per LPModelEnumerator call ---
TIMEOUT_MIN ?= 10

.PHONY: run-folder-range
run-folder-range: compile
	@if [ -z "$(FOLDER)" ]; then echo "ERROR: set FOLDER=/path/to/lp_files"; exit 2; fi
	@if [ -z "$(RESULT_DIR)" ]; then echo "ERROR: set RESULT_DIR=/path/to/results"; exit 2; fi
	@mkdir -p "$(RESULT_DIR)"

	@MASTER_LOG="$(RESULT_DIR)/optimal.log"; \
	echo "== run-folder-range =="                    >  "$$MASTER_LOG"; \
	echo "FOLDER      = $(FOLDER)"                  >> "$$MASTER_LOG"; \
	echo "RESULT_DIR  = $(RESULT_DIR)"              >> "$$MASTER_LOG"; \
	echo "MIN_MODELS  = $(MIN_MODELS)"              >> "$$MASTER_LOG"; \
	echo "MAX_MODELS  = $(MAX_MODELS)"              >> "$$MASTER_LOG"; \
	echo "TIMEOUT_MIN = $(TIMEOUT_MIN)"             >> "$$MASTER_LOG"; \
	echo "PRINT       = $(PRINT)"                   >> "$$MASTER_LOG"; \
	echo "EXTRA       = $(EXTRA)"                   >> "$$MASTER_LOG"; \
	echo "PROOF       = $(PROOF)"                   >> "$$MASTER_LOG"; \
	echo "CH          = $(CH)"                      >> "$$MASTER_LOG"; \
	echo "CONFIG      = $(CONFIG)"                  >> "$$MASTER_LOG"; \
	echo "----------------------------------------" >> "$$MASTER_LOG"; \
	\
	FOUND=0; \
	while IFS= read -r FILE; do \
		FOUND=1; \
		REL="$${FILE#$(FOLDER)/}"; \
		RELDIR="$$(dirname "$$REL")"; \
		OUTDIR="$(RESULT_DIR)/$$RELDIR"; \
		mkdir -p "$$OUTDIR"; \
		for N in $$(seq $(MIN_MODELS) $(MAX_MODELS)); do \
			echo "[RUN] $$FILE  (numModels=0, numInit=$$N)" | tee -a "$$MASTER_LOG"; \
			echo "  [OUT] $$OUTDIR"                         | tee -a "$$MASTER_LOG"; \
			\
			# Build the FULL command with concrete args (no $FILE/$OUTDIR/$N left for a nested shell) \
			CMD="$(call RUN_JAVA,LPModelEnumerator) \"$$FILE\" \"$$OUTDIR\" 0 \"$$N\" \"$(PRINT)\" \"$(EXTRA)\" \"$(PROOF)\" \"$(CH)\" \"$(CONFIG)\""; \
			echo "[CMD] timeout $(TIMEOUT_MIN)m $$CMD" | tee -a "$$MASTER_LOG"; \
			\
			# Run it directly (no bash -lc needed now) \
			timeout $(TIMEOUT_MIN)m bash -c "$$CMD" >> "$$MASTER_LOG" 2>&1; \
			RC=$$?; \
			if [ $$RC -eq 124 ]; then \
				echo "[TIMEOUT after $(TIMEOUT_MIN)m] $$FILE (numInit=$$N) -- stopping further N for this file" | tee -a "$$MASTER_LOG"; \
				echo "" >> "$$MASTER_LOG"; \
				break; \
			elif [ $$RC -ne 0 ]; then \
				echo "[FAIL rc=$$RC] $$FILE (numInit=$$N)" | tee -a "$$MASTER_LOG"; \
				exit $$RC; \
			else \
				echo "[OK] $$FILE (numInit=$$N)" | tee -a "$$MASTER_LOG"; \
			fi; \
			echo "" >> "$$MASTER_LOG"; \
		done; \
	done < <(find "$(FOLDER)" -type f -name "*.lp" | sort); \
	if [ $$FOUND -eq 0 ]; then \
		echo "No .lp files found under: $(FOLDER)" | tee -a "$$MASTER_LOG"; \
		exit 3; \
	fi; \
	echo "Done. Master log: $$MASTER_LOG" | tee -a "$$MASTER_LOG"



















# ======================
# run-lp-3profiles (per-file, 3 profiles, LPModelEnumerator)
# Mirrors INPUT_DIR folder structure under OUTPUT_ROOT/<profile>/
# Single universal log at OUTPUT_ROOT/
# ======================

LP_NUM_MODELS := -1
LP_NUM_INIT   := 2
LP_PRINT      := true
LP_EXTRA      := false

# ---- per-run timeout (40 minutes) ----
LP_RUN_TIMEOUT := 40m
TIMEOUT_BIN ?= timeout

LP_3P_MASTER_LOG := $(OUTPUT_ROOT)/three-profiles.master.log

# ---- JVM memory cap (tune as needed) ----
# Good starting point on a 128GB VM (single Java process at a time):
LP_JAVA_XMX ?= 96g
# Optional: leave Xms unset, or set small to avoid grabbing memory upfront
# LP_JAVA_XMS ?= 4g

# JVM opts injected without modifying RUN_JAVA
LP_JAVA_TOOL_OPTIONS := -Xmx$(LP_JAVA_XMX) -XX:+ExitOnOutOfMemoryError

.PHONY: run-lp-3profiles
run-lp-3profiles: compile
	@mkdir -p "$(OUTPUT_ROOT)"
	@echo "== Running per-file, 3 profiles (LPModelEnumerator) ==" >  "$(LP_3P_MASTER_LOG)"
	@echo "== INPUT_DIR   = $(INPUT_DIR) =="                      >> "$(LP_3P_MASTER_LOG)"
	@echo "== OUTPUT_ROOT = $(OUTPUT_ROOT) =="                     >> "$(LP_3P_MASTER_LOG)"
	@echo "== counts: numModels=$(LP_NUM_MODELS) numInit=$(LP_NUM_INIT) ==" >> "$(LP_3P_MASTER_LOG)"
	@echo "== per-run timeout: $(LP_RUN_TIMEOUT) =="               >> "$(LP_3P_MASTER_LOG)"
	@echo "== JVM: Xmx=$(LP_JAVA_XMX) =="                          >> "$(LP_3P_MASTER_LOG)"
	@echo "------------------------------------------------------------"     >> "$(LP_3P_MASTER_LOG)"
	@set -u; \
	find "$(INPUT_DIR)" -type f -name "*.lp" | sort | while IFS= read -r FILE; do \
		REL="$${FILE#$(INPUT_DIR)}"; \
		RELDIR="$$(dirname "$$REL")"; \
		for PROFILE in dag-unambigous hypertree-unambigous hypertree-standard; do \
			PROF1="$$(echo "$$PROFILE" | cut -d- -f1)"; \
			PROF2="$$(echo "$$PROFILE" | cut -d- -f2)"; \
			OUTDIR="$(OUTPUT_ROOT)$$PROFILE/$$RELDIR/"; \
			mkdir -p "$$OUTDIR"; \
			echo "[RUN] $$FILE  profile=$$PROFILE (timeout=$(LP_RUN_TIMEOUT))" >> "$(LP_3P_MASTER_LOG)"; \
			echo "  [OUT] $$OUTDIR"                                             >> "$(LP_3P_MASTER_LOG)"; \
			RC=0; \
			$(TIMEOUT_BIN) --foreground "$(LP_RUN_TIMEOUT)" \
				env JAVA_TOOL_OPTIONS='$(LP_JAVA_TOOL_OPTIONS)' \
				$(call RUN_JAVA, LPModelEnumerator \
					"$$FILE" \
					"$$OUTDIR" \
					$(LP_NUM_MODELS) \
					$(LP_NUM_INIT) \
					"$(LP_PRINT)" \
					"$(LP_EXTRA)" \
					"$$PROF1" \
					"$$PROF2" \
					"$(CONFIG)") >> "$(LP_3P_MASTER_LOG)" 2>&1 || RC=$$?; \
			if [ $$RC -eq 0 ]; then \
				echo "[OK] $$FILE  profile=$$PROFILE" >> "$(LP_3P_MASTER_LOG)"; \
			elif [ $$RC -eq 124 ]; then \
				echo "[TIMEOUT rc=$$RC] $$FILE profile=$$PROFILE (limit=$(LP_RUN_TIMEOUT))" >> "$(LP_3P_MASTER_LOG)"; \
			else \
				echo "[FAIL rc=$$RC] $$FILE profile=$$PROFILE" >> "$(LP_3P_MASTER_LOG)"; \
			fi; \
			echo "" >> "$(LP_3P_MASTER_LOG)"; \
		done; \
	done; \
	echo "Done. Master log: $(LP_3P_MASTER_LOG)" >> "$(LP_3P_MASTER_LOG)"; \
	exit 0