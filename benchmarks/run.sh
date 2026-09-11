#!/bin/zsh
set -u
B=$(cd "$(dirname "$0")" && pwd); cd $B
OUT=$B/results.txt; : > $OUT
for s in netty tomcat jetty undertow jdk; do
  echo "=== $s" | tee -a $OUT
  ./gradlew -q run${(C)s} > $B/server-$s.log 2>&1 &
  GP=$!
  for i in $(seq 1 120); do curl -sf localhost:8085/plaintext >/dev/null && break; sleep 0.5; done
  grep -h STARTUP_MS $B/server-$s.log | tee -a $OUT
  curl -si localhost:8085/plaintext | head -8 >> $OUT
  # warmup
  wrk -t4 -c64 -d10s http://localhost:8085/plaintext >/dev/null 2>&1
  wrk -t4 -c64 -d10s -s post.lua http://localhost:8085/echo >/dev/null 2>&1
  for ep in plaintext json; do
    echo "--- GET /$ep c=64" >> $OUT
    wrk -t4 -c64 -d15s --latency http://localhost:8085/$ep | grep -E "Requests/sec|Latency|99%|Non-2xx|Socket" >> $OUT
  done
  echo "--- POST /echo c=64" >> $OUT
  wrk -t4 -c64 -d15s --latency -s post.lua http://localhost:8085/echo | grep -E "Requests/sec|Latency|99%|Non-2xx|Socket" >> $OUT
  echo "--- GET /plaintext c=256" >> $OUT
  wrk -t8 -c256 -d15s --latency http://localhost:8085/plaintext | grep -E "Requests/sec|Latency|99%|Non-2xx|Socket" >> $OUT
  PID=$(lsof -ti tcp:8085 -sTCP:LISTEN); kill $PID 2>/dev/null; kill $GP 2>/dev/null; wait $GP 2>/dev/null
  for i in $(seq 1 40); do lsof -ti tcp:8085 -sTCP:LISTEN >/dev/null || break; sleep 0.5; done
done
echo DONE >> $OUT
