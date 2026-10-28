#!/usr/bin/env bash
set -uo pipefail

workspace="${GITHUB_WORKSPACE:?GITHUB_WORKSPACE is required}"
cd "$workspace/android" || exit 1
mkdir -p "$workspace/diagnostics"

adb logcat -c || echo 'Could not clear logcat before the test run.'
gradle --no-daemon -Dorg.gradle.jvmargs="-Xmx1536m -Dfile.encoding=UTF-8" \
  :app:connectedDebugAndroidTest \
  :shura-extension-runtime:connectedDebugAndroidTest \
  2>&1 | tee "$workspace/android-emulator-gradle.log"
status=${PIPESTATUS[0]}

cp "$workspace/android-emulator-gradle.log" \
  "$workspace/diagnostics/" 2>/dev/null || true
find app/build shura-extension-runtime/build -name '*.xml' -path '*connected*' \
  -exec cp --parents {} "$workspace/diagnostics/" \; 2>/dev/null || true
find app/build/reports shura-extension-runtime/build/reports -type f \
  -exec cp --parents {} "$workspace/diagnostics/" \; 2>/dev/null || true
adb logcat -d -v threadtime > "$workspace/diagnostics/logcat.txt" 2>&1 || \
  echo 'adb logcat failed: no device' > "$workspace/diagnostics/logcat.txt"

{
  echo "### Emulator: App startup and real extension load"
  echo
  echo "gradle exit status: $status"
  echo
  echo '#### Gradle tail'
  echo '```'
  tail -60 "$workspace/android-emulator-gradle.log"
  echo '```'
  echo
  if find "$workspace/diagnostics" -name 'TEST-*.xml' | grep -q .; then
    echo '#### Per-test results'
    echo '```'
    python3 "$workspace/scripts/junit_summary.py" \
      $(find "$workspace/diagnostics" -name 'TEST-*.xml') || true
    echo '```'
    for file in $(find "$workspace/diagnostics" -name 'TEST-*.xml'); do
      python3 "$workspace/scripts/junit_summary.py" "$file" \
        | grep -E '^   (FAIL|ERROR)' \
        | sed 's/^   //' \
        | while read -r line; do
            echo "::error title=Instrumentation test failed::${line}"
          done
    done
  else
    echo '#### No JUnit XML'
    echo 'The instrumentation produced no XML, so no test reported results.'
    echo 'See the Gradle tail and logcat above.'
  fi
} >> "$GITHUB_STEP_SUMMARY"

exit "$status"
