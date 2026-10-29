#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
OUT=${OUT_DIR:-"$ROOT/pi-results"}
JAVA_OPTS=${JAVA_OPTS:-"-Xms32m -Xmx192m -XX:+UseSerialGC"}
RUN_JMH=${RUN_JMH:-1}
JMH_FORKS=${JMH_FORKS:-2}
JMH_WARMUPS=${JMH_WARMUPS:-3}
JMH_ITERATIONS=${JMH_ITERATIONS:-5}
JMH_TIME=${JMH_TIME:-500ms}

MAIN_JAR="$ROOT/experiment-010.jar"
JMH_JAR="$ROOT/experiment-010-jmh.jar"

mkdir -p "$OUT"

if ! command -v java >/dev/null 2>&1; then
    echo "ERROR: java is not installed or not on PATH." >&2
    echo "Install/select an ARMv6-compatible Java 8+ runtime before running Experiment 010." >&2
    exit 2
fi

if [ ! -f "$MAIN_JAR" ]; then
    echo "ERROR: missing $MAIN_JAR" >&2
    exit 2
fi

capture_environment() {
    phase=$1
    file="$OUT/environment-$phase.txt"

    {
        echo "phase=$phase"
        echo "utc=$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
        echo
        echo "== uname =="
        uname -a
        uname -m
        getconf LONG_BIT 2>/dev/null || true
        echo
        echo "== cpu =="
        grep -E '^(model name|Model|Hardware|Revision)' /proc/cpuinfo 2>/dev/null || true
        echo
        echo "== os =="
        cat /etc/os-release 2>/dev/null || true
        echo
        echo "== brainbox zero =="
        cat /etc/brainbox-zero-image 2>/dev/null || echo "not present"
        echo
        echo "== experiment bundle =="
        cat "$ROOT/BUNDLE-INFO" 2>/dev/null || echo "not present"
        echo
        echo "== java =="
        command -v java
        java -version
        echo
        java -XshowSettings:vm -version 2>&1 || true
        echo
        echo "== memory/load =="
        free -h 2>/dev/null || true
        uptime 2>/dev/null || true
        echo
        echo "== raspberry pi firmware state =="
        if command -v vcgencmd >/dev/null 2>&1; then
            vcgencmd get_throttled || true
            vcgencmd measure_temp || true
            vcgencmd measure_clock arm || true
        else
            echo "vcgencmd not available"
        fi
    } >"$file" 2>&1
}

capture_environment before

if [ -f "$ROOT/BUNDLE-SHA256SUMS" ]; then
    (
        cd "$ROOT"
        sha256sum -c BUNDLE-SHA256SUMS
    ) >"$OUT/bundle-sha256-check.txt" 2>&1
fi

echo "Running 500-entry ranking diagnostic..."
# shellcheck disable=SC2086
java $JAVA_OPTS -cp "$MAIN_JAR"     emb.experiment.ranking.RankingBenchmark 500     >"$OUT/ranking-500.csv"

echo "Running 500-row RegistrationList diagnostic..."
# shellcheck disable=SC2086
java $JAVA_OPTS -cp "$MAIN_JAR"     emb.experiment.active.RegistrationListBenchmark 500     >"$OUT/registration-list-500.csv"

if [ "$RUN_JMH" = "1" ]; then
    if [ ! -f "$JMH_JAR" ]; then
        echo "ERROR: RUN_JMH=1 but $JMH_JAR is missing" >&2
        exit 2
    fi

    echo "Running selected SortedArray vs PooledAVL JMH qualification..."
    # shellcheck disable=SC2086
    java $JAVA_OPTS -jar "$JMH_JAR"         'RankingIndexJmhBenchmark.rankQuery|RankingIndexJmhBenchmark.replacePrepareRankRoundTrip|RegistrationListJmhBenchmark.registrationSyncScan|RegistrationListJmhBenchmark.snapshot500|RegistrationListJmhBenchmark.startChangeAndSyncRoundTrip'         -p implementation=SORTED_ARRAY,POOLED_AVL         -f 2         -wi 3         -i 5         -w 500ms         -r 500ms         -prof gc         -rf json         -rff "$OUT/jmh-selected.json"
else
    echo "RUN_JMH=0: selected JMH qualification skipped."
fi

capture_environment after

{
    echo "Experiment 010 Pi qualification"
    echo "utc_finished=$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
    echo "java_opts=$JAVA_OPTS"
    echo "run_jmh=$RUN_JMH"
    echo "jmh_forks=$JMH_FORKS"
    echo "jmh_warmups=$JMH_WARMUPS"
    echo "jmh_iterations=$JMH_ITERATIONS"
    echo "jmh_time=$JMH_TIME"
    echo "result_dir=$OUT"
} >"$OUT/run.txt"

echo "Done. Results: $OUT"
