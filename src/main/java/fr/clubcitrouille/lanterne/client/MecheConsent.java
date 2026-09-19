package fr.clubcitrouille.lanterne.client;

import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Le consentement du joueur à laisser CE serveur écrire dans son dossier {@code mods/}.
 *
 * <h2>Pourquoi ce réglage est un refus par défaut</h2>
 *
 * <p>Même modèle que {@code client.screen.Consent}, pour une raison encore plus directe : charger un
 * média distant révèle une adresse IP ; ceci écrit un fichier exécutable sur le disque du joueur. Un
 * mod n'a pas à décider cela à sa place. Le serveur peut proposer — {@code MecheManifest} — le client
 * seul décide d'accepter.
 *
 * <p>Refusé, rien ne change à ce que ce mécanisme faisait avant d'exister : le serveur annonce son
 * empreinte, le client la compare, et s'il diffère il se contente de le DIRE dans la discussion — un
 * message, pas un octet téléchargé. Voir {@code client.MecheClient#acceptManifest}.
 *
 * <h2>Ce que ce fichier n'est PAS</h2>
 *
 * <p>Il n'autorise en rien l'exécution de quoi que ce soit. Le jar téléchargé n'est ni ouvert, ni
 * chargé, ni lancé par ce mod — seulement écrit sur le disque sous un nom qui ne peut entrer en
 * collision avec le jar en cours d'exécution, en attendant que le joueur relance lui-même le jeu.
 */
public final class MecheConsent {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue AUTO_UPDATE;

    public static final ModConfigSpec SPEC;

    static {
        BUILDER.comment(
                "Lanterne - la mise a jour automatique, reglage personnel.",
                "",
                "Ce fichier n'engage que cette machine. Il n'est jamais envoye a un serveur, et aucun",
                "serveur ne peut le changer.").push("maj");

        AUTO_UPDATE = BUILDER.comment(
                "Autoriser ce client a telecharger et installer les mises a jour de Lanterne",
                "annoncees par le serveur auquel il se connecte ?",
                "",
                "A FAUX (par defaut) : rien n'est telecharge. Si le serveur fait tourner une autre",
                "version, ce client se contente de le DIRE dans la discussion.",
                "",
                "A VRAI : quand l'empreinte annoncee par le serveur differe de celle du jar installe",
                "ici, ce client le telecharge, verifie son empreinte, puis l'ecrit dans mods/ SOUS UN",
                "NOM DIFFERENT du jar en cours - jamais de remplacement a chaud. Il tente ensuite de",
                "supprimer l'ancien jar ; si l'OS refuse (le jar tournant est verrouille sur Windows",
                "tant que le jeu n'est pas ferme), un message dit exactement quoi faire a la main",
                "avant de relancer.",
                "",
                "Le fichier recu ne vient JAMAIS d'ailleurs que du serveur auquel ce client est deja",
                "connecte : aucune adresse distante n'est jamais contactee par ce mecanisme.")
                .define("auto_maj", false);

        BUILDER.pop();
        SPEC = BUILDER.build();
    }

    private MecheConsent() {}

    /** Le joueur accepte-t-il que ce client télécharge une mise à jour proposée par le serveur ? */
    public static boolean autoUpdate() {
        return SPEC.isLoaded() && AUTO_UPDATE.get();
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
