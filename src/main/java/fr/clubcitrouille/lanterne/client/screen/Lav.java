package fr.clubcitrouille.lanterne.client.screen;

/**
 * Le moteur recommandé : les bibliothèques de FFmpeg, appelées depuis Java.
 *
 * <h2>Pourquoi celui-ci, et pas un navigateur embarqué</h2>
 *
 * <p>Le relevé complet et chiffré est dans {@code notes/projecteur.md}. L'essentiel tient en quatre
 * points, et le premier suffirait :
 *
 * <ul>
 *   <li><b>Vingt-neuf mébioctets</b> de binaires pour Windows x86-64, contre cent quatre-vingts pour
 *       un Chromium embarqué — et quatre cent vingt-neuf une fois décompressé, contre rien du tout
 *       puisqu'un jar reste un jar. Sur un mod dont le sujet est la performance, l'écart n'est pas
 *       négociable.</li>
 *   <li><b>Aucun processus supplémentaire.</b> Un navigateur embarqué fait tourner quatre à six
 *       processus hors du tas de la machine virtuelle : augmenter {@code -Xmx} n'y change rien, et
 *       la mémoire manque au jeu. Une bibliothèque de décodage vit dans le processus du jeu.</li>
 *   <li><b>La licence tombe juste.</b> Le greffon de bytedeco est en Apache-2.0 <em>ou</em> GPLv2
 *       avec exception de chemin de classes, au choix du preneur. Les binaires publiés sans
 *       {@code --enable-gpl} sont en <b>LGPL v3</b> — {@code --enable-version3} est dans leur script
 *       de compilation — donc absorbables par la GPL-3.0-only de ce mod. Ceux avec
 *       {@code --enable-gpl} sont en GPL v3, absorbables aussi. Les deux variantes conviennent, ce
 *       qui est rare et vaut d'être noté.</li>
 *   <li><b>Le décodage matériel est là.</b> Les binaires publiés activent D3D11VA et DXVA2 sur
 *       Windows, VAAPI sur Linux, VideoToolbox sur macOS, CUDA et Vulkan partout où c'est possible.
 *       Une image décodée par la carte graphique ne coûte presque rien au processeur, ce qui est
 *       exactement ce qu'il faut à un mur qu'on regarde à soixante images par seconde.</li>
 * </ul>
 *
 * <h2>La dépendance à ffmpeg que ce dépôt vient de supprimer n'est pas celle-ci</h2>
 *
 * <p>La distinction est capitale et mérite d'être écrite, parce que la confondre ferait rejeter la
 * bonne solution pour les raisons de la mauvaise.
 *
 * <p>{@code content.disc.Detour} appelait le <b>programme</b> {@code ffmpeg}, celui qu'on installe
 * soi-même et qu'on met dans son {@code PATH}. Il a été retiré parce qu'il n'est installé chez
 * presque personne : la fonction ne marchait donc chez presque personne, et elle échouait après coup,
 * chez le joueur, au moment où il cliquait.
 *
 * <p>Ici, rien n'est demandé au joueur. Les binaires sont <b>versionnés, choisis par nous, et
 * apportés avec le mod</b> — soit dans l'archive, soit téléchargés une fois depuis le dépôt Maven
 * officiel. Le jour où ça marche chez l'un, ça marche chez tous, et la version est celle qu'on a
 * éprouvée. C'est la différence entre dépendre d'un programme absent et embarquer une bibliothèque
 * présente, et elle est de nature.
 *
 * <h2>Ce qui manque, et qu'aucune astuce ne remplace</h2>
 *
 * <ol>
 *   <li><b>La dépendance n'est pas déclarée</b> dans {@code build.gradle}. Trente mébioctets ajoutés
 *       à l'archive d'un mod de performance est une décision qui appartient à l'auteur du dépôt, pas
 *       à un chantier ; et elle se prend en même temps que le choix « embarqué ou téléchargé », qui
 *       change la taille publiée d'un facteur quatre.</li>
 *   <li><b>La pompe à images n'est pas écrite.</b> Ouvrir un {@code AVFormatContext}, trouver le flux
 *       vidéo, ouvrir l'{@code AVCodecContext}, négocier le contexte matériel, lire les paquets sur
 *       un fil de fond, convertir en RGBA et téléverser vers la texture — c'est le corps de
 *       {@link Reel}, et il ne s'écrit pas sans pouvoir l'exécuter une seule fois.</li>
 *   <li><b>Le son n'a pas de chemin.</b> Le moteur sonore de Minecraft joue des ressources, pas des
 *       tampons arbitraires. Y faire entrer un flux décodé en direct demande le même détour que les
 *       disques ont pris — un décodeur branché dans {@code SoundBufferLibrary} — mais avec une
 *       contrainte que les disques n'ont pas : rester en phase avec l'image.</li>
 * </ol>
 *
 * <p>Le premier point est un choix à faire, les deux autres sont du travail à faire. Aucun n'est un
 * obstacle de conception : la place est ici, la signature est celle de {@link Engine.Reel}, et
 * l'horloge partagée qui commande tout cela est déjà écrite et déjà juste.
 */
public final class Lav implements Engine {
    public static final Lav INSTANCE = new Lav();

    /**
     * La classe dont la présence prouve que les binaires sont là.
     *
     * <p>{@code avformat} et non {@code avcodec} : c'est elle qui ouvre les conteneurs, donc la
     * première dont on aurait besoin, donc la bonne à tester. Un test qui réussirait sur une
     * dépendance partielle serait pire qu'inutile — il ferait échouer plus tard, plus loin, et sur
     * un message moins clair.
     */
    private static final String PROBE = "org.bytedeco.ffmpeg.global.avformat";

    private Verdict verdict;

    private Lav() {}

    @Override
    public String label() {
        return "FFmpeg (bytedeco)";
    }

    @Override
    public Verdict verdict() {
        // Le consentement se relit à chaque appel : c'est un interrupteur que le joueur bascule en
        // cours de partie, et un refus qui n'aurait d'effet qu'au prochain lancement n'est pas un
        // refus. Le reste, lui, est mis en cache — une recherche de classe qui échoue coûte le
        // parcours du chemin de classes entier, et on ne la fait pas soixante fois par seconde.
        if (!Consent.remoteAllowed()) {
            return Verdict.SANS_CONSENTEMENT;
        }
        if (this.verdict == null) {
            this.verdict = present() ? Verdict.SANS_PONT : Verdict.SANS_BIBLIOTHEQUE;
        }
        return this.verdict;
    }

    /**
     * Le point de branchement.
     *
     * <p>Rend {@code null} en toutes circonstances tant que la pompe à images n'existe pas. Ce n'est
     * pas un bouchon : c'est la seule réponse vraie, et c'est elle qui fait tomber l'appelant sur
     * {@link Still}, qui affichera pourquoi.
     */
    @Override
    public Reel open(String source) {
        return null;
    }

    /** Les binaires de bytedeco sont-ils sur le chemin de classes ? */
    private static boolean present() {
        try {
            Class.forName(PROBE, false, Lav.class.getClassLoader());
            return true;
        } catch (Throwable absent) {
            return false;
        }
    }
}
