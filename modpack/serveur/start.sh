#!/usr/bin/env bash
# === Club Citrouille - demarrage du serveur (Linux / macOS / Git Bash) ===
# Installe NeoForge au premier lancement, puis redemarre le serveur s'il plante (borne a 5 essais).
set -u
cd "$(dirname "$0")"
NEOFORGE="26.3.0.7-beta"
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) ARGS_FILE="win_args.txt" ;; *) ARGS_FILE="unix_args.txt" ;; esac
ARGS="libraries/net/neoforged/neoforge/$NEOFORGE/$ARGS_FILE"
MAX_CRASH=5

if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then PATH="$JAVA_HOME/bin:$PATH"; fi
command -v java >/dev/null 2>&1 || { echo "[ERREUR] Java introuvable : installe Java 25 (https://adoptium.net)." >&2; exit 1; }

if [ ! -f "$ARGS" ]; then
  echo "[INSTALL] NeoForge $NEOFORGE absent : installation..."
  INST="neoforge-$NEOFORGE-installer.jar"
  if [ ! -f "$INST" ]; then
    URL="https://maven.neoforged.net/releases/net/neoforged/neoforge/$NEOFORGE/$INST"
    if command -v curl >/dev/null 2>&1; then curl -fL -o "$INST" "$URL"; else wget -O "$INST" "$URL"; fi \
      || { echo "[ERREUR] Telechargement impossible." >&2; exit 1; }
  fi
  java -jar "$INST" --installServer || { echo "[ERREUR] Installation de NeoForge echouee." >&2; exit 1; }
fi

if ! grep -q '^eula=true' eula.txt 2>/dev/null; then
  echo "Ce serveur necessite d'accepter le CLUF de Minecraft : https://aka.ms/MinecraftEULA"
  if [ "${ACCEPT_EULA:-}" = "true" ]; then rep=OUI; else read -r -p "Tape OUI pour accepter le CLUF : " rep; fi
  [ "${rep:-}" = "OUI" ] || { echo "CLUF non accepte, arret."; exit 1; }
  echo "eula=true" > eula.txt
fi

crashes=0
while :; do
  java @user_jvm_args.txt "@$ARGS" nogui
  code=$?
  if [ "$code" -eq 0 ]; then echo "[OK] Serveur arrete proprement."; exit 0; fi
  crashes=$((crashes + 1))
  echo "[ATTENTION] Le serveur s'est arrete avec le code $code (incident $crashes/$MAX_CRASH)."
  if [ "$crashes" -ge "$MAX_CRASH" ]; then
    echo "[ERREUR] Trop d'incidents, abandon. Voir logs/latest.log et crash-reports/" >&2
    exit "$code"
  fi
  echo "Redemarrage dans 10 secondes... (Ctrl+C pour annuler)"
  sleep 10
done
