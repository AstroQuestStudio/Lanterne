package fr.clubcitrouille.lanterne.client.screen;

import fr.clubcitrouille.lanterne.Lanterne;

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
 * <h2>Comment il s'installe</h2>
 *
 * <p>Les liaisons Java — 832 Kio, du Java pur — sont dans l'archive du mod. Les bibliothèques, elles,
 * se téléchargent une fois depuis Maven Central, sur consentement, et se vérifient par empreinte
 * SHA-256 inscrite dans le code. Voir {@link Fetch}, et l'épreuve autonome
 * {@code tools/EssaiLav.java} qui a établi que le chargeur de natifs sait les trouver sur le disque
 * sans chargeur de classes maison.
 *
 * <h2>Ce qui reste à voir tourner</h2>
 *
 * <p>Le décodage a été exécuté hors de Minecraft : un H.264 ouvert, converti en RGBA, pixels
 * vérifiés, horodatages corrects, les bibliothèques chargées depuis un dossier et non depuis le
 * chemin de classes. C'est la partie qui pouvait ne pas marcher du tout.
 *
 * <p><b>Le téléversement vers la carte graphique n'a pas pu l'être</b> : il demande un contexte
 * graphique, donc le jeu lancé, et ce chantier n'a pas le droit de le lancer. <b>Le son non plus</b>
 * — voir {@link Airwave} pour le chemin, qui existe et qui est celui des disques.
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

    private Lav() {}

    @Override
    public String label() {
        return "FFmpeg (bytedeco)";
    }

    /**
     * Tout est-il réuni ?
     *
     * <p>Rien n'est mis en cache, et c'est voulu : les trois réponses possibles changent en cours de
     * partie. Le consentement est un interrupteur que le joueur bascule pendant qu'il regarde un
     * écran, et l'installation des binaires se termine sur un fil de fond. Un verdict figé au
     * démarrage ferait attendre le prochain lancement du jeu pour l'un comme pour l'autre.
     *
     * <p>Les trois tests coûtent une lecture de champ chacun — {@link Fetch} garde son état dans une
     * référence atomique, et la recherche de classe, elle, est mise en cache par le chargeur.
     */
    @Override
    public Verdict verdict() {
        if (!Consent.remoteAllowed()) {
            return Verdict.SANS_CONSENTEMENT;
        }
        if (!bindings()) {
            // Les liaisons sont embarquées : ne pas les trouver veut dire que jarJar n'a pas fait
            // son travail, et c'est une panne d'empaquetage, pas d'installation.
            return Verdict.SANS_PONT;
        }
        return Fetch.state() == Fetch.State.PRET ? Verdict.PRET : Verdict.SANS_BIBLIOTHEQUE;
    }

    /**
     * Ouvre une source.
     *
     * <p>Aucune exception ne sort d'ici : l'appelant est le fil de rendu, et une bobine qui refuse de
     * s'ouvrir doit donner une ardoise, pas un plantage. {@link Pump} attrape tout de son côté aussi
     * — la double garde n'est pas de la superstition, les deux fils peuvent échouer séparément.
     */
    @Override
    public Reel open(String source, int wantedHeight) {
        if (verdict() != Verdict.PRET) {
            return null;
        }
        try {
            return Pump.open(source, wantedHeight);
        } catch (Throwable refused) {
            Lanterne.LOG.warn("[PROJECTION] ouverture impossible : {}", String.valueOf(refused));
            return null;
        }
    }

    /**
     * Les liaisons Java sont-elles là ?
     *
     * <p>{@code initialize = false} est indispensable. Initialiser une classe de bytedeco ferait
     * tourner l'initialiseur statique de {@code Loader}, qui lit les propriétés de chemin — et à ce
     * moment-là {@link Fetch} ne les a peut-être pas encore posées. Le relevé deviendrait alors la
     * cause de la panne qu'il est censé constater.
     */
    private static boolean bindings() {
        try {
            Class.forName(PROBE, false, Lav.class.getClassLoader());
            return true;
        } catch (Throwable absent) {
            return false;
        }
    }
}
