# ======================
# Java + JNI Makefile
# ======================

SRC_DIR     := code/java
LIB_DIR     := code/lib
BUILD_DIR   := build
CONFIG      := config/runner.properties

INPUT_DIR   ?= examples/downward
OUTPUT_DIR  ?= out/logs/LP

# Simplified LP options
PROOF       ?= dag           # dag | hypertree
MODE        ?= unambiguous   # arbitrary | unambiguous
K           ?= 2
PRINT       ?= true

JAVA_HOME ?= /usr/lib/jvm/java-21-openjdk-amd64
JAVA      := $(JAVA_HOME)/bin/java
JAVAC     := $(JAVA_HOME)/bin/javac

CLASSPATH := $(BUILD_DIR):$(LIB_DIR)/*:$(LIB_DIR)/Gralog/*:$(LIB_DIR)/Gralog/plugins/*
JAVA_LIBPATH := -Djava.library.path=$(LIB_DIR)

define RUN_JAVA
LD_LIBRARY_PATH="$(LIB_DIR):$(JAVA_HOME)/lib:$(JAVA_HOME)/lib/server:$$LD_LIBRARY_PATH" \
$(JAVA) $(JAVA_LIBPATH) -cp "$(CLASSPATH)" $(1)
endef

SOURCES := $(wildcard $(SRC_DIR)/*.java)

.PHONY: all info compile clean check run-single run-batch

all: run-batch

info:
	@echo "JAVA_HOME   = $(JAVA_HOME)"
	@$(JAVA) -version
	@$(JAVAC) -version
	@echo "CONFIG      = $(CONFIG)"
	@echo "INPUT_DIR    = $(INPUT_DIR)"
	@echo "OUTPUT_DIR   = $(OUTPUT_DIR)"
	@echo "PROOF       = $(PROOF)"
	@echo "MODE        = $(MODE)"
	@echo "K           = $(K)"
	@echo "PRINT       = $(PRINT)"

compile:
	@mkdir -p $(BUILD_DIR)
	@$(JAVAC) -cp "$(LIB_DIR)/*:$(LIB_DIR)/Gralog/*:$(LIB_DIR)/Gralog/plugins/*" -d $(BUILD_DIR) $(SOURCES)

clean:
	@rm -rf $(BUILD_DIR)

check:
	@echo "== Checking JNI dependencies =="
	@ls -lh $(LIB_DIR) || true
	@ldd $(LIB_DIR)/libwhysat.so | egrep "gurobi|not found|whysat|stdc\+\+|gcc|libm|pthread|dl" || true

# Run all .lp files under INPUT_DIR with simplified options.
run-batch: compile
	@mkdir -p "$(OUTPUT_DIR)"
	@$(call RUN_JAVA, BatchRunner \
		"$(INPUT_DIR)" \
		"$(OUTPUT_DIR)" \
		"$(K)" \
		"$(PRINT)" \
		"$(PROOF)" \
		"$(MODE)" \
		"$(CONFIG)")

# Run one file by passing INPUT_FILE and optional OUT_DIR.
# Example:
#   make run-single INPUT_FILE=examples/downward/graph_analytics/bitcoin_path_9005418_9005413.lp OUT_DIR=out/test-example K=1 PROOF=dag MODE=unambiguous
INPUT_FILE ?=
OUT_DIR ?= out/single
run-single: compile
	@if [ -z "$(INPUT_FILE)" ]; then echo "ERROR: set INPUT_FILE=/path/to/file.lp"; exit 2; fi
	@mkdir -p "$(OUT_DIR)"
	@$(call RUN_JAVA, LPModelEnumerator \
		"$(INPUT_FILE)" \
		"$(OUT_DIR)" \
		0 \
		"$(K)" \
		"$(PRINT)" \
		false \
		"$(PROOF)" \
		"$(MODE)" \
		false \
		"$(CONFIG)")
