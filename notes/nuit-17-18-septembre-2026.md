# Nuit du 17 au 18 septembre 2026 — état des lieux et suite

## Fait et vérifié cette nuit (par moi-même, pas juste rapporté par un fork)

- **ZGC générationnel déployé en production** sur la VM Oracle (remplace G1 + AlwaysPreTouch).
  Pause max mesurée 156 ms → 0,016 ms. RAM réelle au repos 17 Go → 2,03 Go (`VmRSS`
  vérifié directement via `/proc/<pid>/status`). Confirmé stable dans `[CREUSET]`.
- **Bug de concurrence réel corrigé** : `Hoard.java`/`Wheel.java` mutaient des
  `HashMap`/`HashSet` depuis un thread de fond sans repasser par `Minecraft.execute`.
- **Pregen** ne s'arrête plus quand le serveur se met en pause faute de joueurs
  (mixin sur la pause auto vanilla) ; logs de progression resserrés à 1×/s.
- **Panel** : TLS réel (`141-253-100-132.sslip.io`, Let's Encrypt, vérifié sans `-k`),
  responsive mobile corrigé (sidebar qui écrasait tout sous 768px), alertes Discord
  câblées mais mises en pause à la demande (`DISCORD_WEBHOOK_URL` vide = watcher inactif).
- **Gestion des joueurs façon Aternos** (panel) : kick/tuer/téléporter, mode de jeu,
  liste blanche/ban/opérateur, statistiques détaillées (temps de jeu, KDR, blocs cassés,
  objets utilisés, entités tuées — lues depuis `world/players/stats/<uuid>.json`, chemin
  propre à cette version du jeu, pas le `world/stats/` vanilla classique). Testé en
  conditions réelles via un compte temporaire créé puis supprimé proprement. Commité et
  déployé.

## En cours (deux forks repris ce matin après un redémarrage de session cette nuit)

Les deux avaient laissé du travail non commité sur des fichiers disjoints (vérifié avant
de les relancer, aucun conflit réel) :

1. **Module clear-lag (`Sweeper`/"Balai") + fix Enderman/feuilles** — `gradle.properties`
   (26.3.14, déjà buildé et tourné cette nuit), `core/Config.java` (`BROOM` désactivé par
   défaut, confirmé), `core/Sweeper.java`, tag `enderman_does_not_teleport_to.json`.
   Semblait proche de la fin (jar 26.3.14 déjà vu tourner dans les logs).
2. **Carte 3D custom façon BlueMap** — `tools/ExtractColors.java` + `block_colors.json`
   (extraction des couleurs de blocs depuis les textures), `tools/testworld/` (sondage du
   format `.mca`). Stade exploratoire, encore du chemin.

## Anomalie de cette nuit, expliquée

À 23:24, `minecraft.service` a échoué une fois (`ConnectionRefusedError` dans `stop.py`,
`Failed with result 'exit-code'`, redémarrage auto par systemd). Isolé, non reproduit
depuis (`NRestarts=0` actuellement). Cause la plus probable : un `stop` envoyé cru par
RCON pendant un test plutôt que `systemctl restart minecraft` — le script d'arrêt du
service a alors trouvé le port RCON déjà fermé. Demandé confirmation au fork concerné.

## Backlog, dans l'ordre où je compte l'attaquer

1. Finir les deux forks ci-dessus (clear-lag+Enderman, carte 3D).
2. **Chantier FPS client+serveur** — occlusion culling, greedy meshing, LOD entités/
   particules, moteur de lumière façon Starlight. Voir `notes/PLAN.md` (le plan
   d'absorption des 202 mods déjà trié) pour ne pas repartir de zéro sur les cibles
   client — c'est directement le "Vague 1" de ce plan.
3. **Netcode client+serveur** — prédiction/réconciliation maison pour réduire lag et
   rollback, en tirant parti du contrôle des deux bouts de la connexion.
4. Deux mixins dormants jamais enregistrés (`ElastiqueMixin`, `HopperMixin`, absents de
   `lanterne.mixins.json`) — à vérifier, peut-être juste un oubli d'enregistrement.

## Ce qui reste hors périmètre sans instruction explicite

`C:\Users\trufa\Downloads\tokens.tok` — toujours non lu, non utilisé, origine inconnue.
