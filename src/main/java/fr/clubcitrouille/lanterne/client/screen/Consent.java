package fr.clubcitrouille.lanterne.client.screen;

import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Le consentement du joueur, et son plafond de dépense.
 *
 * <h2>Pourquoi ce réglage est un refus par défaut</h2>
 *
 * <p>Aller chercher une adresse, c'est la communiquer. Quand un joueur pose un lien sur un écran, ce
 * n'est pas le serveur qui va le chercher : c'est <b>chaque client qui regarde</b>. L'hôte distant
 * reçoit donc l'adresse IP de chaque spectateur. Voir {@code content.screen.Sieve} pour ce que cela
 * permet exactement, et à qui.
 *
 * <p>Un mod n'a pas à décider cela à la place de quelqu'un. Il peut le lui demander ; il ne peut pas
 * le supposer. Le défaut est donc <b>non</b>, et il l'est en sachant très bien ce que cela coûte :
 * un joueur qui installe le mod et pose un écran ne verra rien tant qu'il n'aura pas dit oui.
 *
 * <p>Ce n'est acceptable qu'à une condition, et elle est tenue : <b>l'écran dit pourquoi</b>. Il
 * affiche « médias distants refusés » et l'écran de réglages porte l'interrupteur, à un clic. Un
 * refus par défaut muet serait un défaut ; un refus par défaut qui s'explique et se lève en un geste
 * est un choix rendu au joueur.
 *
 * <h2>Le fichier est de type CLIENT, sans hésitation</h2>
 *
 * <p>C'est une décision personnelle, prise sur la machine de quelqu'un, à propos de son adresse IP.
 * Elle n'a rien à faire dans un fichier serveur, où un joueur en multijoueur ne pourrait pas
 * l'atteindre — c'est très exactement la leçon que {@code core.ClientConfig} a déjà tirée dans ce
 * dépôt.
 *
 * <h2>Les trois autres réglages ne sont pas du confort</h2>
 *
 * <p>Ils bornent la dépense, et ils existent parce que ce mod est un mod de performance. Un écran qui
 * décode soixante images par seconde et les téléverse vers la carte graphique est précisément le
 * genre de chose qui fait chuter le taux d'images ; huit écrans dans une salle le font huit fois. Le
 * plafond, la cadence et la distance sont les trois leviers, et ils sont réglables parce qu'une
 * machine de 2026 et une machine de 2014 n'ont pas la même réponse.
 */
public final class Consent {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue REMOTE;
    public static final ModConfigSpec.IntValue ACTIVE_CAP;
    public static final ModConfigSpec.IntValue FRAME_CAP;
    public static final ModConfigSpec.IntValue DISTANCE;
    public static final ModConfigSpec.EnumValue<fr.clubcitrouille.lanterne.content.screen.Grade> CEILING;

    public static final ModConfigSpec SPEC;

    static {
        BUILDER.comment(
                "Lanterne - projection, reglages personnels.",
                "",
                "Ce fichier n'engage que cette machine. Il n'est jamais envoye a un serveur, et aucun",
                "serveur ne peut le changer.").push("projection");

        REMOTE = BUILDER.comment(
                "Autoriser ce client a aller chercher les medias distants ?",
                "",
                "A FAUX (par defaut) : rien ne part de cette machine. Les ecrans affichent une",
                "ardoise qui dit pourquoi. Aucune adresse IP n'est communiquee a personne.",
                "",
                "A VRAI : ce client se connecte aux adresses posees sur les ecrans. L'hote distant",
                "connaitra alors votre adresse IP, comme n'importe quel site que vous visitez.",
                "La liste blanche du serveur limite a QUI cela peut arriver ; elle ne l'empeche pas.")
                .define("charger_medias_distants", false);

        ACTIVE_CAP = BUILDER.comment(
                "Nombre d'ecrans qui decodent en meme temps, au maximum.",
                "Les plus proches gagnent. Les autres gardent leur derniere image, ce qui ne coute",
                "rien du tout - une texture deja envoyee se redessine gratuitement.")
                .defineInRange("ecrans_actifs_max", 4, 1, 16);

        FRAME_CAP = BUILDER.comment(
                "Images par seconde demandees au decodeur, au maximum.",
                "30 suffit a toute video ordinaire. Monter a 60 double le travail de decodage et de",
                "televersement pour un gain que personne ne voit sur un mur a six blocs de distance.")
                .defineInRange("images_par_seconde_max", 30, 1, 120);

        CEILING = BUILDER.comment(
                "Resolution de decodage maximale que cette machine accepte.",
                "",
                "  auto    - suit la taille apparente du mur (defaut, et le bon choix)",
                "  basse   - 360p  : NEUF fois moins de pixels que 1080p",
                "  moyenne - 720p",
                "  haute   - 1080p",
                "  source  - aucun plafond",
                "",
                "Chaque ecran porte AUSSI son propre reglage de qualite, choisi par celui qui l'a",
                "pose. C'est le plus BAS des deux qui s'applique : un joueur sur petite machine peut",
                "donc se proteger sans rien degrader pour les autres, et sans que l'administrateur",
                "ait a trancher a sa place.")
                .defineEnum("qualite_max", fr.clubcitrouille.lanterne.content.screen.Grade.AUTO);

        DISTANCE = BUILDER.comment(
                "Distance au-dela de laquelle un ecran cesse de decoder, en blocs.",
                "Il reste visible et garde sa derniere image. A 64 blocs, un mur de 16 blocs de large",
                "occupe environ un quinzieme de l'ecran : on n'y distingue pas le mouvement.")
                .defineInRange("distance_max", 64, 8, 256);

        BUILDER.pop();
        SPEC = BUILDER.build();
    }

    private Consent() {}

    /**
     * Le joueur accepte-t-il que sa machine se connecte aux hôtes posés sur les écrans ?
     *
     * <p>Rend {@code false} tant que le fichier n'est pas lu. Se tromper dans ce sens ne coûte qu'une
     * ardoise pendant une seconde de démarrage ; se tromper dans l'autre ouvre une connexion que
     * personne n'a autorisée, et on ne la referme pas après coup.
     */
    public static boolean remoteAllowed() {
        return SPEC.isLoaded() && REMOTE.get();
    }

    /** Bascule le consentement, et l'écrit. Appelé par l'interrupteur de l'écran de réglages. */
    public static void allowRemote(boolean wanted) {
        if (!SPEC.isLoaded()) {
            return;
        }
        REMOTE.set(wanted);
        // Sans « save », le choix ne vaudrait que pour cette session — et un joueur qui a lu
        // l'avertissement et dit non le redirait à chaque lancement, ce qui finirait par le faire
        // dire oui par lassitude.
        SPEC.save();
    }

    public static int activeCap() {
        return SPEC.isLoaded() ? ACTIVE_CAP.get() : 4;
    }

    public static int frameCap() {
        return SPEC.isLoaded() ? FRAME_CAP.get() : 30;
    }

    public static int distance() {
        return SPEC.isLoaded() ? DISTANCE.get() : 64;
    }

    /**
     * Le plafond de résolution de cette machine.
     *
     * <p>Ne part jamais d'ici : il n'est envoyé à aucun serveur, et aucun serveur ne peut le lire.
     * C'est ce qui le distingue du réglage porté par l'écran, qui vaut pour tout le monde.
     */
    public static fr.clubcitrouille.lanterne.content.screen.Grade ceiling() {
        return SPEC.isLoaded() ? CEILING.get()
                : fr.clubcitrouille.lanterne.content.screen.Grade.AUTO;
    }

    /** Change le plafond, et l'écrit. Appelé par le bouton de l'écran de réglages. */
    public static void setCeiling(fr.clubcitrouille.lanterne.content.screen.Grade wanted) {
        if (!SPEC.isLoaded()) {
            return;
        }
        CEILING.set(wanted);
        SPEC.save();
    }

    public static void apply(ModConfigEvent event) {
        if (event.getConfig().getSpec() != SPEC) {
            return;
        }
        if (event.getConfig().getType() != ModConfig.Type.CLIENT) {
            return;
        }
        // Rien à recopier ailleurs : tout se lit à la demande. Ce gestionnaire existe pour que le
        // fichier soit chargé du tout — l'enregistrer sans écouter laisserait « isLoaded » à faux.
    }
}
