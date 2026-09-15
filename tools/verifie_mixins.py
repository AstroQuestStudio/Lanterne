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
    """Les « method = "..." » ; une valeur peut porter plusieurs noms séparés par des virgules."""
    noms = set()
    for brut in re.findall(r'method\s*=\s*((?:"(?:[^"\\]|\\.)*"\s*\+?\s*)+)', texte):
        assemble = "".join(re.findall(r'"((?:[^"\\]|\\.)*)"', brut))
        for morceau in assemble.split(","):
            morceau = morceau.strip()
            if not morceau:
                continue
            # « nom(Ljava/lang/Object;)V » -> on ne garde que le nom : la
            # signature d'une méthode visée par « method » peut être partielle.
            noms.add(morceau.split("(")[0].strip())
    return noms


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


def lit_une_classe(classe, chemin):
    """Ce que « javap » sait d'une classe : ses méthodes, et sa parenté."""
    issue = subprocess.run(["javap", "-p", "-s", "-cp", chemin, classe],
                           capture_output=True, text=True, errors="replace")
    if issue.returncode != 0:
        return None
    noms, signatures, parents = set(), set(), []
    dernier = None
    for ligne in issue.stdout.splitlines():
        nue = ligne.strip()
        if " extends " in nue or " implements " in nue:
            for mot in re.findall(r"(?:extends|implements)\s+([\w.$,<>\s]+?)\s*\{", nue):
                for nom in mot.split(","):
                    nom = re.sub(r"<.*?>", "", nom).strip()
                    if nom and nom != "java.lang.Object":
                        parents.append(nom)
        forme = re.search(r"([\w<>$]+)\s*\([^)]*\)\s*;?\s*$", nue)
        if forme and not nue.startswith("descriptor:"):
            dernier = forme.group(1)
            noms.add(dernier)
        elif nue.startswith("descriptor:") and dernier:
            signatures.add((dernier, nue.split(":", 1)[1].strip()))
    return noms, signatures, parents


def membres(classe, chemin):
    """Les méthodes d'une classe ET de tout ce dont elle hérite.

    Remonter la parenté n'est pas un raffinement, c'est une nécessité. « javap »
    ne montre que les membres déclarés ; or un mixin vise très légitimement une
    méthode héritée. « LivingEntityMixin » vise ainsi « isEffectiveAi », qui est
    déclarée dans « Entity ». Sans cette remontée, l'outil rendrait « n'existe
    plus » sur un mixin parfaitement sain — et un contrôleur qui crie au loup se
    fait débrancher au bout de deux fois.

    Rend None si la classe elle-même est introuvable ; les parents introuvables,
    eux, sont ignorés en silence (le JDK n'est pas dans le chemin de classes, et
    « java.lang.Object » n'apprendrait rien).
    """
    if classe in _cache:
        return _cache[classe]
    lu = lit_une_classe(classe, chemin)
    if lu is None:
        _cache[classe] = None
        return None
    noms, signatures, parents = lu
    vus = {classe}
    a_voir = list(parents)
    while a_voir:
        parent = a_voir.pop()
        if parent in vus:
            continue
        vus.add(parent)
        herite = lit_une_classe(parent, chemin)
        if herite is None:
            continue
        noms |= herite[0]
        signatures |= herite[1]
        a_voir.extend(herite[2])
    _cache[classe] = (noms, signatures)
    return _cache[classe]


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
            plaintes.append(f"{fichier.name} : absent de lanterne.mixins.json — "
                            f"ce mixin ne s'applique donc jamais.")
            continue

        # 1 et 2 : la classe visée, et les noms de méthodes qu'on y cherche.
        connus = set()
        introuvable = False
        for classe in classes:
            lu = membres(classe, jar)
            if lu is None:
                plaintes.append(f"{fichier.name} : @Mixin vise « {classe} », "
                                f"classe introuvable dans le jar.")
                introuvable = True
                continue
            connus |= lu[0]
        if introuvable:
            continue

        for nom in noms_de_methodes(texte):
            if nom.startswith("<") or nom.startswith("Lanterne"):
                continue
            if nom not in connus:
                plaintes.append(f"{fichier.name} : method = « {nom} » — "
                                f"absente de {', '.join(classes)}.")

        # 3 : les appels enveloppés ou redirigés, avec leur signature entière.
        for classe, methode, signature in cibles_invoke(texte):
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
