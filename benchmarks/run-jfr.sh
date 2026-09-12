#!/bin/zsh
# usage: run-jfr.sh <server> <version> <endpoint> [post]
set -u
B=$(cd "$(dirname "$0")" && pwd); cd $B
S=$1; V=$2; EP=$3; POST=${4:-}
OUT=$B/jfr-$S-$EP.jfr; rm -f $OUT
./gradlew -q -PservletVersion=$V "-Pjvmextra=-XX:StartFlightRecording=filename=$OUT,settings=profile,dumponexit=true -XX:FlightRecorderOptions=stackdepth=96" run${(C)S} > $B/server-jfr-$S.log 2>&1 &
GP=$!
for i in $(seq 1 120); do curl -sf localhost:8085/plaintext >/dev/null && break; sleep 0.5; done
if [ -n "$POST" ]; then WRK="wrk -t4 -c64 -s post.lua"; else WRK="wrk -t4 -c64"; fi
${=WRK} -d8s http://localhost:8085/$EP >/dev/null 2>&1
${=WRK} -d20s --latency http://localhost:8085/$EP | grep -E "Requests/sec|99%|Latency"
PID=$(lsof -ti tcp:8085 -sTCP:LISTEN); kill $PID; kill $GP 2>/dev/null; wait $GP 2>/dev/null
for i in $(seq 1 60); do [ -s $OUT ] && ! lsof $OUT >/dev/null 2>&1 && break; sleep 0.5; done
ls -la $OUT
