package fr.clubcitrouille.lanterne.client.reglage;

import java.util.Locale;
import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.clock.WorldClocks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * Le scénario de vérification des réglages : il prouve l'effet de chacun, par le log et par une image.
 *
 * <p>Actif seulement avec {@code -Dlanterne.reglage.scene=<nom>} (jamais chez un joueur). Une capture du
 * banc prise sur une scène qui ne montre pas ce que le réglage change ne prouve rien — c'est ce qui
 * s'est produit avec le premier essai (deux captures identiques face à un tronc). Ce scénario place donc
 * la caméra, force la scène et écrit, pour chaque réglage, ce qui est réellement appliqué.
 *
 * <p>Noms de scène :
 * <ul>
 *   <li>{@code ciel} : regard levé vers le couchant, soleil dans le champ ;</li>
 *   <li>{@code meteo} : pluie forcée, regard vers l'horizon puis levé ;</li>
 *   <li>{@code particules} : rafales de particules (explosion, fumée, feuilles) devant la caméra ;</li>
 *   <li>{@code entites} : un cadre, une peinture et un support d'armure devant la caméra, à distance
 *       choisie par {@code -Dlanterne.reglage.scene.distance=<blocs>}.</li>
 * </ul>
 *
 * <p>Le log reçoit une ligne {@code [REGLAGES]} par réglage lu, avec sa valeur EFFECTIVE (celle que les
 * hooks utilisent : propriété JVM, sinon fichier), pour qu'un job « functional » prouve l'application
 * du réglage sans capture parlante.
 */
@EventBusSubscriber(modid = Lanterne.ID, value = Dist.CLIENT)
public final class ReglageScene {
    private static final String NOM = System.getProperty("lanterne.reglage.scene", "");
    private static final boolean ACTIVE = !NOM.isEmpty();
    private static final int DISTANCE = Integer.getInteger("lanterne.reglage.scene.distance", 12);

    private static boolean rapporte;
    private static boolean installee;
    private static int ticks;
    private static int attente;

    private ReglageScene() {}

    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        if (!ACTIVE) {
            return;
        }
        try {
            Minecraft mc = Minecraft.getInstance();
            if (!rapporte) {
                rapporte = true;
                rapporter();
            }
            MinecraftServer serveur = mc.getSingleplayerServer();
            if (mc.level == null || mc.player == null || serveur == null) {
                return;
            }
            mc.getFramerateLimitTracker().onInputReceived();
            if (!installee) {
                if (++attente < 60) {
                    return;
                }
                installee = true;
                serveur.execute(() -> installer(serveur));
                return;
            }
            ticks++;
            if ("particules".equals(NOM) && ticks % 10 == 0) {
                serveur.execute(() -> particules(serveur));
            }
            if ("entites".equals(NOM) && ticks == 5) {
                serveur.execute(() -> entites(serveur));
            }
        } catch (Throwable echec) {
            Lanterne.LOG.warn("[REGLAGES] scène « {} » interrompue : {}", NOM, echec.toString());
        }
    }

    /** Une ligne par réglage : la valeur EFFECTIVE, celle que les hooks lisent. */
    private static void rapporter() {
        Reglages.refresh();
        StringBuilder appliques = new StringBuilder();
        for (Reglages.Reglage reglage : Reglages.tous()) {
            if (!reglage.estDefaut()) {
                appliques.append(' ').append(reglage.id()).append('=').append(reglage.valeur());
            }
        }
        Lanterne.LOG.info("[REGLAGES] scène={} ; réglages écartés du défaut :{}", NOM,
                appliques.length() == 0 ? " aucun" : appliques);
        Lanterne.LOG.info("[REGLAGES] cache des hooks : particulesActives={} meteoCoupee={} astresCoupes={}"
                        + " etoilesCoupees={} blocs={} plaques={} textures={} fpsHorsFocus={} cadres={} supports={}",
                Reglages.particulesActives, Reglages.meteoCoupee, Reglages.astresCoupes,
                Reglages.etoilesCoupees, Reglages.blocsDistanceCarre, Reglages.plaquesDistance,
                Reglages.texturesMode, Reglages.fpsHorsFocus, Reglages.cadresDistanceCarre,
                Reglages.armorStandsDistanceCarre);
    }

    /** Fige l'horloge du monde à {@code ticks} (même méthode que le mode banc du bilan). */
    private static void heure(MinecraftServer serveur, long ticks) {
        var horloge = serveur.registryAccess().lookupOrThrow(Registries.WORLD_CLOCK)
                .getOrThrow(WorldClocks.OVERWORLD);
        if (serveur.overworld().clockManager() instanceof net.minecraft.world.clock.ServerClockManager gestion) {
            gestion.setTotalTicks(horloge, ticks);
            gestion.setPaused(horloge, true);
        }
    }

    private static void installer(MinecraftServer serveur) {
        try {
            var regles = serveur.getGameRules();
            regles.set(net.minecraft.world.level.gamerules.GameRules.ADVANCE_TIME, false, serveur);
            regles.set(net.minecraft.world.level.gamerules.GameRules.ADVANCE_WEATHER, false, serveur);
            var commandes = serveur.getCommands();
            var source = serveur.createCommandSourceStack().withSuppressedOutput();
            if (serveur.getPlayerList().getPlayers().isEmpty()) {
                return;
            }
            ServerPlayer joueur = serveur.getPlayerList().getPlayers().get(0);
            ServerLevel monde = joueur.level() instanceof ServerLevel niveau ? niveau : serveur.overworld();
            float yaw = 270f;
            float pitch = -3f;
            switch (NOM) {
                case "ciel" -> {
                    // Le couchant est à l'ouest (yaw 270) ; heure 12500 : soleil bas, à peine sous l'horizon
                    // visible ; on lève le regard de 25° pour l'avoir dans le champ.
                    heure(serveur, 12000L);
                    commandes.performPrefixedCommand(source, "weather clear");
                    pitch = -20f;
                }
                case "meteo" -> {
                    heure(serveur, 6000L);
                    commandes.performPrefixedCommand(source, "weather rain 100000");
                    pitch = -5f;
                }
                case "particules", "entites" -> {
                    heure(serveur, 6000L);
                    commandes.performPrefixedCommand(source, "weather clear");
                    pitch = 5f;
                }
                default -> commandes.performPrefixedCommand(source, "weather clear");
            }
            joueur.teleportTo(monde, joueur.getX(), joueur.getY(), joueur.getZ(), Set.of(), yaw, pitch, true);
            joueur.setNoGravity(true);
            joueur.setDeltaMovement(0, 0, 0);
            Lanterne.LOG.info("[REGLAGES] scène « {} » installée : regard yaw={} pitch={}, joueur en {}.",
                    NOM, yaw, pitch, position(joueur));
        } catch (Throwable echec) {
            Lanterne.LOG.warn("[REGLAGES] installation de la scène « {} » impossible : {}", NOM, echec.toString());
        }
    }

    private static String position(ServerPlayer joueur) {
        return String.format(Locale.ROOT, "%.1f,%.1f,%.1f", joueur.getX(), joueur.getY(), joueur.getZ());
    }

    /** Vise le point situé à {@code avant} blocs devant le joueur (yaw 270 = ouest = -x). */
    private static double[] devant(ServerPlayer joueur, double avant, double haut) {
        return new double[] {joueur.getX() - avant, joueur.getY() + haut, joueur.getZ()};
    }

    private static void particules(MinecraftServer serveur) {
        if (serveur.getPlayerList().getPlayers().isEmpty()) {
            return;
        }
        var joueur = serveur.getPlayerList().getPlayers().get(0);
        var source = serveur.createCommandSourceStack().withSuppressedOutput();
        double[] p = devant(joueur, 6d, 1.5d);
        String at = String.format(Locale.ROOT, "%.1f %.1f %.1f", p[0], p[1], p[2]);
        var c = serveur.getCommands();
        c.performPrefixedCommand(source, "particle minecraft:explosion " + at + " 1.5 1.5 1.5 0 40 force");
        c.performPrefixedCommand(source, "particle minecraft:large_smoke " + at + " 1.5 1.5 1.5 0.02 60 force");
        c.performPrefixedCommand(source, "particle minecraft:cherry_leaves " + at + " 2 2 2 0.02 60 force");
        c.performPrefixedCommand(source, "particle minecraft:bubble_pop " + at + " 1.5 1.5 1.5 0.02 40 force");
        c.performPrefixedCommand(source, "particle minecraft:enchant " + at + " 1.5 1.5 1.5 0.5 60 force");
    }

    private static void entites(MinecraftServer serveur) {
        if (serveur.getPlayerList().getPlayers().isEmpty()) {
            return;
        }
        var joueur = serveur.getPlayerList().getPlayers().get(0);
        var source = serveur.createCommandSourceStack().withSuppressedOutput();
        var c = serveur.getCommands();
        for (int i = 0; i < 3; i++) {
            double[] p = devant(joueur, DISTANCE, 0.5d);
            String at = String.format(Locale.ROOT, "%.1f %.1f %.1f", p[0], p[1], p[2] + (i - 1) * 3d);
            c.performPrefixedCommand(source, "summon minecraft:armor_stand " + at
                    + " {ShowArms:1b,CustomName:'\"Support\"',CustomNameVisible:1b}");
            c.performPrefixedCommand(source, "summon minecraft:item_frame " + at.replace(" 0.", " 1.")
                    + " {Facing:5b}");
        }
        Lanterne.LOG.info("[REGLAGES] entités invoquées à {} blocs devant la caméra.", DISTANCE);
    }
}
