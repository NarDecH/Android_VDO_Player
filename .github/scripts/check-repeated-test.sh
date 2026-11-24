#!/usr/bin/env bash
#
# Fails when the test the repeated emulator run watches is not declared in the
# instrumented sources.
#
# The name has one home: `repeated_test=` in run-emulator-playback-test.sh, which writes
# it to `emulator-diagnostics/repeated-test.txt` for the workflow's summary. A rename that
# updates the test but not that line leaves the rounds reporting on a name nothing
# produces, and the summary can only say so once the emulator has already spent its
# minutes. This reads the same line and asks the source tree whether an instrumented test
# of that name exists, which costs a second and runs before the emulator.
#
# A JUnit test is the function under `@Test`, so the annotation is part of what is
# checked: a helper with the same name would satisfy a bare `fun` search and watch
# nothing.
#
# Usage:
#   bash .github/scripts/check-repeated-test.sh [runner-script] [androidTest-dir]
set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
runner=${1:-$root/.github/scripts/run-emulator-playback-test.sh}
tests=${2:-$root/app/src/androidTest}

if [[ ! -f $runner ]]; then
  printf 'no runner script at %s\n' "$runner" >&2
  exit 1
fi
if [[ ! -d $tests ]]; then
  printf 'no instrumented test directory at %s\n' "$tests" >&2
  exit 1
fi

# The assignment, not a copy of its value.
name=$(sed -n 's/^repeated_test="\([^"]*\)"$/\1/p' "$runner" | head -1)
if [[ -z $name ]]; then
  printf 'could not read `repeated_test="..."` from %s\n' "${runner#"$root"/}" >&2
  printf 'this check reads that assignment; keep it on a line of its own\n' >&2
  exit 1
fi

# Three lines is enough for an annotation, a second annotation or a blank line before the
# declaration, and short enough that a `@Test` three functions up cannot vouch for this one.
declarations=$(find "$tests" -type f -name '*.kt' -print0 |
  while IFS= read -r -d '' file; do
    awk -v name="$name" -v file="$file" '
      { previous[NR % 4] = $0 }
      $0 ~ "^[[:space:]]*fun[[:space:]]+" name "[[:space:]]*\\(" {
        for (back = 1; back <= 3; back++) {
          if (previous[(NR - back) % 4] ~ /^[[:space:]]*@Test([[:space:]]|$)/) {
            print file ":" NR
            next
          }
        }
      }
    ' "$file"
  done)

if [[ -z $declarations ]]; then
  printf 'the repeated run watches %s, but no `@Test` in %s declares it\n' \
    "$name" "${tests#"$root"/}" >&2
  cat >&2 <<EOF

Either the test was renamed and ${runner#"$root"/} still names the old one — put the new
name in \`repeated_test=\` there — or the test was removed, in which case the rounds are no
longer guarding anything and the repetition should go with it.
EOF
  exit 1
fi

first=${declarations%%$'\n'*}
printf 'the repeated run watches %s, declared at %s\n' "$name" "${first#"$root"/}"
