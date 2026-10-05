#!/usr/bin/env bash
# Generates the HTML API documentation of the grid module into docs/api, using the JDK's javadoc tool.
# Run from anywhere: bash docs/generate-api-docs.sh
set -e
cd "$(dirname "$0")/.."

# The JavaFX jars for this platform, as Maven resolves them.
classpath_file="$(mktemp)"
mvn -q -o -pl grid dependency:build-classpath -Dmdep.outputFile="$classpath_file" -Dmdep.includeScope=compile
module_path="$(cat "$classpath_file")"
rm -f "$classpath_file"

source_path="$(pwd)/grid/src/main/java"
if command -v cygpath > /dev/null; then
    source_path="$(cygpath -m "$source_path")"
fi

rm -rf docs/api
javadoc -J-Duser.language=en --javafx \
    -d docs/api \
    --module-path "$module_path" \
    --module-source-path "de.schladebeck.fx.grid=$source_path" \
    --module de.schladebeck.fx.grid \
    -Xdoclint:all,-missing \
    -encoding UTF-8 -docencoding UTF-8 -charset UTF-8 \
    -windowtitle "FX Grid 0.1.0" -doctitle "FX Grid 0.1.0 API" \
    -quiet
echo "API documentation written to docs/api/index.html"
