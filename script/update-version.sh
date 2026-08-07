#!/bin/bash

set -euo pipefail

if [[ $# -ne 1 || -z "$1" ]]; then
    echo "Usage: $0 <exact-version>" >&2
    exit 2
fi

DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
MAVEN_VERSION="$1"
if [[ "${MAVEN_VERSION}" =~ ^([0-9]+\.[0-9]+\.[0-9]+)$ ]]; then
    OSGI_VERSION="${BASH_REMATCH[1]}"
elif [[ "${MAVEN_VERSION}" =~ ^([0-9]+\.[0-9]+\.[0-9]+)-SNAPSHOT$ ]]; then
    OSGI_VERSION="${BASH_REMATCH[1]}.qualifier"
elif [[ "${MAVEN_VERSION}" =~ ^([0-9]+\.[0-9]+\.[0-9]+)-([A-Za-z0-9_]+)$ ]]; then
    VERSION_BASE="${BASH_REMATCH[1]}"
    VERSION_QUALIFIER="${BASH_REMATCH[2]}"
    if [[ "${VERSION_QUALIFIER}" =~ ^[Qq][Uu][Aa][Ll][Ii][Ff][Ii][Ee][Rr]$ ]]; then
        echo "Literal qualifier is reserved; use X.Y.Z-SNAPSHOT" >&2
        exit 2
    fi
    OSGI_VERSION="${VERSION_BASE}.${VERSION_QUALIFIER}"
else
    echo "Version must be X.Y.Z, X.Y.Z-SNAPSHOT or X.Y.Z-qualifier" >&2
    exit 2
fi

cd "${DIR}/../"
mvn org.eclipse.tycho:tycho-versions-plugin:4.0.13:set-version \
    -Dartifacts=\
ru.taximaxim.codekeeper.rcp.product,\
ru.taximaxim.codekeeper.ui,\
ru.taximaxim.codekeeper.mainapp,\
ru.taximaxim.codekeeper.feature,\
ru.taximaxim.codekeeper.updatesite \
    "-DnewVersion=${OSGI_VERSION}"

# Маркетинговая Maven-версия корня сохраняет дефис; OSGi-модули используют
# эквивалентный qualifier через точку.
mvn org.eclipse.tycho:tycho-versions-plugin:4.0.13:set-property \
    -Dartifacts=ru.taximaxim.codeKeeper \
    -Dproperties=revision \
    "-DnewRevision=${MAVEN_VERSION}"
