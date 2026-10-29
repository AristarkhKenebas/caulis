#!/usr/bin/env bash
# Uploads a release of Caulis to Maven Central (the Central Portal, central.sonatype.com).
#
#   ci/publish-central.sh 0.1.0
#   ci/publish-central.sh [--dry-run] [--yes] [--only caulis-wm,caulis-core] [--from-pages] <version>
#
# MIND THE MONTHLY LIMITS. Since 2026-10-01 Central's free tier allows, per organization and
# calendar month, about 80 MB, 1,000 files and 7 releases (numbers as reported; check
# central.sonatype.com). One Caulis release is ~15 modules × (jar, sources, javadoc, POM,
# module metadata, each with .asc, .md5, .sha1) ≈ 300 files and ~9 MB. So:
#   - every release goes to the GitHub Pages repository first (ci/publish-github-pages.sh):
#     free, no limits that matter here, and users find it there at once;
#   - Central gets a release when the month's limits allow; --dry-run shows what it would cost;
#   - when a month's limit is used up, wait for the next month, then upload the very files of
#     the Pages repository (--from-pages): byte for byte what people already have.
# The bundle holds only what Central needs: no maven-metadata.xml (Central makes its own) and
# no .sha256/.sha512 (Central asks for .md5 and .sha1 only) — about 300 files instead of 560.
#
# Options:
#   --dry-run      build and show the bundle (files, size, against the limits); upload nothing
#   --yes          do not ask before uploading
#   --only a,b     send only these modules (e.g. to finish a release whose other modules are on
#                  Central already). Refused when a module sent needs a Caulis module of this
#                  version that is neither sent nor on Central: users could not resolve it.
#                  (Every module carries the release's version, and each needs caulis-log,
#                  caulis-native, ...: leaving out "unchanged" modules is not possible while
#                  all modules share one version.)
#   --from-pages   take the files from the Pages repository (build/gh-pages/maven, or
#                  CAULIS_PAGES_DIR/maven) instead of building them again
#
# Needs, once:
#   - the namespace io.github.aristarkhkenebas verified on central.sonatype.com (Namespaces:
#     signed in with GitHub it is verified already; else Central names a public repository to
#     create on that GitHub account, for a moment, as the proof);
#   - a GPG key (gpg --full-generate-key), its public part on a key server
#     (gpg --keyserver keyserver.ubuntu.com --send-keys <id>); Gradle signs with gpg's agent;
#   - a user token from central.sonatype.com (Account → Generate User Token), given as
#     CENTRAL_TOKEN=$(printf '%s:%s' "<username>" "<password>" | base64 -w0);
#   - caulis.website, caulis.scm, caulis.developer.id and .name in gradle.properties (set).
# The limits it warns about: CENTRAL_LIMIT_MB (80), CENTRAL_LIMIT_FILES (1000),
# CENTRAL_LIMIT_RELEASES (7).
#
# The upload is checked by Central and waits there: it goes out only when you press "Publish"
# on central.sonatype.com (Deployments). Nothing is published by this script alone.
set -euo pipefail

dry_run=false
yes=false
only=
from_pages=false
version=
while [[ $# -gt 0 ]]; do
    case "$1" in
        --dry-run) dry_run=true ;;
        --yes) yes=true ;;
        --only) only=${2:?"--only needs a list, e.g. --only caulis-wm,caulis-core"}; shift ;;
        --only=*) only=${1#--only=} ;;
        --from-pages) from_pages=true ;;
        -*) echo "Unknown option $1" >&2; exit 2 ;;
        *) [[ -z "$version" ]] || { echo "One version only" >&2; exit 2; }; version=$1 ;;
    esac
    shift
done
[[ -n "$version" ]] || { echo "usage: ci/publish-central.sh [--dry-run] [--yes] [--only a,b] [--from-pages] <version>" >&2; exit 2; }
case "$version" in *-SNAPSHOT) echo "A release has no -SNAPSHOT: $version" >&2; exit 2 ;; esac
$dry_run || : "${CENTRAL_TOKEN:?set CENTRAL_TOKEN (see the top of this script)}"
limit_mb=${CENTRAL_LIMIT_MB:-80}
limit_files=${CENTRAL_LIMIT_FILES:-1000}
limit_releases=${CENTRAL_LIMIT_RELEASES:-7}

cd "$(dirname "$0")/.."
group_path=io/github/aristarkhkenebas
staging=build/staging-deploy
rm -rf "$staging" build/central-bundle.zip
if $from_pages; then
    pages_repo=${CAULIS_PAGES_DIR:-build/gh-pages}/maven
    found=false
    for dir in "$pages_repo/$group_path"/*/"$version"; do
        [[ -d "$dir" ]] || continue
        module=$(basename "$(dirname "$dir")")
        mkdir -p "$staging/$group_path/$module"
        cp -r "$dir" "$staging/$group_path/$module/"
        found=true
    done
    $found || { echo "$pages_repo has no module of version $version" >&2; exit 1; }
else
    ./gradlew publishAllPublicationsToStagingRepository -Pcaulis.version="$version"
fi

on_central() { # module version
    curl -sfI "https://repo1.maven.org/maven2/$group_path/$1/$2/$1-$2.pom" >/dev/null
}

# Only what Central needs.
find "$staging" \( -name 'maven-metadata.xml*' -o -name '*.sha256' -o -name '*.sha512' \) -delete

for dir in "$staging/$group_path"/*/*/; do
    module=$(basename "$(dirname "$dir")")
    v=$(basename "$dir")
    if [[ -n "$only" && ",$only," != *",$module,"* ]]; then
        echo "$module $v: not in --only: left out"
        rm -rf "$dir"
    # A module version Central has already (one not released anew) is not sent again.
    elif on_central "$module" "$v"; then
        echo "$module $v is on Maven Central already: left out"
        rm -rf "$dir"
    fi
done
find "$staging" -mindepth 1 -type d -empty -delete
sent=$( (find "$staging/$group_path" -mindepth 2 -maxdepth 2 -type d 2>/dev/null || true) | wc -l)
[[ "$sent" -gt 0 ]] || { echo "Nothing to upload: every module of $version is on Central already (or left out)." >&2; exit 0; }
if [[ -n "$only" ]]; then
    for name in ${only//,/ }; do
        [[ -d "$staging/$group_path/$name/$version" ]] || on_central "$name" "$version" ||
            { echo "--only names $name, but the build has no $name $version" >&2; exit 1; }
    done
fi

# Every Caulis module a module sent needs must be sent too, or be on Central already.
missing=()
for pom in "$staging/$group_path"/*/*/*.pom; do
    while read -r artifact dep_version; do
        [[ -d "$staging/$group_path/$artifact/$dep_version" ]] && continue
        on_central "$artifact" "$dep_version" && continue
        missing+=("$(basename "$pom" .pom) needs $artifact $dep_version")
    done < <(awk '
        /<dependency>/ { g = a = v = "" }
        /<groupId>/ { g = $0; gsub(/.*<groupId>|<\/groupId>.*/, "", g) }
        /<artifactId>/ { a = $0; gsub(/.*<artifactId>|<\/artifactId>.*/, "", a) }
        /<version>/ { v = $0; gsub(/.*<version>|<\/version>.*/, "", v) }
        /<\/dependency>/ && g == "io.github.aristarkhkenebas" { print a, v }
    ' "$pom" | sort -u)
done
if [[ ${#missing[@]} -gt 0 ]]; then
    echo "Refused: users could not resolve what is sent, as these are neither sent nor on Central:" >&2
    printf '  %s\n' "${missing[@]}" | sort -u >&2
    exit 1
fi

(cd "$staging" && zip -qr ../central-bundle.zip .)
files=$(unzip -Z1 build/central-bundle.zip | grep -vc '/$' || true)
bytes=$(stat -c %s build/central-bundle.zip)
unpacked=$(du -sb "$staging" | cut -f1)
mb() { awk -v b="$1" 'BEGIN { printf "%.1f MB", b / 1000000 }'; }
echo
echo "Bundle build/central-bundle.zip: $sent module(s), $files files, $(mb "$bytes") ($(mb "$unpacked") unpacked)."
echo "Central's free limits per month (as reported): ~$limit_mb MB, $limit_files files, $limit_releases releases —"
echo "this upload is $((files * 100 / limit_files))% of the files and $((unpacked / 10000 / limit_mb))% of the size, and one release."
echo "What this month used already: central.sonatype.com → Deployments (and the Usage page)."
if [[ "$files" -gt "$limit_files" || "$unpacked" -gt $((limit_mb * 1000000)) ]]; then
    echo "WARNING: this alone is over a monthly limit; Central will refuse it. Use --only to split it." >&2
fi
if $dry_run; then
    echo "(--dry-run: nothing uploaded)"
    exit 0
fi
if ! $yes && [[ -t 0 ]]; then
    read -r -p "Upload it to Central? [y/N] " answer
    [[ "$answer" == [yY]* ]] || { echo "Not uploaded."; exit 1; }
fi

echo "Uploading build/central-bundle.zip..."
curl --fail-with-body \
    -H "Authorization: Bearer $CENTRAL_TOKEN" \
    -F "bundle=@build/central-bundle.zip" \
    "https://central.sonatype.com/api/v1/publisher/upload?publishingType=USER_MANAGED&name=caulis-$version"
echo
echo "Uploaded. Check and publish it on https://central.sonatype.com/publishing/deployments"
