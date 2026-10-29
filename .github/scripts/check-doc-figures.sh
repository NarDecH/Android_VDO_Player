#!/usr/bin/env bash
#
# Compares the figures the manual and the research page print with the APKs the
# build just produced.
#
# Those numbers are what a downloader reads to decide which file to take, and
# nothing was checking them. 1.4.0 went to the release page with a research page
# claiming R8 left 4.75 MB of dex — no build ever produced that number — and a
# `libavcodec.so` of "5.6 MB" in a file that measures 5,679,760 bytes. Both were
# found by downloading the assets and measuring them, after publishing. A number
# in prose does not fail anything on its own, so it is compared here with the APKs
# the same commit built: a size that drifts, a dex count that changes, a native
# library that is not the one described — the commit that changes it fails, not
# the tag that publishes it.
#
# The historical column of the research table (1.0.0–1.3.0) is deliberately not
# checked. Those APKs are not built any more, and the column describes releases
# that are already published, not something this commit can change.
#
# Every figure has to be found. A sentence that no longer matches the shapes read
# here fails the job instead of passing quietly, because a parser that finds
# nothing looks exactly like a document that is correct.
#
# Usage: check-doc-figures.sh <apk-dir> [docs-dir]
#
# Requires unzip: the uncompressed sizes of the dex files and the native libraries
# live in the APK's zip directory, and no SDK tool reports them.
set -euo pipefail

if [[ $# -lt 1 || $# -gt 2 ]]; then
  echo "usage: $(basename "$0") <apk-dir> [docs-dir]" >&2
  exit 2
fi

apk_dir=$1
repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
docs=${2:-"$repo_root/docs"}

if ! command -v unzip >/dev/null 2>&1; then
  echo "missing unzip — the sizes inside an APK are only in its zip directory" >&2
  exit 1
fi

research_md="$docs/RESEARCH.md"
research_html="$docs/RESEARCH.html"
readme_md="$docs/README.md"
readme_html="$docs/README.html"
for doc in "$research_md" "$research_html" "$readme_md" "$readme_html"; do
  if [[ ! -f $doc ]]; then
    echo "missing $doc" >&2
    exit 1
  fi
done

# Release order, the order the manual lists them in.
artifacts=(arm64-v8a armeabi-v7a x86_64 universal)

# CRLF is what Git hands Windows checkouts, and `unzip -l` prints it here too;
# a stray \r turns "11 MB" into a value nothing can parse.
strip_cr() {
  tr -d '\r'
}

# Rounded to the nearest MB, which is the precision both documents print in.
round_mib() {
  awk -v bytes="$1" 'BEGIN { printf "%d", bytes / 1048576 + 0.5 }'
}

# Two decimals: the dex figure is the one place the documents give a fraction.
exact_mib() {
  awk -v bytes="$1" 'BEGIN { printf "%.2f", bytes / 1048576 }'
}

one_decimal_mib() {
  awk -v bytes="$1" 'BEGIN { printf "%.1f", bytes / 1048576 }'
}

# 5,679,760 rather than 5679760: the document groups the digits by hand because
# numfmt and printf %'d follow LC_NUMERIC, which a runner need not share.
group_digits() {
  awk -v n="$1" 'BEGIN {
    s = sprintf("%d", n); out = ""
    while (length(s) > 3) {
      out = "," substr(s, length(s) - 2) out
      s = substr(s, 1, length(s) - 3)
    }
    print s out
  }'
}

# The size cell of an APK's row in a Markdown table. Both tables put it in the
# fourth `|`-separated field — the research page because the release it describes
# is the second column, the manual because the architecture comes first — and the
# manual prefixes it with `~`.
md_size() {
  local file=$1 doc=$2
  strip_cr <"$doc" | awk -F'|' -v file="$file" '
    index($2, file) {
      value = $4
      gsub(/[ \t~]/, "", value)
      sub(/MB$/, "", value)
      print value
      exit
    }'
}

# The same cell in the HTML twin. The research row ends with it, so it is the last
# <td> before </tr>; the manual has a description column after it.
html_size() {
  local file=$1 doc=$2
  local cell
  cell=$(strip_cr <"$doc" | grep -F "<code>$file</code>" | head -1 || true)
  if [[ -z $cell ]]; then
    return 0
  fi
  case $doc in
    */RESEARCH.html) sed -E 's/.*<td>([0-9]+) MB<\/td><\/tr>.*/\1/' <<<"$cell" ;;
    */README.html) sed -E 's/.*<code>[^<]*<\/code><\/td><td>[^<]*<\/td><td>([0-9]+) MB<\/td>.*/\1/' <<<"$cell" ;;
  esac
}

problems=0
fail() {
  echo "FAIL: $*" >&2
  problems=$((problems + 1))
}

printf '%-28s %12s %5s  %-9s %-9s %-11s %-11s\n' \
  APK bytes MB README.md RSCH.md README.html RSCH.html
for variant in "${artifacts[@]}"; do
  apk="$apk_dir/app-$variant-release.apk"
  if [[ ! -f $apk ]]; then
    echo "missing $apk" >&2
    exit 1
  fi
  bytes=$(stat -c %s "$apk" 2>/dev/null || stat -f %z "$apk")
  mib=$(round_mib "$bytes")

  readme_value=$(md_size "app-$variant-release.apk" "$readme_md")
  research_value=$(md_size "app-$variant-release.apk" "$research_md")
  readme_html_value=$(html_size "app-$variant-release.apk" "$readme_html")
  research_html_value=$(html_size "app-$variant-release.apk" "$research_html")

  printf '%-28s %12s %5s  %-9s %-9s %-11s %-11s\n' "app-$variant-release.apk" \
    "$(group_digits "$bytes")" "$mib" "${readme_value:-?}" "${research_value:-?}" \
    "${readme_html_value:-?}" "${research_html_value:-?}"

  # Each document is checked separately so the message names the file to edit;
  # a pair that agrees with itself but not with the APK is the same bug.
  for pair in "README.md:$readme_value" "RESEARCH.md:$research_value" \
    "README.html:$readme_html_value" "RESEARCH.html:$research_html_value"; do
    doc=${pair%%:*}
    value=${pair#*:}
    if [[ -z $value ]]; then
      fail "docs/$doc has no size for app-$variant-release.apk — the row changed shape, so update this script with it"
    elif [[ $value != "$mib" ]]; then
      fail "docs/$doc says app-$variant-release.apk is $value MB; the APK measures $bytes bytes ($mib MB)"
    fi
  done
done

# The arm64 APK is the one the manual recommends and the one the research page's
# paragraphs about dex and native libraries describe.
arm64="$apk_dir/app-arm64-v8a-release.apk"
listing=$(unzip -l "$arm64" | strip_cr)

dex_count=$(awk '$4 ~ /^classes[0-9]*\.dex$/ { count++ } END { print count + 0 }' <<<"$listing")
dex_bytes=$(awk '$4 ~ /^classes[0-9]*\.dex$/ { total += $1 } END { print total + 0 }' <<<"$listing")
dex_mib=$(exact_mib "$dex_bytes")
lib_bytes=$(awk '$4 == "lib/arm64-v8a/libavcodec.so" { print $1; exit }' <<<"$listing")
if [[ -z $lib_bytes ]]; then
  echo "app-arm64-v8a-release.apk has no lib/arm64-v8a/libavcodec.so" >&2
  exit 1
fi
lib_mib=$(one_decimal_mib "$lib_bytes")

echo
printf 'arm64 dex: %s file(s), %s bytes (%s MB) | libavcodec.so: %s bytes (%s MB)\n' \
  "$dex_count" "$(group_digits "$dex_bytes")" "$dex_mib" "$(group_digits "$lib_bytes")" "$lib_mib"

# Whitespace is flattened first: the HTML paragraphs wrap mid-phrase, and the
# sentence is a claim, not a layout.
research_md_flat=$(strip_cr <"$research_md" | tr '\n' ' ')
research_html_flat=$(strip_cr <"$research_html" | tr '\n' ' ' | tr -s ' ')

doc_dex_md=$(grep -oE 'dex files? of [0-9]+\.[0-9]+ MB' <<<"$research_md_flat" | head -1 | sed -E 's/.*of ([0-9.]+) MB/\1/' || true)
doc_dex_html=$(grep -oE 'dex files? of [0-9]+\.[0-9]+ MB' <<<"$research_html_flat" | head -1 | sed -E 's/.*of ([0-9.]+) MB/\1/' || true)

for pair in "RESEARCH.md:$doc_dex_md" "RESEARCH.html:$doc_dex_html"; do
  doc=${pair%%:*}
  value=${pair#*:}
  if [[ -z $value ]]; then
    fail "docs/$doc no longer states a dex figure — update this script with the new sentence"
  elif [[ $value != "$dex_mib" ]]; then
    fail "docs/$doc says the dex is $value MB; the APK measures $dex_bytes bytes ($dex_mib MB)"
  fi
done
# "single" is a claim about the build, not wording: four dex files called one is
# exactly what R8 is supposed to have fixed.
if [[ $dex_count -ne 1 ]] && grep -q 'single dex file' <<<"$research_md_flat$research_html_flat"; then
  fail "docs/RESEARCH says the APK carries a single dex file; this one carries $dex_count"
fi

doc_lib_bytes=$(grep -oE '\([0-9][0-9,]* bytes, [0-9]+\.[0-9]+ MB\)' <<<"$research_md_flat" | head -1 | sed -E 's/\(([0-9,]+) bytes.*/\1/' || true)
doc_lib_mib=$(grep -oE '\([0-9][0-9,]* bytes, [0-9]+\.[0-9]+ MB\)' <<<"$research_md_flat" | head -1 | sed -E 's/.*, ([0-9.]+) MB\)/\1/' || true)
doc_lib_bytes_html=$(grep -oE '\([0-9][0-9,]* bytes, [0-9]+\.[0-9]+ MB\)' <<<"$research_html_flat" | head -1 | sed -E 's/\(([0-9,]+) bytes.*/\1/' || true)

if [[ -z $doc_lib_bytes || -z $doc_lib_mib ]]; then
  fail "docs/RESEARCH.md no longer states libavcodec.so's size in bytes — update this script with the new sentence"
else
  if [[ $doc_lib_bytes != "$(group_digits "$lib_bytes")" ]]; then
    fail "docs/RESEARCH.md says libavcodec.so is $doc_lib_bytes bytes; the APK measures $lib_bytes"
  fi
  if [[ $doc_lib_mib != "$lib_mib" ]]; then
    fail "docs/RESEARCH.md says libavcodec.so is $doc_lib_mib MB; $lib_bytes bytes is $lib_mib MB"
  fi
fi
if [[ -z $doc_lib_bytes_html ]]; then
  fail "docs/RESEARCH.html no longer states libavcodec.so's size in bytes — update this script with the new sentence"
elif [[ $doc_lib_bytes_html != "$(group_digits "$lib_bytes")" ]]; then
  fail "docs/RESEARCH.html says libavcodec.so is $doc_lib_bytes_html bytes; the APK measures $lib_bytes"
fi

if [[ $problems -ne 0 ]]; then
  echo "FAIL: $problems documented figure(s) disagree with the APKs in $apk_dir" >&2
  exit 1
fi
echo "all documented figures match the APKs"
