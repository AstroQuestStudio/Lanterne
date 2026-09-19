#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Le contrôleur de cibles : chaque mixin vise-t-il encore quelque chose ?

POURQUOI CET OUTIL EXISTE
=========================

Une cible de mixin est une CHAÎNE DE CARACTÈRES. Le compilateur Java ne la relit
jamais. Un mixin dont la méthode visée a été renommée compile donc parfaitement,
et n'échoue qu'au démarrage du jeu — avec « defaultRequire: 1 », par un écran
d'erreur.

Le passage à Minecraft 26.2 en a fourni la démonstration. « EntityCullMixin »
visait « LevelRenderer.extractVisibleEntities ». La 26.2 a coupé le rendu en deux
et déplacé cette méthode dans une classe neuve, « LevelExtractor », dans un paquet
neuf. Tout compilait. Le serveur dédié démarrait sans broncher — et pour cause :
un serveur dédié ne charge aucun mixin client. Le défaut n'aurait été visible
qu'au premier lancement du client par un joueur.

C'est le pire genre de panne : silencieuse côté développeur, fatale côté joueur.

CE QUE CE CONTRÔLEUR FAIT
=========================

Il lit les mixins, en extrait les cibles, et vérifie chacune contre le jar de
Minecraft réellement utilisé pour compiler. Trois choses sont contrôlées :

  1. la classe visée par « @Mixin » existe-t-elle ?
  2. chaque « method = "nom" » désigne-t-il une méthode de cette classe ?
  3. chaque « target = "Lpaquet/Classe;methode(args)retour" » — la cible d'un
     @Redirect, d'un @WrapOperation ou d'un @At INVOKE — existe-t-elle dans la
     classe qu'elle nomme, avec exactement cette signature ?

Le troisième point est le plus utile : une signature entière, c'est là que se
cachent les ruptures qu'un simple nom ne révèle pas.

CE QU'IL NE FAIT PAS, ET IL FAUT LE SAVOIR
==========================================

Il ne vérifie pas qu'une injection trouvera son point d'ancrage. « @At("HEAD") »
est toujours valide ; « @At(value = "INVOKE", ordinal = 2) » peut très bien
désigner un troisième appel qui n'existe plus, sans que rien ici ne s'en aperçoive.
Ce contrôleur écarte la panne la plus fréquente, pas toutes les pannes.

Il ne vérifie pas non plus les arguments d'injection : un « @Inject » dont la
signature de rappel ne correspond plus au type de retour de la méthode visée
passera d'ici sans encombre.

Autrement dit : un mixin qui passe ce contrôle n'est pas prouvé. Un mixin qui y
échoue est cassé.

UN MIXIN PEUT ÊTRE ABSENT DU MANIFESTE SANS ÊTRE UN OUBLI
===========================================================

Un fichier présent dans mixin/ mais absent de lanterne.mixins.json ne s'applique jamais — et
c'est en général un oubli d'enregistrement, donc une vraie panne. Mais ce dépôt garde aussi,
volontairement, la trace d'expériences mesurées en jeu puis rejetées (voir ElastiqueMixin,
HopperMixin) : le fichier reste, avec sa javadoc complète, pour ne pas repartir de zéro le jour
où une vraie piste se présente, mais il ne doit surtout pas s'appliquer.

Distinguer les deux cas à la lecture de la prose serait fragile — la même fragilité que ce
contrôleur dénonce partout ailleurs. Le marqueur littéral MARQUEUR_DESACTIVATION, posé juste
avant l'annotation « @Mixin » de la classe, le permet sans ambiguïté : un fichier qui le porte
est annoncé, pas compté en échec. Un fichier absent du manifeste et sans ce marqueur reste une
panne, exactement comme avant.

USAGE
=====

    python tools/verifie_mixins.py

Code de retour 0 si tout va bien, 1 si au moins une cible est introuvable.
"""

import json
import pathlib
import re
import subprocess
import sys

# La console Windows par défaut est en cp1252 et refuserait les accents de ce
# fichier comme ses puces. On force l'UTF-8 plutôt que d'appauvrir les messages.
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

RACINE = pathlib.Path(__file__).resolve().parent.parent
MIXINS = RACINE / "src" / "main" / "java" / "fr" / "clubcitrouille" / "lanterne" / "mixin"
ARTEFACTS = RACINE / "build" / "moddev" / "artifacts"


def trouve_jar():
    """Le chemin de classes à contrôler.

    Deux sources, et il faut les deux :

      — le jar de Minecraft. On prend « -merged » quand il existe : c'est celui
        qui contient à la fois les classes client et serveur, et la moitié des
        mixins de ce dépôt vise le client. Contrôler un mixin client contre un
        jar serveur rendrait « classe introuvable » sur du code parfaitement
        sain — l'erreur exactement inverse de celle qu'on cherche.

      — le chemin de classes complet, que la tâche Gradle « cheminDeClasses »
        dépose dans build/. Sans lui, tout mixin visant une classe de NeoForge
        — et ce dépôt en a — serait signalé à tort.

    Le second est facultatif : sans lui l'outil fonctionne, avec un angle mort
    qu'il annonce.
    """
    if not ARTEFACTS.is_dir():
        return None
    fusionnes = sorted(ARTEFACTS.glob("minecraft-patched-*-merged.jar"))
    if fusionnes:
        base = fusionnes[-1]
    else:
        simples = sorted(p for p in ARTEFACTS.glob("minecraft-patched-*.jar")
                         if "sources" not in p.name)
        if not simples:
            return None
        base = simples[-1]

    depose = RACINE / "build" / "chemin-de-classes.txt"
    if depose.is_file():
        entrees = [morceau for morceau in depose.read_text(encoding="utf-8").split(";")
                   if morceau.strip()]
        if entrees:
            return (str(base), ";".join([str(base)] + entrees), True)
    return (str(base), str(base), False)


def imports(texte):
    """Nom court -> nom complet, d'après les imports du fichier."""
    table = {}
    for ligne in re.findall(r"^import\s+(?:static\s+)?([\w.]+);", texte, re.M):
        table[ligne.rsplit(".", 1)[-1]] = ligne
    return table


def cibles_de_classe(texte, table):
    """Les classes visées par @Mixin, en noms complets tels que « javap » les attend.

    Trois formes coexistent dans ce dépôt, et les trois piègent différemment :

      @Mixin(Brain.class)                    nom court, à résoudre par les imports
      @Mixin(net.minecraft...PlayerSensor.class)   déjà complet
      @Mixin(MemoryCondition.Registered.class)     classe imbriquée

    La troisième est la plus vicieuse : « javap » veut un dollar, pas un point, et
    seule la ou les dernières composantes portent la majuscule qui distingue une
    classe imbriquée d'un morceau de paquet.
    """
    trouvees = []
    for bloc in re.findall(r"@Mixin\s*\(([^)]*)\)", texte, re.S):
        for chemin in re.findall(r"([\w.]+)\s*\.\s*class", bloc):
            morceaux = chemin.split(".")
            if len(morceaux) == 1:
                trouvees.append(table.get(chemin, chemin))
                continue
            # Déjà complet ? La première composante commence par une minuscule.
            if morceaux[0][:1].islower():
                tete, reste = [], list(morceaux)
                while reste and reste[0][:1].islower():
                    tete.append(reste.pop(0))
                trouvees.append(".".join(tete + [reste[0]]) + "".join(
                        "$" + m for m in reste[1:]))
                continue
            # Sinon : « Externe.Imbriquee », la tête se résout par les imports.
            racine = table.get(morceaux[0], morceaux[0])
            trouvees.append(racine + "".join("$" + m for m in morceaux[1:]))
        # Forme « targets = "net.minecraft.Machin" », employée quand la classe
        # n'est pas accessible à la compilation.
        for complet in re.findall(r'targets\s*=\s*"([\w.$]+)"', bloc):
            trouvees.append(complet)
    return trouvees


def noms_de_methodes(texte):
    """Les cibles « method = "..." », séparées selon qu'elles portent ou non une signature.

    <h2>L'angle mort qui a fait échouer un démarrage</h2>

    Cette fonction ne rendait que des NOMS. C'était insuffisant, et cela s'est
    payé : « BlockEntityVeilMixin » visait
    « tryExtractRenderState(BlockEntity, float, CrumblingOverlay, Frustum) », et
    la 26.2 a inséré un booléen avant le Frustum. Le nom existait toujours, donc
    le contrôleur se taisait — et Mixin refusait de s'appliquer au lancement du
    client. Écran d'erreur chez le joueur, sur un dépôt qui compilait et dont le
    serveur dédié démarrait : un serveur dédié ne charge aucun mixin client, rien
    ne pouvait le signaler de ce côté.

    Quand une cible donne sa signature, on la vérifie donc entièrement. Quand elle
    ne donne qu'un nom — ce qui est permis, et fréquent — on ne peut vérifier que
    le nom, et c'est dit.

    @return deux ensembles : les noms seuls, et les couples (nom, signature).
    """
    noms = set()
    signes = set()
    for brut in re.findall(r'method\s*=\s*((?:"(?:[^"\\]|\\.)*"\s*\+?\s*)+)', texte):
        assemble = "".join(re.findall(r'"((?:[^"\\]|\\.)*)"', brut))
        # Attention : une signature contient des virgules dans les types generiques ?
        # Non — un descripteur JVM n'en contient aucune. Decouper sur la virgule est
        # donc sur, a condition de recoller les morceaux d'une meme parenthese.
        for morceau in assemble.split(","):
            morceau = morceau.strip()
            if not morceau:
                continue
            forme = re.match(r"^([\w<>$]+)(\(.*\).+)$", morceau)
            if forme:
                signes.add((forme.group(1), forme.group(2)))
            else:
                noms.add(morceau.split("(")[0].strip())
    return noms, signes


def cibles_invoke(texte):
    """Les « target = "Lpaquet/Classe;methode(args)retour" » recollées."""
    sorties = []
    for brut in re.findall(r'target\s*=\s*((?:"(?:[^"\\]|\\.)*"\s*\+?\s*)+)', texte):
        assemble = "".join(re.findall(r'"((?:[^"\\]|\\.)*)"', brut))
        correspond = re.match(r"^L([\w/$]+);([\w<>$]+)(\(.*\).+)$", assemble)
        if correspond:
            sorties.append((correspond.group(1).replace("/", "."),
                            correspond.group(2), correspond.group(3)))
    return sorties


_cache = {}


_lues = {}

# Ce qui ne se trouve pas dans le chemin de classes du mod, et qu'il est inutile
# d'interroger. Aucun mixin de ce depot ne vise la bibliotheque standard.
HORS_PORTEE = ("java.", "javax.", "jdk.", "sun.", "com.google.", "org.slf4j.",
               "it.unimi.", "org.joml.")

# Marque un mixin volontairement absent de lanterne.mixins.json : une experience mesuree en
# jeu, puis rejetee, dont le fichier est garde comme trace (voir la javadoc de la classe). Ce
# n'est pas un oubli d'enregistrement, et ce controleur ne doit pas le signaler comme tel — mais
# il ne doit pas non plus le confondre avec un VRAI oubli en devinant sur la prose de la javadoc :
# une chaine litterale, sans ambiguite, tranche a la place de deviner.
MARQUEUR_DESACTIVATION = "LANTERNE_MIXIN_DESACTIVE"


def analyse(sortie):
    """Decoupe une sortie de « javap » multi-classes, et en tire ce qui nous interesse."""
    resultats = {}
    courant = None
    dernier = None
    for ligne in sortie.splitlines():
        nue = ligne.strip()
        entete = re.match(r"^(?:public |protected |private |abstract |final |static |sealed |non-sealed )*"
                          r"(?:class|interface|enum|record|@interface)\s+([\w.$]+)", nue)
        if entete:
            courant = entete.group(1)
            resultats[courant] = [set(), set(), []]
            dernier = None
            if nue.endswith("{") and (" extends " in nue or " implements " in nue):
                # « extends A implements B, C » : deux clauses, et « implements » ne
                # contient que des caracteres de mot — une seule expression les
                # avalerait ensemble et rendrait « A implements B » comme nom de classe.
                corps = nue.rstrip("{").strip()
                clauses = re.split(r"\bimplements\b", corps)
                candidats = []
                apres = re.search(r"\bextends\b(.*)", clauses[0])
                if apres:
                    candidats.extend(apres.group(1).split(","))
                for clause in clauses[1:]:
                    candidats.extend(clause.split(","))
                for nom in candidats:
                    nom = re.sub(r"<.*?>", "", nom).strip()
                    if nom and nom != "java.lang.Object" and re.fullmatch(r"[\w.$]+", nom):
                        resultats[courant][2].append(nom)
            continue
        if courant is None:
            continue
        if nue.startswith("descriptor:"):
            if dernier:
                resultats[courant][1].add((dernier, nue.split(":", 1)[1].strip()))
            continue
        # « throws Xxx » peut s'intercaler entre la parenthèse fermante et le
        # point-virgule (« configure(...) throws SurfaceException; ») — sans cette
        # clause optionnelle, toute méthode qui déclare une exception vérifiée
        # ratait ce motif et disparaissait purement et simplement de « connus »,
        # ce qui faisait crier « method absente » sur une cible parfaitement
        # valide. Trouvé sur VulkanGpuSurface.configure/acquireNextTexture, toutes
        # deux « throws SurfaceException » — jamais vu avant parce qu'aucun mixin
        # de ce dépôt ne visait jusqu'ici une méthode déclarant une exception.
        forme = re.search(
                r"([\w<>$]+)\s*\([^)]*\)(?:\s+throws\s+[\w.$]+(?:\s*,\s*[\w.$]+)*)?\s*;?\s*$",
                nue)
        if forme:
            dernier = forme.group(1)
            resultats[courant][0].add(dernier)
    return resultats


def charge(noms, chemin):
    """Lit plusieurs classes en UN SEUL appel a « javap ».

    Cette methode existe pour une raison mesuree : un appel par classe mettait
    quatre minutes, parce que « Entity » et ses aieux etaient relus pour chacun
    des cinquante mixins. Regroupes, les memes lectures prennent quelques
    secondes. Une verification qu'on n'a pas le temps d'attendre ne se lance pas,
    et un garde-fou qu'on ne lance pas ne garde rien.
    """
    a_lire = [nom for nom in dict.fromkeys(noms)
              if nom not in _lues and not nom.startswith(HORS_PORTEE)]
    for nom in noms:
        if nom.startswith(HORS_PORTEE):
            _lues.setdefault(nom, None)
    if not a_lire:
        return
    issue = subprocess.run(["javap", "-p", "-s", "-cp", chemin] + a_lire,
                           capture_output=True, text=True, errors="replace")
    trouvees = analyse(issue.stdout)
    for nom in a_lire:
        _lues[nom] = tuple(trouvees[nom]) if nom in trouvees else None


def membres(classe, chemin):
    """Les methodes d'une classe ET de tout ce dont elle herite.

    Remonter la parente n'est pas un raffinement, c'est une necessite. « javap »
    ne montre que les membres declares ; or un mixin vise tres legitimement une
    methode heritee. « LivingEntityMixin » vise ainsi « isEffectiveAi », qui est
    declaree dans « Entity ». Sans cette remontee, l'outil rendrait « n'existe
    plus » sur un mixin parfaitement sain — et un controleur qui crie au loup se
    fait debrancher au bout de deux fois.

    Rend None si la classe elle-meme est introuvable ; les parents introuvables,
    eux, sont ignores en silence.
    """
    charge([classe], chemin)
    lu = _lues.get(classe)
    if lu is None:
        return None
    noms, signatures, parents = set(lu[0]), set(lu[1]), list(lu[2])
    vus = {classe}
    # Une profondeur bornee. La parente d'une classe de Minecraft se ramifie a
    # travers ses interfaces, et le graphe complet compte des centaines de noeuds
    # pour un gain nul : une methode visee par un mixin est declaree a deux ou
    # trois crans, jamais a douze.
    for _ in range(4):
        a_voir = [nom for nom in parents if nom not in vus]
        if not a_voir:
            break
        charge(a_voir, chemin)
        suivants = []
        for nom in a_voir:
            vus.add(nom)
            herite = _lues.get(nom)
            if herite is None:
                continue
            noms |= herite[0]
            signatures |= herite[1]
            suivants.extend(herite[2])
        parents = suivants
    return noms, signatures


def main():
    trouve = trouve_jar()
    if trouve is None:
        print("Aucun jar de Minecraft sous build/moddev/artifacts.")
        print("Lance d'abord « ./gradlew compileJava » : c'est lui qui le produit.")
        return 2
    base, chemin, complet = trouve
    print(f"Jar contrôlé : {pathlib.Path(base).name}")
    if complet:
        print("Chemin de classes complet : les cibles NeoForge sont contrôlées aussi.\n")
    else:
        print("Chemin de classes réduit au seul jar de Minecraft — les cibles")
        print("NeoForge seront annoncées introuvables. Lance « ./gradlew "
              "cheminDeClasses » pour lever cet angle mort.\n")
    jar = chemin

    # Les mixins réellement déclarés. Un fichier présent sur le disque mais absent
    # du manifeste ne s'applique pas — et l'inverse casse le démarrage.
    manifeste = json.loads((RACINE / "src" / "main" / "resources"
                            / "lanterne.mixins.json").read_text(encoding="utf-8"))
    declares = set(manifeste.get("mixins", [])) | set(manifeste.get("client", []))

    plaintes = []
    for fichier in sorted(MIXINS.glob("*.java")):
        texte = fichier.read_text(encoding="utf-8")
        table = imports(texte)
        classes = cibles_de_classe(texte, table)
        if not classes:
            continue

        if fichier.stem not in declares:
            if MARQUEUR_DESACTIVATION in texte:
                print(f"  · {fichier.name} : absent de lanterne.mixins.json, mais marqué "
                      f"« {MARQUEUR_DESACTIVATION} » — désactivation intentionnelle et "
                      f"documentée (voir sa javadoc), pas un oubli.")
            else:
                plaintes.append(f"{fichier.name} : absent de lanterne.mixins.json — "
                                f"ce mixin ne s'applique donc jamais.")
            continue

        # 1 et 2 : la classe visée, les noms de méthodes, et leurs signatures.
        connus = set()
        signatures = set()
        introuvable = False
        for classe in classes:
            lu = membres(classe, jar)
            if lu is None:
                plaintes.append(f"{fichier.name} : @Mixin vise « {classe} », "
                                f"classe introuvable dans le jar.")
                introuvable = True
                continue
            connus |= lu[0]
            signatures |= lu[1]
        if introuvable:
            continue

        noms, signes = noms_de_methodes(texte)
        for nom in noms:
            if nom.startswith("<") or nom.startswith("Lanterne"):
                continue
            if nom not in connus:
                plaintes.append(f"{fichier.name} : method = « {nom} » — "
                                f"absente de {', '.join(classes)}.")

        # Les cibles qui donnent leur signature entiere sont verifiees entierement.
        # C'est le controle qui manquait, et son absence a coute un ecran d'erreur
        # au lancement : voir la note de « noms_de_methodes ».
        for nom, signature in signes:
            if nom.startswith("<") or nom.startswith("Lanterne"):
                continue
            if (nom, signature) in signatures:
                continue
            if nom in connus:
                autres = sorted(s for (m, s) in signatures if m == nom)
                plaintes.append(f"{fichier.name} : method = « {nom} » existe, mais PAS avec la "
                                f"signature {signature}. Trouvee(s) : {'; '.join(autres)}")
            else:
                plaintes.append(f"{fichier.name} : method = « {nom} » — "
                                f"absente de {', '.join(classes)}.")

        # 3 : les appels enveloppés ou redirigés, avec leur signature entière.
        for classe, methode, signature in cibles_invoke(texte):
            # Un mixin peut parfaitement envelopper un appel a la bibliotheque
            # standard — « List.iterator », « ThreadLocal.get ». Le JDK n'est pas
            # dans le chemin de classes du mod, et le signaler introuvable serait
            # une fausse alerte de plus.
            if classe.startswith(HORS_PORTEE):
                continue
            lu = membres(classe, jar)
            if lu is None:
                plaintes.append(f"{fichier.name} : target vise « {classe} », "
                                f"classe introuvable dans le jar.")
                continue
            if (methode, signature) not in lu[1]:
                if methode in lu[0]:
                    plaintes.append(f"{fichier.name} : {classe}.{methode} existe, "
                                    f"mais pas avec la signature {signature}.")
                else:
                    plaintes.append(f"{fichier.name} : {classe}.{methode} "
                                    f"n'existe plus.")

    if plaintes:
        print(f"{len(plaintes)} cible(s) en défaut :\n")
        for plainte in plaintes:
            print(f"  ✗ {plainte}")
        print("\nChacune de ces lignes est un écran d'erreur au démarrage.")
        return 1

    print("Toutes les cibles de mixin existent dans le jar.")
    print("Rappel : cela ne prouve pas que les injections trouveront leur ancrage.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
