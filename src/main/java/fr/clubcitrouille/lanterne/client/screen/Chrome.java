package fr.clubcitrouille.lanterne.client.screen;

import java.util.List;

/**
 * Le second moteur : un Chromium embarqué, s'il se trouve déjà installé.
 *
 * <h2>Ce qu'il apporte, et qu'aucune bibliothèque de décodage n'apportera</h2>
 *
 * <p>Il ne joue pas un fichier : il ouvre une <b>page</b>. Toute la différence est là. Un hébergeur
 * de vidéo qui ne sert pas de fichier — c'est-à-dire tous les grands — devient accessible, avec son
 * lecteur, ses sous-titres, ses pistes audio et sa publicité. Et il y devient accessible <b>sans rien
 * contourner</b> : le client se comporte en navigateur, ce qu'il est.
 *
 * <p>C'est le seul point où il gagne, et il est réel. L'autre voie — extraire l'adresse du flux par
 * un outil tiers — demande de contourner en permanence des mesures que l'hébergeur remet en place, ce
 * qui est à la fois une course perdue d'avance et le seul endroit de tout ce chantier où l'on
 * franchirait une ligne juridique nette. Ce dépôt ne la franchira pas, et c'est aussi pourquoi
 * l'extraction n'est pas un moteur candidat.
 *
 * <h2>Ce qu'il coûte, chiffres en main</h2>
 *
 * <ul>
 *   <li><b>Cent quatre-vingts mébioctets</b> téléchargés au premier lancement, sur Windows x86-64 —
 *       et <b>quatre cent vingt-neuf</b> une fois décompressés sur le disque du joueur. Sur Linux,
 *       cinq cent cinquante-huit.</li>
 *   <li><b>Quatre à six processus</b> qui vivent hors du tas de la machine virtuelle, dont un dédié
 *       au rendu. Aucun réglage de mémoire du lanceur ne les concerne : ils prennent la mémoire du
 *       système, celle qui manquait déjà.</li>
 *   <li>Une lignée de forks où <b>des processus survivent à la fermeture du jeu</b> — le défaut est
 *       ouvert depuis novembre 2023 en amont, et n'a reçu qu'un contournement.</li>
 * </ul>
 *
 * <p>Pour un mod dont la raison d'être est de rendre le jeu plus léger, faire démarrer un navigateur
 * complet pour afficher une vidéo est une contradiction qu'aucun chiffre ne rachète. D'où l'ordre de
 * {@code Engine.CANDIDATES} : {@link Lav} d'abord, celui-ci seulement s'il est <b>déjà là</b>.
 *
 * <h2>Déjà là, et jamais installé par nous</h2>
 *
 * <p>Ce moteur ne télécharge rien, ne dépose rien, et n'ajoute aucune dépendance à l'archive. Il
 * regarde si un mod compagnon fournissant un Chromium embarqué est chargé, et s'en sert le cas
 * échéant. Un joueur qui n'en veut pas n'a rien à refuser : il lui suffit de ne pas l'installer.
 *
 * <p>C'est aussi ce qui garde la question de licence simple. Les forks vivants de ce mod sont en
 * LGPL-2.1-<em>or-later</em>, ce qui se marierait avec la GPL-3.0-only de ce dépôt — la clause
 * « or later » est ce qui le permet, et une LGPL-2.1-only ne le permettrait pas. Mais la question ne
 * se pose pas : rien de leur code n'entre ici, et l'appel se fait par réflexion sur un mod que le
 * joueur a installé de son côté.
 *
 * <h2>Ce qui manque</h2>
 *
 * <ol>
 *   <li><b>Le pont n'est pas écrit.</b> Créer un navigateur hors écran, lui donner une taille, lui
 *       charger une adresse, recevoir ses images et les téléverser — le mécanisme amont est un
 *       {@code onPaint} qui rend un tampon BGRA et des rectangles sales.</li>
 *   <li><b>L'horloge ne commande pas un navigateur.</b> Un lecteur web tient sa propre position et ne
 *       la cède pas : on la pilote par injection de JavaScript — « va à telle seconde », « mets en
 *       pause » — ce qui est indirect, asynchrone, et dépendant de la page. La synchronisation au
 *       dixième de seconde que {@code Clock} rend possible avec un décodeur direct ne peut pas être
 *       tenue ici ; elle serait de l'ordre de la demi-seconde. <b>Cela doit être annoncé et non
 *       découvert</b>, parce que c'est très exactement la fonction que l'on demande.</li>
 *   <li><b>Le nom de la classe compagnon n'est pas certain.</b> La lignée a changé de nom au moins
 *       une fois ; on sonde donc plusieurs noms plutôt qu'un seul, et l'on préfère un relevé qui se
 *       trompe en disant « absent » à un relevé qui se trompe en disant « présent ».</li>
 * </ol>
 */
public final class Chrome implements Engine {
    public static final Chrome INSTANCE = new Chrome();

    /**
     * Les noms sous lesquels un Chromium embarqué a pu s'installer.
     *
     * <p>Plusieurs, et non un seul : la lignée compte un ancêtre abandonné, un fork longtemps de
     * référence et gelé, et un fork actif qui a été renommé. Sonder un seul nom reviendrait à
     * déclarer « absent » un moteur parfaitement présent, et le joueur n'aurait aucun moyen de
     * comprendre pourquoi.
     */
    private static final List<String> PROBES = List.of(
            "com.cinemamod.mcef.MCEF",
            "de.keksuccino.rinku.Rinku",
            "net.montoyo.mcef.api.API");

    private Verdict verdict;

    private Chrome() {}

    @Override
    public String label() {
        return "Chromium embarqué";
    }

    @Override
    public Verdict verdict() {
        if (!Consent.remoteAllowed()) {
            return Verdict.SANS_CONSENTEMENT;
        }
        if (this.verdict == null) {
            this.verdict = present() ? Verdict.SANS_PONT : Verdict.SANS_MOD;
        }
        return this.verdict;
    }

    /**
     * Le point de branchement.
     *
     * <p>Rend {@code null} tant que le pont n'existe pas — voir l'en-tête. La signature est celle de
     * {@link Lav}, et c'est l'intérêt de l'interface : le jour où l'un des deux est écrit, rien
     * d'autre ne bouge dans le mod.
     */
    @Override
    public Reel open(String source, int wantedHeight) {
        return null;
    }

    private static boolean present() {
        for (String name : PROBES) {
            try {
                Class.forName(name, false, Chrome.class.getClassLoader());
                return true;
            } catch (Throwable absent) {
                // Ce nom-là n'est pas celui du mod installé, ou aucun mod n'est installé. Les deux
                // se traitent pareil, et ni l'un ni l'autre n'est une anomalie à journaliser.
            }
        }
        return false;
    }
}
