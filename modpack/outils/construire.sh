#!/usr/bin/env bash
# Assemble les deux zips du modpack a partir de modpack/client et modpack/serveur.
# Prerequis : un JDK (outil `jar`) dans le PATH ou JAVA_HOME, les jars deja copies dans
# client/overrides/mods et serveur/mods (voir modpack/README.md).
set -euo pipefail
cd "$(dirname "$0")/.."
JAR="${JAVA_HOME:+$JAVA_HOME/bin/}jar"
SORTIE="${1:-build}"
mkdir -p "$SORTIE"
rm -f "$SORTIE/citrouille.zip" "$SORTIE/citrouille-serveur.zip"
"$JAR" --create --no-manifest --file "$SORTIE/citrouille.zip" -C client .
"$JAR" --create --no-manifest --file "$SORTIE/citrouille-serveur.zip" -C serveur .
sha256sum "$SORTIE"/*.zip
