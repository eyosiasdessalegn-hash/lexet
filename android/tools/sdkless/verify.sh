#!/usr/bin/env bash
# Checks the Android sources without the Android SDK (used where Google's Maven is unreachable):
#  1. compiles the :domain module + its tests with the standalone Kotlin compiler and runs them;
#  2. parses every Kotlin/KTS file with the compiler's parser (syntax only);
#  3. runs the app's generated archive/search SQL against SQLite (FTS4) with the Room schema.
# Usage: tools/sdkless/verify.sh [path-to-kotlinc-dir]
set -euo pipefail
cd "$(dirname "$0")/../.."
HERE=tools/sdkless
KC=${1:-${KOTLINC_HOME:-}}
OUT=$(mktemp -d)
if [ -z "$KC" ]; then
  echo "Downloading Kotlin compiler 2.1.20…"
  curl -sSL -o "$OUT/kc.zip" https://github.com/JetBrains/kotlin/releases/download/v2.1.20/kotlin-compiler-2.1.20.zip
  unzip -q "$OUT/kc.zip" -d "$OUT" && KC="$OUT/kotlinc"
fi
L=$KC/lib
J="java -Dstdout.encoding=UTF-8 -Dfile.encoding=UTF-8"

echo "== 1. domain tests"
"$KC/bin/kotlinc" domain/src/main/kotlin domain/src/test/kotlin $HERE/junit-stub $HERE/Runner.kt -cp "$L/kotlin-test.jar" -d "$OUT/t" -jvm-target 17 -nowarn
$J -cp "$OUT/t:$L/kotlin-stdlib.jar:$L/kotlin-test.jar" RunnerKt $(grep -ho "^class [A-Za-z]*Test" domain/src/test/kotlin/app/lexet/domain/*.kt | sed 's/class /app.lexet.domain./')

echo "== 2. syntax of all Kotlin sources"
"$KC/bin/kotlinc" $HERE/SyntaxCheck.kt -cp "$L/kotlin-compiler.jar" -d "$OUT/s" -nowarn
$J -cp "$OUT/s:$L/kotlin-compiler.jar:$L/kotlin-stdlib.jar:$L/trove4j.jar:$L/kotlin-reflect.jar" SyntaxCheckKt .

echo "== 3. archive/search SQL against SQLite"
"$KC/bin/kotlinc" domain/src/main/kotlin app/src/main/kotlin/app/lexet/data/ArchiveQuery.kt $HERE/QueryHarness.kt -d "$OUT/q" -jvm-target 17 -nowarn
$J -cp "$OUT/q:$L/kotlin-stdlib.jar" QueryHarnessKt ../shared/taxonomy.json > "$OUT/cases.json"
python3 $HERE/query_check.py "$OUT/cases.json"
