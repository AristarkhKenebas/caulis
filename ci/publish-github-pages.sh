#!/usr/bin/env bash
# Puts a release of Caulis into the Maven repository on GitHub Pages — a second home for
# releases, free and without a login for users (GitHub Packages wants a token even to read):
#
#   https://aristarkhkenebas.github.io/caulis/maven/
#
#   ci/publish-github-pages.sh 0.1.3
#   ci/publish-github-pages.sh [--force] [--from-tree] <version> [-- <more Gradle options>]
#
# Users add it after mavenCentral(); the coordinates are the same as on Central
# (io.github.aristarkhkenebas:caulis-*:<version>), so a version found on either works:
#
#   repositories {
#       mavenCentral()
#       maven("https://aristarkhkenebas.github.io/caulis/maven") {
#           content { includeGroup("io.github.aristarkhkenebas") }
#       }
#   }
#
# What it does:
#   1. builds the same files as for Maven Central (ci/publish-central.sh): jars, sources and
#      javadoc jars, POMs, Gradle module metadata, gpg signatures (.asc) and checksums — from
#      the tag v<version> (exported to build/pages-src, so changes not committed stay out),
#      or with --from-tree from this folder as it is (for a version without a tag);
#   2. merges them into a clone of the branch gh-pages in build/gh-pages (made by the script:
#      cloned from origin, or begun anew when origin has no such branch yet), under maven/,
#      keeping every older version; writes maven-metadata.xml of each module anew (versions,
#      latest, release, lastUpdated) with its checksums;
#   3. commits there (in build/gh-pages only — never in this repository) and prints the one
#      command that publishes it: git -C build/gh-pages push origin gh-pages. Nothing is pushed
#      by the script.
#
# Running it again for the same version changes nothing (the build is compared by content:
# the files in the jars, the POM, the module metadata without its hashes — not by bytes, as
# jars carry the time they were made). A different build of a version already there is
# refused: a version, once out, must stay what people downloaded. --force replaces it anyway
# (only for a version nobody can have used yet).
#
# Once, on GitHub: Settings → Pages → Build and deployment → Source: "Deploy from a branch",
# Branch: gh-pages, folder: / (root) → Save. (The API documentation then goes to the same
# branch, under api/: .github/workflows/docs.yml.) After a push, GitHub needs a minute or two.
#
# Needs: git, gpg with the release key (gradle.properties: signing.gnupg.keyName; gpg's agent
# asks for its password), unzip, md5sum/sha1sum/sha256sum/sha512sum.
# For trying it out: CAULIS_PAGES_DIR (instead of build/gh-pages) and CAULIS_PAGES_REMOTE
# (instead of origin's URL); Gradle options after "--", e.g. another key:
#   -- -Psigning.gnupg.keyName=<id> -Psigning.gnupg.homeDir=<dir>
set -euo pipefail

force=false
from_tree=false
version=
gradle_extra=()
while [[ $# -gt 0 ]]; do
    case "$1" in
        --force) force=true ;;
        --from-tree) from_tree=true ;;
        --) shift; gradle_extra=("$@"); break ;;
        -*) echo "Unknown option $1" >&2; exit 2 ;;
        *) [[ -z "$version" ]] || { echo "One version only" >&2; exit 2; }; version=$1 ;;
    esac
    shift
done
if [[ -z "$version" ]]; then
    echo "usage: ci/publish-github-pages.sh [--force] [--from-tree] <version> [-- <Gradle options>]" >&2
    exit 2
fi
case "$version" in
    *-SNAPSHOT) echo "A release has no -SNAPSHOT: $version" >&2; exit 2 ;;
    *[!A-Za-z0-9._-]*) echo "Not a version: $version" >&2; exit 2 ;;
esac

cd "$(dirname "$0")/.."
root=$(pwd)
group_path=io/github/aristarkhkenebas
pages=${CAULIS_PAGES_DIR:-$root/build/gh-pages}
remote=${CAULIS_PAGES_REMOTE:-$(git remote get-url origin)}
branch=gh-pages

# --- 1. The build -------------------------------------------------------------------------
if $from_tree; then
    src=$root
    if [[ -n "$(git status --porcelain --untracked-files=no)" ]]; then
        echo "Note: building from this folder with changes not committed (--from-tree)." >&2
    fi
else
    if ! git rev-parse -q --verify "refs/tags/v$version^{commit}" >/dev/null; then
        echo "There is no tag v$version. Tag the release first, or build from this folder as it is: --from-tree" >&2
        exit 1
    fi
    src=$root/build/pages-src
    echo "== Exporting the sources of v$version to build/pages-src"
    rm -rf "$src"
    mkdir -p "$src"
    git archive "v$version" | tar -x -C "$src"
fi

echo "== Building and signing $version"
rm -rf "$src/build/staging-deploy"
(cd "$src" && ./gradlew --console=plain publishAllPublicationsToStagingRepository \
    -Pcaulis.version="$version" ${gradle_extra[@]+"${gradle_extra[@]}"})
staging=$src/build/staging-deploy

modules=()
for dir in "$staging/$group_path"/*/"$version"; do
    [[ -d "$dir" ]] || continue
    module=$(basename "$(dirname "$dir")")
    for kind in pom module jar; do
        file="$dir/$module-$version.$kind"
        [[ -f "$file" ]] || { echo "$module: $(basename "$file") is missing" >&2; exit 1; }
        for extra in asc md5 sha1; do
            [[ -f "$file.$extra" ]] || { echo "$module: $(basename "$file").$extra is missing (not signed?)" >&2; exit 1; }
        done
    done
    modules+=("$module")
done
if [[ ${#modules[@]} -eq 0 ]]; then
    echo "The build made no module of version $version in $staging" >&2
    exit 1
fi
echo "Built: ${modules[*]}"

# --- 2. The branch gh-pages ---------------------------------------------------------------
if [[ -d "$pages/.git" ]]; then
    echo "== Updating $pages from $remote"
    if [[ -n "$(git -C "$pages" status --porcelain)" ]]; then
        echo "$pages has changes not committed; commit or remove them first." >&2
        exit 1
    fi
    git -C "$pages" remote set-url origin "$remote"
    if git -C "$pages" fetch -q origin "+refs/heads/$branch:refs/remotes/origin/$branch" 2>/dev/null; then
        git -C "$pages" merge -q --ff-only "origin/$branch" || {
            echo "$pages and $remote's $branch went apart: put them together by hand (git -C $pages pull --rebase)." >&2
            exit 1
        }
    fi
elif git ls-remote --exit-code --heads "$remote" "$branch" >/dev/null 2>&1; then
    echo "== Cloning $branch of $remote into $pages"
    rm -rf "$pages"
    git clone -q --branch "$branch" --single-branch "$remote" "$pages"
else
    echo "== $remote has no $branch yet: beginning it in $pages"
    rm -rf "$pages"
    git init -q "$pages"
    git -C "$pages" checkout -q --orphan "$branch"
    git -C "$pages" remote add origin "$remote"
fi
repo=$pages/maven

# What a build of a module version is, apart from when it was made and its signatures: the
# files in each jar (size, CRC) and the POM and module metadata (without the jars' hashes).
fingerprint() {
    local dir=$1 file
    for file in "$dir"/*; do
        case "$file" in
            *.asc | *.md5 | *.sha1 | *.sha256 | *.sha512) continue ;;
        esac
        echo "== $(basename "$file")"
        case "$file" in
            *.jar) unzip -v "$file" | awk 'NF >= 8 && $7 ~ /^[0-9a-f]{8}$/ { print $1, $7, $8 }' | sort ;;
            *.module) grep -v -E '"(size|sha512|sha256|sha1|md5|buildId)"' "$file" ;;
            *) cat "$file" ;;
        esac
    done | sha256sum | cut -d' ' -f1
}

# First every check, then the changes: a refused version leaves the branch as it was.
new=()
replace=()
for module in "${modules[@]}"; do
    there=$repo/$group_path/$module/$version
    if [[ ! -d "$there" ]]; then
        new+=("$module")
    elif [[ "$(fingerprint "$there")" == "$(fingerprint "$staging/$group_path/$module/$version")" ]]; then
        echo "$module $version: already there, the same build (kept)"
    elif $force; then
        echo "$module $version: already there, a different build: replaced (--force)"
        replace+=("$module")
    else
        echo "$module $version is published already, from a different build. People may have it" >&2
        echo "  (and Gradle caches it for good): release a new version instead. To replace it" >&2
        echo "  anyway (only if nobody can have used it yet): --force" >&2
        refused=true
    fi
done
[[ -z "${refused:-}" ]] || exit 1

for module in ${new[@]+"${new[@]}"} ${replace[@]+"${replace[@]}"}; do
    there=$repo/$group_path/$module/$version
    rm -rf "$there"
    mkdir -p "$there"
    cp "$staging/$group_path/$module/$version"/* "$there/"
    echo "$module $version: copied in"
done

checksums() {
    local file=$1
    md5sum "$file" | cut -d' ' -f1 > "$file.md5"
    sha1sum "$file" | cut -d' ' -f1 > "$file.sha1"
    sha256sum "$file" | cut -d' ' -f1 > "$file.sha256"
    sha512sum "$file" | cut -d' ' -f1 > "$file.sha512"
}

# maven-metadata.xml of each module: every version in the repository (a folder with its POM).
# Written anew only when its versions changed, so that running again changes nothing.
now=$(date -u +%Y%m%d%H%M%S)
for moduledir in "$repo/$group_path"/*/; do
    module=$(basename "$moduledir")
    versions=$(for pom in "$moduledir"*/"$module"-*.pom; do
        v=$(basename "$(dirname "$pom")")
        if [[ "$(basename "$pom")" == "$module-$v.pom" ]]; then echo "$v"; fi
    done | sort -V)
    [[ -n "$versions" ]] || continue
    latest=$(tail -n1 <<<"$versions")
    release=$(grep -v -- '-SNAPSHOT$' <<<"$versions" | tail -n1 || true)
    group=${group_path//\//.}
    body="  <groupId>$group</groupId>
  <artifactId>$module</artifactId>
  <versioning>
    <latest>$latest</latest>
    <release>$release</release>
    <versions>
$(sed 's|.*|      <version>&</version>|' <<<"$versions")
    </versions>"
    metadata=$moduledir/maven-metadata.xml
    if [[ -f "$metadata" ]] && [[ "$(sed '/<lastUpdated>/,$d' "$metadata" | tail -n +3)" == "$body" ]]; then
        continue
    fi
    printf '%s\n' '<?xml version="1.0" encoding="UTF-8"?>' '<metadata>' "$body" \
        "    <lastUpdated>$now</lastUpdated>" '  </versioning>' '</metadata>' > "$metadata"
    checksums "$metadata"
    echo "$module: maven-metadata.xml written ($(tr '\n' ' ' <<<"$versions"))"
done

# GitHub Pages: files as they are (no Jekyll), and a page saying what this is.
touch "$pages/.nojekyll"
if [[ ! -f "$pages/index.html" ]]; then
    printf '%s\n' '<!DOCTYPE html>' '<html><head><meta charset="utf-8"><title>Caulis</title></head><body>' \
        '<h1>Caulis</h1>' '<p><a href="maven/">Maven repository</a> · <a href="api/">API documentation</a> ·' \
        '<a href="https://github.com/AristarkhKenebas/caulis">sources</a></p>' '</body></html>' > "$pages/index.html"
fi
{
    echo '<!DOCTYPE html>'
    echo '<html><head><meta charset="utf-8"><title>Caulis Maven repository</title></head><body>'
    echo '<h1>Caulis Maven repository</h1>'
    echo '<p>Releases of <a href="https://github.com/AristarkhKenebas/caulis">Caulis</a>, the same as on Maven Central'
    echo '(same coordinates, signed with the same key). In Gradle:</p>'
    echo '<pre>repositories {'
    echo '    mavenCentral()'
    echo '    maven("https://aristarkhkenebas.github.io/caulis/maven") {'
    echo '        content { includeGroup("io.github.aristarkhkenebas") }'
    echo '    }'
    echo '}</pre>'
    echo '<ul>'
    for moduledir in "$repo/$group_path"/*/; do
        module=$(basename "$moduledir")
        [[ -f "$moduledir/maven-metadata.xml" ]] || continue
        echo "<li>io.github.aristarkhkenebas:$module: $(sed -n 's|.*<version>\(.*\)</version>.*|\1|p' "$moduledir/maven-metadata.xml" | tr '\n' ' ')</li>"
    done
    echo '</ul></body></html>'
} > "$repo/index.html"

# --- 3. Commit (here only), and say how to publish -----------------------------------------
git -C "$pages" add -A
if git -C "$pages" diff --cached --quiet; then
    echo "== Nothing new: $version is in $pages already, as built now."
else
    git -C "$pages" commit -q -m "Caulis $version"
    echo "== Committed in $pages: $(git -C "$pages" log -1 --format='%h %s')"
fi
if git -C "$pages" rev-parse -q --verify "refs/remotes/origin/$branch" >/dev/null &&
    [[ -z "$(git -C "$pages" log --oneline "origin/$branch..HEAD")" ]]; then
    echo "Everything is pushed already."
else
    echo
    echo "To publish it (https://aristarkhkenebas.github.io/caulis/maven/), run:"
    echo "  git -C $pages push origin $branch"
fi
