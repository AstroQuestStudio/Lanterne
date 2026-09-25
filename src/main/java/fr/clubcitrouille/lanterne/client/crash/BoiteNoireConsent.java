package fr.clubcitrouille.lanterne.client.crash;

import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Le consentement du joueur à l'envoi automatique de ses rapports de plantage aux admins du serveur
 * Lanterne auquel il se connecte.
 *
 * <h2>Même modèle que {@code client.screen.Consent}, pour la même raison</h2>
 *
 * <p>Un crash-report ou un {@code hs_err_pid*.log} contient des chemins de disque, parfois le nom de
 * compte Windows du joueur, la liste de ses mods installés. Ce n'est pas anodin, même une fois
 * anonymisé — voir {@link fr.clubcitrouille.lanterne.client.crash.CrashSummary} pour ce qui l'est
 * réellement. Un mod n'a pas à décider à la place du joueur qu'un texte qui le concerne parte vers un
 * tiers. Le défaut est donc <b>non</b>, et on ne le redemande qu'une fois, jusqu'à réponse.
 *
 * <h2>Fichier CLIENT, séparé, comme ses cousins</h2>
 *
 * <p>Trois fichiers du même genre existent déjà dans ce dépôt — {@code client.screen.Consent} (médias
 * distants), {@code client.MecheConsent} (mise à jour automatique) — chacun pour une décision
 * personnelle distincte, jamais mélangées dans le même fichier ni dans celui du rendu
 * ({@link fr.clubcitrouille.lanterne.core.ClientConfig}). Celui-ci suit la même règle : un plantage
 * n'a rien à voir avec un mur vidéo ou un jar téléchargé, et regrouper ces trois consentements sous un
 * même interrupteur aurait fait dire « oui » à l'un en croyant répondre à l'autre.
 */
public final class BoiteNoireConsent {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue ENVOYER;
    public static final ModConfigSpec.BooleanValue REPONDU;
    public static final ModConfigSpec.BooleanValue ENVOYER_PROFILS;
    public static final ModConfigSpec.BooleanValue PROFILS_REPONDU;

    public static final ModConfigSpec SPEC;

    static {
        BUILDER.comment(
                "Lanterne - la boite noire, reglage personnel.",
                "",
                "Ce fichier n'engage que cette machine. Il n'est jamais envoye a un serveur, et aucun",
                "serveur ne peut le changer.").push("boite_noire");

        ENVOYER = BUILDER.comment(
                "Envoyer automatiquement les rapports de plantage de cette machine aux admins du",
                "serveur Lanterne auquel elle se connecte ?",
                "",
                "A FAUX (par defaut) : rien ne part jamais d'ici tout seul.",
                "",
                "A VRAI : au prochain lancement d'un plantage non encore envoye (crash-reports/,",
                "hs_err_pid*.log a la racine du dossier de jeu), ce client transmet un extrait",
                "tronque et anonymise (chemins \"C:\\Users\\<nom>\" remplaces par \"~\") au serveur",
                "auquel il vient de se connecter, qui l'ecrit dans son dossier logs/lanterne-plantages/.")
                .define("envoyer_automatiquement", false);

        REPONDU = BUILDER.comment(
                "Le joueur a-t-il deja repondu a la question de la boite noire ?",
                "A FAUX et envoyer_automatiquement a FAUX : on la posera une fois, a la prochaine",
                "connexion a un serveur Lanterne ou un plantage non envoye est trouve. A VRAI : refus",
                "ou accord retenu, on ne redemande plus (cet ecran de reglages permet de changer d'avis).")
                .define("boite_noire_repondu", false);

        ENVOYER_PROFILS = BUILDER.comment(
                "Envoyer automatiquement les PROFILS DE PERFORMANCE (JFR : methodes chaudes, pauses",
                "GC, allocations...) de cette machine, environ toutes les 60 minutes de jeu en monde,",
                "au serveur Lanterne auquel elle est connectee ?",
                "",
                "A FAUX (par defaut) : rien ne part jamais d'ici tout seul. Une copie locale reste",
                "toujours ecrite dans config/lanterne/profils/, que ce reglage soit actif ou non.",
                "",
                "A VRAI : chaque rapport tronque (~200 Ko) part vers le serveur, qui l'ecrit dans son",
                "dossier logs/lanterne-profils/. Reglage independant de « envoyer_automatiquement »",
                "ci-dessus : un profil de performance ne contient ni chemin de disque ni nom de compte,",
                "mais reste une decision separee, jamais devinee a la place du joueur.")
                .define("envoyer_profils_automatiquement", false);

        PROFILS_REPONDU = BUILDER.comment(
                "Le joueur a-t-il deja repondu a la question des profils de performance ?",
                "Meme logique que boite_noire_repondu ci-dessus, pour la question des profils : posee",
                "une fois (souvent en meme temps que celle des plantages, en une seule question si",
                "aucune des deux n'a encore de reponse), jamais reposee ensuite.")
                .define("profils_repondu", false);

        BUILDER.pop();
        SPEC = BUILDER.build();
    }

    private BoiteNoireConsent() {}

    /** Le joueur accepte-t-il l'envoi automatique de ses rapports de plantage ? */
    public static boolean allowed() {
        return SPEC.isLoaded() && ENVOYER.get();
    }

    /** Le joueur a-t-il déjà répondu, dans un sens ou dans l'autre ? */
    public static boolean answered() {
        return SPEC.isLoaded() && REPONDU.get();
    }

    /** Bascule le consentement, et l'écrit. Appelé par la question posée à la connexion, ou l'écran des réglages. */
    public static void setAllowed(boolean wanted) {
        if (!SPEC.isLoaded()) {
            return;
        }
        ENVOYER.set(wanted);
        REPONDU.set(true);
        SPEC.save();
    }

    /** Le joueur accepte-t-il l'envoi automatique de ses profils de performance ? */
    public static boolean profilsAllowed() {
        return SPEC.isLoaded() && ENVOYER_PROFILS.get();
    }

    /** Le joueur a-t-il déjà répondu à la question des profils de performance, dans un sens ou dans l'autre ? */
    public static boolean profilsAnswered() {
        return SPEC.isLoaded() && PROFILS_REPONDU.get();
    }

    /** Bascule le consentement des profils de performance, et l'écrit — même geste que {@link #setAllowed}. */
    public static void setProfilsAllowed(boolean wanted) {
        if (!SPEC.isLoaded()) {
            return;
        }
        ENVOYER_PROFILS.set(wanted);
        PROFILS_REPONDU.set(true);
        SPEC.save();
    }

    public static void apply(ModConfigEvent event) {
        if (event.getConfig().getSpec() != SPEC) {
            return;
        }
        if (event.getConfig().getType() != ModConfig.Type.CLIENT) {
            return;
        }
        // Rien à recopier ailleurs : tout se lit à la demande, comme client.screen.Consent.
    }
}
