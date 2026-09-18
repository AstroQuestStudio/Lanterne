package fr.clubcitrouille.lanterne.client.portals;

import net.neoforged.fml.ModList;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * PORTAILS IMMERSIFS : détection du compagnon, et rien de plus.
 *
 * <h2>Pourquoi ce fichier n'a qu'une méthode</h2>
 *
 * <p>Voir {@code notes/immersive-portals-faisabilite.md} pour l'enquête complète. Verdict court :
 * aucun jar réel d'Immersive Portals n'existe pour ce moteur (« Minecraft 26.3 »,
 * {@code com.mojang.renderpearl.backend.vulkan}), et l'architecture que le mod suppose partout où
 * il a jamais existé — Fabric, Forge, NeoForge — n'a pas d'équivalent ici : un rendu de portail
 * récursif obtenu en réentrant dans le rendu de niveau avec un état GL mutable (pochoir, plan de
 * coupe) posé et retiré à la volée, sur des classes (`LevelRenderer`, `RenderSystem`) que ce
 * moteur a soit scindées (`LevelExtractor`), soit remplacées par une abstraction déclarative
 * (`GpuDevice`/`CommandEncoder`/`RenderPipeline`) qui fige ses pipelines à la construction. Rien
 * n'est donc branché : ni mixin, ni renderer, ni garde de coexistence avec le Voile ou la
 * lentille temporelle — il n'y a rien à leur faire cohabiter avec un rendu qui n'existe pas dans
 * ce build.
 *
 * <h2>Les identifiants de mod ne sont pas confirmés par un jar réel</h2>
 *
 * <p>Contrairement à {@code report.Compagnons} (Sodium, Distant Horizons, JourneyMap, Jade, JEI),
 * dont chaque identifiant a été lu dans un jar réellement chargé sur cette machine, aucun jar
 * d'Immersive Portals n'a pu être obtenu pour ce moteur — il n'existe pas pour « Minecraft 26.3 ».
 * Les identifiants ci-dessous viennent de la lecture publique des dépôts
 * (qouteall/ImmersivePortalsMod, iPortalTeam/ImmersivePortalsModForNeo, le port Modrinth de
 * Nick1st) et sont donc SUPPOSÉS, pas VÉRIFIÉS au sens où ce dépôt l'entend d'habitude ailleurs —
 * marqués ainsi pour qu'on ne les confonde jamais avec une certitude tirée du bytecode.
 */
public final class Portail {

    /** SUPPOSÉ : identifiant historique du jar « all-in-one » (Fabric/Forge/NeoForge). */
    private static final String ID_PRINCIPAL = "immersive_portals";

    /** SUPPOSÉ : identifiant du module cœur, séparé dans certaines versions Fabric anciennes. */
    private static final String ID_CORE = "imm_ptl_core";

    private Portail() {}

    /**
     * Appelée une fois au démarrage du client, uniquement si {@link PortailsConfig#ACTIVE} est
     * vrai. N'installe rien, ne mixine rien : journalise, et s'arrête là.
     */
    public static void verifie() {
        if (!PortailsConfig.ACTIVE.get()) {
            return;
        }
        if (detecte()) {
            Lanterne.LOG.info(
                    "[LANTERNE][PORTAILS] Immersive Portals detecte, mais AUCUNE integration n'est "
                    + "branchee cette passe — voir notes/immersive-portals-faisabilite.md. Ce "
                    + "compagnon fonctionne de son cote, sans coordination ni garde particuliere "
                    + "de notre part : rien dans Lanterne ne le sait actif au-dela de cette ligne.");
        } else {
            Lanterne.LOG.info(
                    "[LANTERNE][PORTAILS] reglage active, mais Immersive Portals n'est pas installe "
                    + "— et de toute facon, aucune version reelle du mod n'existe pour ce moteur "
                    + "(\"Minecraft 26.3\", NeoForge 26.3.0.3-beta) : voir "
                    + "notes/immersive-portals-faisabilite.md pour l'enquete complete.");
        }
    }

    private static boolean detecte() {
        try {
            return ModList.get().isLoaded(ID_PRINCIPAL) || ModList.get().isLoaded(ID_CORE);
        } catch (Throwable tooEarly) {
            return false;
        }
    }
}
