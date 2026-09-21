# Modpack Club Citrouille

- `client/` : sources du zip CurseForge (manifest.json, modlist.html, overrides/ avec config, servers.dat, LISEZMOI).
  Le jar `client/overrides/mods/lanterne-*.jar` n'est PAS versionne : copier le jar deploye sur le VPS
  (meme SHA-256 que `/srv/minecraft/mods/lanterne-*.jar`), voir `.gitignore`.
- `serveur/` : sources du pack serveur (en cours).
- `outils/GenererServersDat.java` : ecrit/relit `servers.dat` (`java GenererServersDat.java sortie.dat "Nom" ip:port`, `--lire fichier`).
- `outils/construire.sh` : assemble `build/citrouille.zip` et `build/citrouille-serveur.zip` avec l'outil `jar` du JDK.
