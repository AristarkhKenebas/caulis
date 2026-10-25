#!/bin/sh
# Uploads a release of Caulis to Maven Central (the Central Portal, central.sonatype.com).
#
#   ci/publish-central.sh 0.1.0
#
# Needs, once:
#   - the namespace dev.caulis verified on central.sonatype.com (a DNS TXT record on caulis.dev);
#   - a GPG key (gpg --full-generate-key), its public part on a key server
#     (gpg --keyserver keyserver.ubuntu.com --send-keys <id>); Gradle signs with gpg's agent;
#   - a user token from central.sonatype.com (Account → Generate User Token), given as
#     CENTRAL_TOKEN=$(printf '%s:%s' "<username>" "<password>" | base64 -w0);
#   - caulis.website, caulis.scm, caulis.developer.id and .name set in gradle.properties.
#
# The upload is checked by Central and waits there: it goes out only when you press "Publish"
# on central.sonatype.com (Deployments). Nothing is published by this script alone.
set -eu

version=${1:?"usage: ci/publish-central.sh <version>, e.g. 0.1.0"}
case "$version" in *-SNAPSHOT) echo "A release has no -SNAPSHOT: $version" >&2; exit 2 ;; esac
: "${CENTRAL_TOKEN:?set CENTRAL_TOKEN (see the top of this script)}"

cd "$(dirname "$0")/.."
rm -rf build/staging-deploy build/central-bundle.zip
./gradlew publishAllPublicationsToStagingRepository -Pcaulis.version="$version"
(cd build/staging-deploy && zip -qr ../central-bundle.zip .)
echo "Uploading build/central-bundle.zip ($(du -h build/central-bundle.zip | cut -f1))..."
curl --fail-with-body \
    -H "Authorization: Bearer $CENTRAL_TOKEN" \
    -F "bundle=@build/central-bundle.zip" \
    "https://central.sonatype.com/api/v1/publisher/upload?publishingType=USER_MANAGED&name=caulis-$version"
echo
echo "Uploaded. Check and publish it on https://central.sonatype.com/publishing/deployments"
