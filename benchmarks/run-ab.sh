#!/bin/zsh
# Interleaved A/B of two published servlet versions.
#   ./run-ab.sh <versionA> <versionB> [servers] [rounds]
# e.g. ./run-ab.sh 6.2.0-SNAPSHOT 6.2.0-MYCHANGE-SNAPSHOT "tomcat jetty undertow" 2
# Publish variants first: ./gradlew -PprojectVersion=6.2.0-MYCHANGE-SNAPSHOT publishToMavenLocal (runtime modules).
set -u
B=$(cd "$(dirname "$0")" && pwd); cd $B
VA=$1; VB=$2; SERVERS=${3:-"tomcat jetty undertow"}; ROUNDS=${4:-2}
OUT=$B/results-ab.txt; : > $OUT
for round in $(seq 1 $ROUNDS); do
for s in ${=SERVERS}; do
for v in $VA $VB; do
  echo "=== $s-$v-r$round" | tee -a $OUT
  ./gradlew -q -PservletVersion=$v run${(C)s} > $B/server-ab.log 2>&1 &
  GP=$!
  for i in $(seq 1 120); do curl -sf localhost:8085/plaintext >/dev/null && break; sleep 0.5; done
  wrk -t4 -c64 -d8s http://localhost:8085/plaintext >/dev/null 2>&1
  wrk -t4 -c64 -d8s -s post.lua http://localhost:8085/echo >/dev/null 2>&1
  for ep in plaintext json; do
    echo "--- GET /$ep c=64" >> $OUT
    wrk -t4 -c64 -d12s --latency http://localhost:8085/$ep | grep -E "Requests/sec|99%" >> $OUT
  done
  echo "--- POST /echo c=64" >> $OUT
  wrk -t4 -c64 -d12s --latency -s post.lua http://localhost:8085/echo | grep -E "Requests/sec|99%" >> $OUT
  PID=$(lsof -ti tcp:8085 -sTCP:LISTEN); kill $PID 2>/dev/null; kill $GP 2>/dev/null; wait $GP 2>/dev/null
  for i in $(seq 1 40); do lsof -ti tcp:8085 -sTCP:LISTEN >/dev/null || break; sleep 0.5; done
done; done; done
echo DONE >> $OUT
python3 $B/compare.py --ab $OUT $VA $VB
