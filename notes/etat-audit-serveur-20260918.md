# État — audit perf serveur (Loterie), session interrompue le 2026-09-18

Rédigé par le coordinateur, pas par l'agent lui-même : il a été arrêté avant d'avoir pu
écrire son propre bilan (tué en pleine relance du banc `Billet` pour vérifier un correctif).
Ce qui suit vient de la lecture directe de son diff non commité, pas d'un rapport de sa part.

## Fait (non commité — reste tel quel sur disque)

Chantier identifié : le tirage aléatoire de bloc par section (`ServerLevel.tickChunk`,
`randomTickSpeed` tirages par section à chaque tick) coûte une lecture de palette même
quand la section n'a presque aucun bloc/fluide qui réagit réellement — technique connue de
Lithium, réimplémentée ici en vérifiant `LevelChunkSection` par bytecode (`javap` sur le
jar patché 26.3) plutôt que copiée.

Fichiers non commités, tous à l'état "en cours" :
- `src/main/java/fr/clubcitrouille/lanterne/core/Loterie.java` (nouveau) — le module.
- `src/main/java/fr/clubcitrouille/lanterne/lab/Billet.java` (nouveau) — banc de mesure
  dédié ("Billet" = le tirage, cohérent avec "Loterie").
- `src/main/java/fr/clubcitrouille/lanterne/mixin/LoterieMixin.java` (nouveau)
- `src/main/java/fr/clubcitrouille/lanterne/mixin/LoterieSectionMixin.java` (nouveau)
- `Lanterne.java`, `core/Config.java`, `core/Settings.java`, `report/SelfTest.java`,
  `lanterne.mixins.json` — modifiés pour brancher le module.

## Mesuré

Rien de fiable capturé avant l'arrêt — le dernier message de l'agent juste avant d'être
tué : *"Now let's compile and re-run the Billet bench to see whether this fix at least
stops the regression."* Il avait donc trouvé une régression de comportement (probablement :
des blocs/fluides qui devraient ticker et ne le font plus, ou l'inverse) et tentait de la
corriger — état non vérifié.

`./gradlew compileJava` tenté juste après l'arrêt par le coordinateur : a échoué, mais sur
une erreur d'infrastructure Gradle (`Could not connect to server ... Gradle Worker Daemon`,
23 daemons périmés non réutilisables) — PAS une erreur de compilation Java. Donc l'état
réel de correction du code (compile ou pas) reste inconnu tant qu'un Gradle propre n'a pas
retenté.

## Reste à faire

1. Relancer `./gradlew compileJava` proprement (probablement après `./gradlew --stop` pour
   nettoyer les daemons périmés) pour savoir si le code compile déjà.
2. Si ça compile, retrouver la régression que l'agent corrigeait — comparer le comportement
   de tick aléatoire avec/sans `Loterie` sur une section simple et bien connue (un seul bloc
   qui coche parmi beaucoup de pierre, par exemple) pour confirmer que les bons blocs tickent
   toujours et qu'aucun n'est ni oublié ni tické en trop.
3. Une fois correct, faire tourner le banc `Billet` pour le vrai chiffre avant/après.
4. Ne PAS committer ce code tel quel sans l'avoir vérifié — c'était explicitement à mi-chemin
   d'un correctif de régression au moment de l'arrêt.

Rien n'est perdu : tout est sur disque, non commité, `git status` le montre clairement.
