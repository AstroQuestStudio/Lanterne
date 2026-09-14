# -*- coding: utf-8 -*-
"""Genere la carte de la mine : le .md de travail, trie par fonction.

L'ancien fichier etait une liste a plat de 204 liens. Celui-ci est trie par ce
que le mod FAIT, et chaque ligne porte le seul renseignement qui decide :
la licence (absorbable ou non), la presence en 26.x (vivant ou orphelin),
et le verdict pour Lanterne.
"""
import json
import re

MINE = r"C:\Users\trufa\Documents\Lanterne\notes\mine.json"

# --- La question qui tranche vraiment : a-t-on le droit de copier le code ? ---
# Lanterne passe sous GPL-3.0-only. Entre alors tout ce qui remonte vers GPL-3 ;
# sort tout ce qui est ferme, non commercial, sans derivee, ou sous licence
# maison. Un `non` ici ne tue pas l'idee : il interdit le copier-coller.
COPIABLE = ("mit", "apache-2.0", "cc0-1.0", "unlicense", "bsd", "isc", "zlib",
            "mpl-2.0", "lgpl-3.0", "lgpl-2.1-or-later", "gpl-3.0", "osl-3.0")
JAMAIS = ("all-rights", "-nc-", "-nd-", "polyform", "protective",
          "licenseref-custom", "agpl", "lgpl-2.1-only")


def copiable(licence):
    bas = (licence or "").lower()
    if not bas or bas == "?":
        return "?"
    if any(k in bas for k in JAMAIS):
        return "non"
    if any(k in bas for k in COPIABLE):
        return "oui"
    if bas.startswith("licenseref"):
        return "non"
    if bas.startswith("cc-by-sa") or bas == "cc-by-4.0":
        return "oui"
    return "?"

# --- Les verdicts -----------------------------------------------------------
# DEJA    : Lanterne le fait, avec un chiffre mesure a l'appui
# PRENDRE : gain reel, pas couvert, absorbable -> entre dans le plan
# MESURER : plausible mais non chiffre -> banc avant code (regle de la maison)
# COTE    : reste un mod separe (trop gros, trop vivant, ou hors sujet)
# NON     : redondant avec une autre ligne, mort, ou gain non demontre
VERDICTS = {
 # --- Le moteur de rendu : on ne l'absorbe pas, on cohabite ---
 "sodium": ("COTE", "50k lignes de moteur de chunks. L'absorber = le re-maintenir contre l'upstream a chaque version."),
 "iris": ("COTE", "Les shaders. Depend de Sodium. Meme raisonnement."),
 "embeddium": ("NON", "Fork Forge de Sodium. Sodium est en 26.x nativement : le fork n'a plus d'objet."),
 "rubidium": ("NON", "Idem, plus ancien encore."),
 "xenon-forge": ("NON", "Idem."),
 "graphene": ("NON", "Idem, et arrete en 1.21.9."),
 "sodium-extra": ("COTE", "Options de Sodium. Rien a absorber sans Sodium."),
 "sodium-extras": ("NON", "Doublon de sodium-extra."),
 "rubidium-extra": ("NON", "Doublon pour un fork mort."),
 "sodium-options-mod-compat": ("NON", "Colle entre Sodium et d'autres mods."),
 "sodium-extra-information": ("NON", "Affichage de debug."),
 "veil": ("COTE", "Bibliotheque de rendu pour moddeurs. Pas une optimisation."),
 "threatengl": ("MESURER", "Force un OpenGL moderne. A verifier sur 26.1 : peut deja etre le cas."),
 "gputape": ("NON", "Annonce instable par son propre auteur. Pas dans un modpack de reference."),

 # --- Le culling : la plus grosse reserve client non couverte ---
 "entityculling": ("PRENDRE", "Raycast asynchrone : n'affiche pas les entites derriere un mur. LE gain client le plus sur."),
 "moreculling": ("PRENDRE", "Culling des faces de blocs. Complementaire, pas redondant."),
 "brute-force-rendering-culling": ("MESURER", "Culling par occlusion GPU. Gain annonce enorme, reputation d'artefacts."),
 "cull-leaves": ("PRENDRE", "Feuilles : ne rend pas l'interieur des arbres. Petit code, gain net."),
 "cull-less-leaves-reforged": ("NON", "Meme idee que cull-leaves, une seule suffit."),
 "sodiumleafculling": ("NON", "Idem, et lie a Sodium."),
 "cullify": ("NON", "Idem pour l'herbe : a plier dans le meme module."),
 "particle-culling": ("NON", "Mort en 1.12.2. L'idee va dans le module particules."),
 "cull-particles-multiloader": ("NON", "Idem, doublon."),
 "entity-collision-fps-fix": ("PRENDRE", "MC-228976 : les collisions d'entites sur le fil de rendu. Cible nommee par toi."),
 "collision-fix": ("MESURER", "Empeche de se faire pousser. Touche au jeu, pas seulement aux perfs."),
 "mob-collision-off": ("NON", "Pas de source publique, et change le jeu."),
 "no-collision": ("NON", "Datapack, meme effet."),

 # --- Les block entities : coffres, panneaux, cadres ---
 "obe": ("PRENDRE", "Rend les block entities plus vite cote client."),
 "ebe-forge": ("NON", "Meme famille qu'OBE, plus ancien."),
 "better-block-entities": ("MESURER", "Vivant en 26.x. A comparer a OBE avant de choisir."),
 "faster-block-entities": ("PRENDRE", "Transforme les block entities en modeles ordinaires. Porte en 26.x : la reference."),
 "fast-item-frames": ("PRENDRE", "Les cadres sont un cout serveur ET client sur les grosses bases."),
 "fast-paintings": ("MESURER", "Pas de source publique : a refaire, pas a copier."),
 "feytweaks": ("PRENDRE", "Panneaux et balises. Deux couts classiques."),
 "sf+": ("NON", "Pas de source, et feytweaks couvre les panneaux."),

 # --- Les particules ---
 "particle-core": ("PRENDRE", "Limites et rendu des particules. MIT, vivant en 26.x."),
 "smart-particles": ("MESURER", "Concurrent direct de particle-core. Une seule des deux."),
 "asyncparticles": ("MESURER", "Les mettre sur un autre fil. Attention aux courses de donnees."),
 "limited-damage-indicator-particle": ("PRENDRE", "Trivial et efficace en combat de masse."),

 # --- L'image : cadence, latence, mise a l'echelle ---
 "immediatelyfast": ("PRENDRE", "Regroupe les appels de rendu immediat. Gain large et sans effet de bord."),
 "exordium": ("PRENDRE", "Rend l'interface moins souvent que le monde. Gros gain avec JEI ouvert."),
 "gnetum": ("MESURER", "Etale les mises a jour du HUD. Meme famille qu'Exordium."),
 "dynamic-fps": ("PRENDRE", "Baisse la cadence en arriere-plan. Indispensable en modpack."),
 "idletweaks": ("NON", "Doublon de dynamic-fps."),
 "rrls": ("PRENDRE", "Supprime l'ecran de chargement des ressources. Trivial."),
 "reflex-antilag": ("MESURER", "NVIDIA Reflex. Depend du pilote : a verifier qu'il s'active vraiment."),
 "superresolution": ("MESURER", "DLSS/FSR. La piste que tu vises : a instruire serieusement, voir la note dediee."),
 "ixeris": ("MESURER", "Sort les appels GLFW du fil principal. Fragile mais reel."),
 "inputbooster": ("PRENDRE", "Scrutation des entrees. Ressenti immediat, code court."),
 "fast-items": ("PRENDRE", "Items au sol en 2D. Tu l'as signale toi-meme : gros gain sur les fermes."),
 "better-clouds": ("NON", "Esthetique, pas une optimisation."),
 "better-biome-blend": ("PRENDRE", "Melange des biomes en cache. Cout reel au chargement de chunk."),
 "bbrb": ("NON", "Doublon de better-biome-blend."),
 "clean-f3": ("NON", "Cosmetique."),
 "modern-ui": ("NON", "Refonte d'interface, pas une optimisation."),
 "kerria-opt": ("MESURER", "Animation des textures. Cout reel mais petit."),
 "veloxcaelo": ("NON", "1.8.9, OptiFine CIT. Hors sujet."),
 "sound-culling": ("PRENDRE", "Limite les sons superposes. Cout CPU reel dans les fermes."),
 "shut-up-gl-error": ("PRENDRE", "Journalisation parasite. Trivial."),
 "dsbg": ("PRENDRE", "Idem cote serveur : setBlock in a far chunk."),
 "asynclogger": ("MESURER", "Journalisation asynchrone. Gain reel sur serveur bavard."),

 # --- La memoire ---
 "ferrite-core": ("PRENDRE", "Deduplication des etats de blocs. Des centaines de Mo en modpack."),
 "memoryleakfix": ("PRENDRE", "Fuites connues et documentees. Peu de code."),
 "saturn": ("MESURER", "Pas de source publique : a refaire depuis l'idee."),
 "redirected": ("PRENDRE", "Deduplication des valeurs d'enum. Court et net."),
 "foamfix": ("NON", "Ancien, largement absorbe par FerriteCore."),
 "mpalladium": ("MESURER", "Annonce large et vague. A verifier avant d'y croire."),
 "modernfix": ("PRENDRE", "Le plus rentable de la liste au demarrage ET en memoire."),

 # --- Le demarrage et le chargement ---
 "lightspeed": ("NON", "Pas de source publique."),
 "lightspeedre": ("MESURER", "Fork du precedent, source a verifier."),
 "lazyyyyy": ("MESURER", "Chargement des gros packs."),
 "ksyxis": ("PRENDRE", "Supprime le chargement des chunks du spawn au demarrage. Trivial, gain immediat."),
 "fastload": ("NON", "Meme cible que Ksyxis."),
 "quickquit": ("PRENDRE", "Fermer pendant le chargement. Confort pur, deux lignes."),
 "fastquit-forge": ("PRENDRE", "Retour au menu instantane. Idem."),
 "quick-pack": ("PRENDRE", "Lecture des zip de packs."),
 "0pack2reload": ("PRENDRE", "Evite un rechargement inutile des packs."),
 "resourcepackcached": ("PRENDRE", "Garde le pack du serveur. Gain enorme a chaque reconnexion."),
 "jeioptimizer": ("PRENDRE", "15x sur le filtre JEI. Tu gardes JEI : c'est donc pour toi."),
 "languagereloadunofficial": ("MESURER", "Rechargement des langues."),
 "smooth-boot": ("MESURER", "Ordonnancement CPU au demarrage. Pas de source pour les deux forks."),
 "smooth-boot-reloaded": ("NON", "Doublon sans source."),
 "notenoughrecipebook": ("MESURER", "Livre de recettes. Cout reel a la connexion."),
 "fastsuite": ("PRENDRE", "Tout le systeme de recettes. MIT, deja en 26.1.2."),
 "craftboost-mccmcaozr": ("NON", "Pas de source. FastSuite couvre la meme chose."),
 "distraction-free-recipes": ("NON", "Confort d'interface."),
 "tmrv": ("NON", "Pont JEI/EMI. Hors sujet."),
 "workstation-recipe-exporter": ("NON", "Outil de moddeur."),

 # --- Les evenements, les mixins, la plomberie ---
 "fastevent": ("PRENDRE", "Le bus d'evenements NeoForge est un cout permanent en modpack."),
 "mixinbooster": ("NON", "Forge ancien. NeoForge 26 n'a pas ce probleme."),
 "toadlib": ("NON", "Bibliotheque d'un autre auteur."),
 "jauml": ("NON", "Bibliotheque de configuration. Lanterne a la sienne."),
 "uuid-hex": ("MESURER", "Formatage d'UUID. A verifier que c'est vraiment chaud."),
 "efficient-hashing": ("PRENDRE", "Hachage de BlockPos/Vec3i. Chemin ultra-chaud, code minuscule."),
 "faster-random": ("PRENDRE", "Le generateur aleatoire est partout. Apache-2.0."),
 "icterine": ("NON", "Remplace par cerulean-advancements."),
 "cerulean-advancements": ("PRENDRE", "InventoryChangeTrigger : cout reel a chaque changement d'inventaire."),
 "achievements-optimizer": ("NON", "Pas de source, meme cible."),

 # --- Serveur : entites et IA ---
 "lithium": ("COTE", "Vivant en 26.x et tres large. A comparer module par module, pas a absorber en bloc."),
 "radium": ("NON", "Fork Forge de Lithium, mort."),
 "canary": ("NON", "Idem, et sans source."),
 "ai-improvements": ("PRENDRE", "Cible nommee par toi. Deja en 26.x, source lisible."),
 "mobtimizations": ("PRENDRE", "Taches d'IA gaspillees. Recouvre en partie EntityThrottle : a departager au banc."),
 "does-it-tick": ("PRENDRE", "Ordonnanceur de tick d'entites. Le plus proche de ce que Lanterne fait deja."),
 "immersive-optimization": ("MESURER", "Annonce un doublement de TPS. A verifier contre EntityThrottle."),
 "notick": ("MESURER", "Meme famille. Trois mods pour la meme idee : n'en garder qu'une."),
 "async": ("NON", "Entites en parallele. Risque de course de donnees trop eleve pour un mod de reference."),
 "too-many-entities": ("MESURER", "Cache les entites en trop. Recouvre le culling."),
 "harium": ("PRENDRE", "IA, redstone, entites, entonnoirs, physique. Le plus proche de l'ambition de Lanterne."),
 "pathwright": ("MESURER", "Recherche de chemin. Pas de source : a refaire depuis l'idee."),
 "a-star-pathfinding": ("NON", "Datapack. Pas applicable."),
 "skeleton-ai-fix": ("PRENDRE", "Le strafe des squelettes est un cout ET un defaut de jeu."),
 "sheep-efficiency": ("MESURER", "Les moutons qui mangent l'herbe. Pas de source, idee triviale a refaire."),
 "despawn-tweaks": ("PRENDRE", "Les mobs qui ne despawnent jamais sont la premiere cause de derive."),
 "lmd": ("NON", "Meme cible que despawn-tweaks."),
 "zombies-reworked": ("NON", "Contenu, pas optimisation."),
 "mobtimizations-dup": ("NON", ""),
 "you-shall-not-spawn": ("MESURER", "Gestion du spawn. Lanterne a deja la Vigie : a comparer."),
 "betterspawn": ("NON", "Pas de source, et hors sujet perfs."),

 # --- Serveur : chunks, worldgen, sauvegarde ---
 "c2me-neoforge": ("COTE", "Genere les chunks en parallele. Enorme, vivant, et risque eleve. Reste a cote."),
 "c2mef": ("NON", "Fork mort."),
 "connectored-c2me": ("NON", "Fork de fork."),
 "c2me-ocl": ("COTE", "Worldgen sur GPU via OpenCL. Fascinant, hors perimetre."),
 "moonrise-opt": ("COTE", "Le plus serieux cote serveur. Vivant en 26.x. A comparer, pas a absorber."),
 "noisiumforked": ("PRENDRE", "Worldgen cible. Vivant en 26.x."),
 "noisiumed": ("NON", "Doublon."),
 "zfastnoise": ("PRENDRE", "Le bruit, c'est 30% de la generation, tu l'as dit. Cible nette."),
 "structure-layout-optimizer": ("PRENDRE", "Les grosses structures figent le serveur. MIT, en 26.x."),
 "huge-structure-blocks": ("NON", "Contenu."),
 "substrate": ("MESURER", "Couches basses et hautes du monde. Idee originale a chiffrer."),
 "deltachunk": ("MESURER", "Reduit la taille du monde. Touche au format de sauvegarde : prudence."),
 "chunky": ("COTE", "Pregeneration. Outil separe, tres bien comme il est."),
 "chunky-extension": ("NON", "Greffon de Chunky."),
 "chunk_gen": ("NON", "Datapack."),
 "born-in-a-barn": ("MESURER", "Les villages chargent des chunks. Bug reel, sans source."),
 "terrablenderfix": ("NON", "Correctif pour un mod tiers."),
 "worldgenfeaturefix": ("MESURER", "Correctif de plantage. A garder sous le coude."),
 "ksyxis-dup": ("NON", ""),
 "unloaded-activity": ("MESURER", "Rattrape les blocs apres dechargement. Change le jeu : a discuter."),
 "voxy-worldgen": ("COTE", "Lie a Voxy."),
 "voxy-server-side": ("COTE", "Idem."),
 "distanthorizons": ("COTE", "Vue lointaine en LOD. Enorme et vivant. Cohabite."),

 # --- Serveur : la lumiere ---
 "starlight-forge": ("MESURER", "Moteur de lumiere. Lanterne a mesure 3.8% seulement : a re-verifier avant d'y aller."),
 "scalablelux": ("MESURER", "Starlight en mieux, d'apres toi. En 26.x. Le candidat serieux."),
 "radon": ("NON", "Phosphor pour FML, mort."),
 "phosphorlegacyforge": ("NON", "Mort."),
 "alfheim-lighting-engine": ("NON", "1.12.2. Les idees sont dans ScalableLux."),

 # --- Serveur : redstone et machines ---
 "alternate-current": ("PRENDRE", "Refonte du moteur de redstone. MIT, en 26.2. Tu l'as marque giga interessant : tu as raison."),
 "railoptimization": ("PRENDRE", "Rails 10x plus rapides. Code court."),
 "nora-rail-optimization": ("NON", "Fork du precedent."),
 "fastfurnace": ("NON", "Lanterne a deja les fours, au tick pres et mesures."),
 "syncboost-mccmcaozr": ("NON", "Pas de source, et Lanterne a deja les entonnoirs."),
 "create-threaded-trains": ("COTE", "Specifique a Create."),
 "createbetterfps": ("COTE", "Idem."),
 "iris-flw-compat": ("COTE", "Idem."),
 "autochefs-delight": ("NON", "Specifique a Farmer's Delight."),
 "refined-fluid-substitution": ("NON", "Specifique a Refined Storage."),

 # --- Reseau ---
 "krypton": ("PRENDRE", "La pile reseau. Lanterne a deja la compression : le reste est a prendre."),
 "krypton-fnp": ("MESURER", "Extension bas niveau."),
 "krypton-foxified": ("NON", "Port NeoForge de Krypton, qui est deja en 26.x."),
 "pluto": ("NON", "Fork sans source."),
 "not-enough-bandwidth": ("PRENDRE", "Economise la bande passante. Complementaire de la compression."),
 "bandwidthoptimizer": ("MESURER", "Meme cible, a departager."),
 "resource-trimmer": ("MESURER", "Idem."),
 "xxl-packets": ("PRENDRE", "Leve la limite de 2 Mo par paquet. Indispensable en gros modpack."),
 "fast-ip-ping": ("PRENDRE", "Ping du menu multijoueur. Trivial et visible."),
 "disconnect-packet-fix": ("PRENDRE", "MC-271325. Correctif net."),
 "vmp-forge": ("MESURER", "Serveur a fort effectif. Mort en 1.20.1 mais les idees valent."),
 "xp-stream": ("MESURER", "Concurrent de Clump. Lanterne a deja les orbes, x11.76 mesure."),
 "ly-clumps": ("NON", "Lanterne a deja mieux, mesure."),

 # --- Nettoyage et budget de tick ---
 "sweeper-maid": ("NON", "Lanterne a deja le Balai."),
 "tct-clearlag": ("NON", "Idem, et sans source."),
 "entity-cleaner": ("NON", "Idem, datapack."),
 "opti-world": ("NON", "Idem."),
 "get-it-together-drops": ("NON", "Deja absorbe dans Gather, x1.27 a x1.42 mesure."),
 "staaaaaaaaaaaack": ("MESURER", "Fusion au-dela de la pile. Va plus loin que Gather : a chiffrer."),
 "tt20": ("PRENDRE", "Degrade proprement quand le TPS chute. Tu l'as marque cool : c'est la bonne intuition."),
 "servercore": ("PRENDRE", "Le plus proche de Lanterne dans l'esprit. MIT, en 26.2. A eplucher en priorite."),
 "potatoptimize": ("MESURER", "Greffon serveur."),

 # --- Confort, correctifs, contenu ---
 "leaves-be-gone": ("PRENDRE", "Tu l'as decrit precisement, y compris le cas des feuilles posees a la main."),
 "accelerated-decay": ("NON", "Meme cible, moins propre."),
 "cleancut": ("PRENDRE", "Frapper a travers les hautes herbes. Confort net, code court."),
 "better-beds": ("MESURER", "Lits optimises et plus beaux."),
 "craftingtweaks": ("NON", "Contenu, 700 recettes. Hors sujet."),
 "norollback": ("MESURER", "Les retours en arriere de position. Tu l'as marque important."),
 "thirdperson": ("NON", "Contenu."),
 "afkfishing": ("NON", "Automatisation de jeu, hors sujet pour un mod de perfs."),
 "item-obliterator": ("NON", "Outil de modpack."),
 "notsoessential": ("NON", "Specifique a Essential."),
 "curtain": ("COTE", "Carpet pour Forge. Outil de test, pas de production."),
 "crashexploitfixer": ("PRENDRE", "Failles de plantage. Un mod de serveur doit les avoir."),
 "vanillaicecreamfix": ("PRENDRE", "VanillaFix : ne pas tomber au menu sur une erreur de rendu."),
 "patcher": ("MESURER", "Sac de correctifs. A trier un par un."),
 "bug-fixes": ("NON", "Pas de source."),
 "wither-spawn-fix": ("NON", "Pas de source, cas tres etroit."),
 "fix-attack-lag": ("MESURER", "Le lag a l'attaque. Tu as signale exactement ce symptome sur les villageois."),
 "server_heater": ("PRENDRE", "Les gels a l'arret du serveur. Tu redemarres souvent."),
 "dim-tracker+afk-display": ("NON", "Datapack d'affichage."),
 "mapboost-mccmcaozr": ("MESURER", "Frequence des cartes. Pas de source."),
 "observable": ("COTE", "Profileur. Outil, et Lanterne a deja ses bancs."),
 "fism": ("NON", "Deja integre dans Iris."),
 "faster-block-entities-dup": ("NON", ""),
}

CATEGORIES = [
 ("Le moteur de rendu — on cohabite, on n'absorbe pas",
  ["sodium","iris","embeddium","rubidium","xenon-forge","graphene","sodium-extra","sodium-extras",
   "rubidium-extra","sodium-options-mod-compat","sodium-extra-information","veil","threatengl","gputape",
   "distanthorizons","voxy-worldgen","voxy-server-side"]),
 ("Le culling — la plus grosse reserve client non couverte",
  ["entityculling","moreculling","brute-force-rendering-culling","cull-leaves","cull-less-leaves-reforged",
   "sodiumleafculling","cullify","particle-culling","cull-particles-multiloader","entity-collision-fps-fix",
   "collision-fix","mob-collision-off","no-collision","too-many-entities"]),
 ("Les block entities — coffres, panneaux, cadres, peintures",
  ["obe","ebe-forge","better-block-entities","faster-block-entities","fast-item-frames","fast-paintings",
   "feytweaks","sf+"]),
 ("Les particules et les sons",
  ["particle-core","smart-particles","asyncparticles","limited-damage-indicator-particle","sound-culling"]),
 ("L'image — cadence, latence, mise a l'echelle",
  ["immediatelyfast","exordium","gnetum","dynamic-fps","idletweaks","rrls","reflex-antilag","superresolution",
   "ixeris","inputbooster","fast-items","better-clouds","better-biome-blend","bbrb","clean-f3","modern-ui",
   "kerria-opt","veloxcaelo"]),
 ("La memoire",
  ["ferrite-core","memoryleakfix","saturn","redirected","foamfix","mpalladium","modernfix"]),
 ("Le demarrage, le chargement, les recettes",
  ["lightspeed","lightspeedre","lazyyyyy","ksyxis","fastload","quickquit","fastquit-forge","quick-pack",
   "0pack2reload","resourcepackcached","jeioptimizer","languagereloadunofficial","smooth-boot",
   "smooth-boot-reloaded","notenoughrecipebook","fastsuite","craftboost-mccmcaozr","distraction-free-recipes",
   "tmrv","workstation-recipe-exporter"]),
 ("La plomberie — evenements, hachage, aleatoire, journalisation",
  ["fastevent","mixinbooster","toadlib","jauml","uuid-hex","efficient-hashing","faster-random","icterine",
   "cerulean-advancements","achievements-optimizer","asynclogger","shut-up-gl-error","dsbg"]),
 ("Serveur — les entites et l'IA",
  ["lithium","radium","canary","ai-improvements","mobtimizations","does-it-tick","immersive-optimization",
   "notick","async","harium","pathwright","a-star-pathfinding","skeleton-ai-fix","sheep-efficiency",
   "despawn-tweaks","lmd","zombies-reworked","you-shall-not-spawn","betterspawn"]),
 ("Serveur — chunks, generation, sauvegarde",
  ["c2me-neoforge","c2mef","c2me-ocl","moonrise-opt","noisiumforked","noisiumed","zfastnoise",
   "structure-layout-optimizer","huge-structure-blocks","substrate","deltachunk","chunky","chunky-extension",
   "chunk_gen","born-in-a-barn","terrablenderfix","worldgenfeaturefix","unloaded-activity"]),
 ("Serveur — la lumiere",
  ["starlight-forge","scalablelux","radon","phosphorlegacyforge","alfheim-lighting-engine"]),
 ("Serveur — redstone, rails, machines",
  ["alternate-current","railoptimization","nora-rail-optimization","fastfurnace","syncboost-mccmcaozr",
   "create-threaded-trains","createbetterfps","iris-flw-compat","autochefs-delight","refined-fluid-substitution"]),
 ("Le reseau",
  ["krypton","krypton-fnp","krypton-foxified","pluto","not-enough-bandwidth","bandwidthoptimizer",
   "resource-trimmer","xxl-packets","fast-ip-ping","disconnect-packet-fix","vmp-forge","xp-stream","ly-clumps"]),
 ("Le nettoyage et le budget de tick",
  ["sweeper-maid","tct-clearlag","entity-cleaner","opti-world","get-it-together-drops","staaaaaaaaaaaack",
   "tt20","servercore","potatoptimize"]),
 ("Confort, correctifs, et ce qui n'est pas de l'optimisation",
  ["leaves-be-gone","accelerated-decay","cleancut","better-beds","craftingtweaks","norollback","thirdperson",
   "afkfishing","item-obliterator","notsoessential","curtain","crashexploitfixer","vanillaicecreamfix",
   "patcher","bug-fixes","wither-spawn-fix","fix-attack-lag","server_heater","dim-tracker+afk-display",
   "mapboost-mccmcaozr","observable","fism"]),
]

ORDRE = {"PRENDRE": 0, "REFAIRE": 1, "MESURER": 2, "COTE": 3, "NON": 4, "?": 5}
PUCE = {"PRENDRE": "**PRENDRE**", "REFAIRE": "**REFAIRE**", "MESURER": "MESURER",
        "COTE": "a cote", "NON": "non", "?": "a trier"}


def main():
    d = json.load(open(MINE, encoding="utf-8"))
    lignes = []
    A = lignes.append

    A("# La mine — 202 mods tries par ce qu'ils font")
    A("")
    A("Genere par `tools/carte.py` depuis l'API Modrinth (`notes/mine.json`).")
    A("Pas ecrit a la main : la licence et la version viennent de la source, pas de la memoire.")
    A("")
    A("| Colonne | Ce qu'elle decide |")
    A("|---|---|")
    A("| **Licence** | MIT/CC0/Apache : absorbable tel quel. LGPL/GPL : absorbable **seulement** si Lanterne passe sous la meme licence. |")
    A("| **26.x** | `oui` = le mod est vivant sur ta version. L'absorber, c'est signer pour le re-maintenir contre son auteur. |")
    A("| **Copie** | `oui` : on peut reprendre le code sous GPL-3. `non` : licence fermee, "
      "non commerciale ou sans derivee — **seule l'idee est reutilisable**, il faut reecrire. |")
    A("| **Verdict** | **PRENDRE** : absorber le code. **REFAIRE** : l'idee vaut, le code est interdit. "
      "MESURER : banc avant code. `a cote` : reste un mod separe. `non` : redondant, mort, ou hors sujet. |")
    A("")

    vus, bascules = set(), []
    for titre, membres in CATEGORIES:
        A(f"## {titre}")
        A("")
        A("| Mod | Licence | Copie | 26.x | Verdict | Pourquoi |")
        A("|---|---|:--:|:--:|---|---|")
        rangs = []
        for s in membres:
            vus.add(s)
            info = d.get(s, {})
            v, pq = VERDICTS.get(s, ("?", ""))
            cop = copiable(info.get("licence"))
            # Un PRENDRE dont le code est interdit devient un REFAIRE. La regle
            # est appliquee ici, pas a la main : on ne peut pas l'oublier.
            if v == "PRENDRE" and cop == "non":
                v = "REFAIRE"
                pq = pq + " **Code non copiable : a reecrire depuis l'idee.**"
                bascules.append(s)
            rangs.append((ORDRE[v], s, info, v, pq, cop))
        for _, s, info, v, pq, cop in sorted(rangs):
            lic = info.get("licence") or "?"
            a26 = "oui" if info.get("a_26") else (info.get("max_mc") or "—")
            src = info.get("source") or ""
            nom = f"[{s}]({src})" if src else f"{s} *(pas de source)*"
            A(f"| {nom} | {lic} | {cop} | {a26} | {PUCE[v]} | {pq} |")
        A("")

    reste = [s for s in d if s not in vus]
    if reste:
        A("## Non classes")
        A("")
        for s in sorted(reste):
            info = d[s]
            A(f"- `{s}` — {info.get('licence','?')} — {info.get('resume','')}")
        A("")

    # Le recapitulatif qui sert reellement a decider
    def final(s):
        v = VERDICTS.get(s, ("?", ""))[0]
        return "REFAIRE" if (v == "PRENDRE" and s in bascules) else v

    compte = {}
    for s in vus:
        v = final(s)
        compte[v] = compte.get(v, 0) + 1
    A("## Le compte")
    A("")
    for v in ["PRENDRE", "REFAIRE", "MESURER", "COTE", "NON"]:
        A(f"- {PUCE[v]} : {compte.get(v,0)}")
    A("")

    if bascules:
        A("### Les idees qu'il faut reecrire au lieu de copier")
        A("")
        for s in sorted(bascules):
            A(f"- **{s}** — `{d.get(s,{}).get('licence','?')}`. "
              f"{VERDICTS.get(s,('',''))[1]}")
        A("")
        A("Une licence fermee protege l'**expression**, pas l'idee. Un raycast vers une entite, "
          "un cache de visibilite, un filtre d'ingredients : ces mecaniques se reecrivent. "
          "Ce qui est interdit, c'est d'ouvrir leur source et d'en recopier les lignes.")
        A("")

    prendre = sorted(s for s in vus if final(s) == "PRENDRE")
    A("### Les PRENDRE, dans l'ordre du plan")
    A("")
    A(", ".join(f"`{s}`" for s in prendre))
    A("")

    out = r"C:\Users\trufa\Documents\Lanterne\notes\MINE.md"
    open(out, "w", encoding="utf-8").write("\n".join(lignes))
    print(f"Ecrit : {out} ({len(lignes)} lignes)")
    print("PRENDRE :", len(prendre))


if __name__ == "__main__":
    main()
