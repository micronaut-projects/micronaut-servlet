#!/bin/sh
# Runs the fuzz suite on one engine (jetty|tomcat|undertow|jdk) or on all of them.
# Usage: test-suite-http-server-fuzz/run.sh [engine ...]   (-Dfuzz.seed=N is passed through FUZZ_SEED)
cd "$(dirname "$0")/.." || exit 1
engines="${@:-jetty tomcat undertow jdk}"
for e in $engines; do
  ./gradlew -Dpts.enabled=false -Dorg.gradle.daemon.registry.base=/tmp/gradle-fuzz ":test-suite-http-server-tck-$e:test" --tests 'io.micronaut.servlet.fuzz.ServletFuzzTest' -q --continue
  /usr/bin/python3 test-suite-http-server-fuzz/summarize.py "test-suite-http-server-tck-$e/build/test-results/test/TEST-io.micronaut.servlet.fuzz.ServletFuzzTest.xml" 300
done
