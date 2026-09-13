package fr.clubcitrouille.lanterne.content.waypoint;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import fr.clubcitrouille.lanterne.core.Config;
import fr.clubcitrouille.lanterne.core.Settings;

/**
 * Le côté serveur des repères : ce qu'il accepte, et ce qu'il refuse.
 *
 * <h2>La règle qui gouverne tout le fichier</h2>
 *
 * <p>Un serveur ne croit <b>rien</b> de ce qu'un client lui dit sur lui-même. L'identifiant du
 * propriétaire ne vient jamais du paquet : il est lu dans la connexion. C'est la seule façon qu'un
 * client modifié ne puisse pas effacer le repère d'un autre joueur — et c'est une différence de
 * nature, pas de degré, avec un système où l'auteur s'annonce.
 */
public final class Waypoints {
    /** Version du protocole. Un client d'une autre version se verra refuser la connexion au canal. */
    private static final String PROTOCOL = "1";

    private Waypoints() {}

    public static void register(IEventBus modBus) {
        modBus.addListener(Waypoints::onRegisterPayloads);
    }

    private static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar(PROTOCOL);
        registrar.playToClient(WaypointSync.TYPE, WaypointSync.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(
                        () -> fr.clubcitrouille.lanterne.client.waypoint.Marks.accept(payload.marks())));
        registrar.playToServer(WaypointAsk.TYPE, WaypointAsk.STREAM_CODEC, Waypoints::onAsk);
    }

    /**
     * Traite une demande du client, sur le fil du serveur.
     *
     * <p>{@code enqueueWork} n'est pas une précaution de style : le gestionnaire est appelé sur le fil
     * réseau, et toucher aux données de monde depuis là serait une course pure et simple.
     */
    private static void onAsk(WaypointAsk ask, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            if (!Settings.waypoints()) {
                tell(player, "Les repères sont désactivés sur ce serveur.");
                return;
            }
            var server = player.level().getServer();
            if (server == null) {
                return;
            }
            Atlas atlas = Atlas.of(server);
            String name = clean(ask.name());
            if (name.isEmpty()) {
                tell(player, "Un repère a besoin d'un nom.");
                return;
            }

            switch (ask.verb()) {
                case PLACE -> {
                    // La position vient du paquet pour que l'interface puisse proposer autre chose
                    // que les pieds du joueur — mais on la borne au monde où il se trouve, faute de
                    // quoi on poserait un repère dans une dimension qu'il n'a jamais vue.
                    var mark = new Waypoint(player.getUUID(), player.getGameProfile().name(), name,
                            player.level().dimension().identifier(), ask.pos(),
                            sane(ask.colour()), false);
                    String refusal = atlas.add(mark);
                    if (refusal != null) {
                        tell(player, refusal);
                        return;
                    }
                    tell(player, "Repère « " + name + " » posé.");
                }
                case DROP -> {
                    if (!atlas.remove(player.getUUID(), name)) {
                        tell(player, "Aucun repère de ce nom.");
                        return;
                    }
                    tell(player, "Repère « " + name + " » retiré.");
                }
                case SHARE -> {
                    var current = atlas.find(player.getUUID(), name);
                    if (current == null) {
                        tell(player, "Aucun repère de ce nom.");
                        return;
                    }
                    boolean wanted = !current.shared();
                    if (wanted && countShared(atlas) >= Config.WAYPOINT_SHARED_CAP.get()) {
                        tell(player, "Le serveur a atteint son nombre de repères publics.");
                        return;
                    }
                    atlas.replace(player.getUUID(), name, mark -> mark.withShared(wanted));
                    tell(player, "Repère « " + name + (wanted ? " » rendu public." : " » redevenu privé."));
                }
                case TINT -> {
                    if (!atlas.replace(player.getUUID(), name,
                            mark -> mark.withColour(sane(ask.colour())))) {
                        tell(player, "Aucun repère de ce nom.");
                        return;
                    }
                }
            }
            // Un partage change ce que les AUTRES voient : on renvoie à tout le monde. Le coût est
            // celui de quelques dizaines de repères, et il achète l'impossibilité d'une copie périmée.
            atlas.syncAll(server);
        });
    }

    /** Repères publics du serveur entier. */
    private static int countShared(Atlas atlas) {
        int total = 0;
        for (Waypoint mark : atlas.visibleTo(new java.util.UUID(0L, 0L))) {
            if (mark.shared()) {
                total++;
            }
        }
        return total;
    }

    /** Un nom sans espaces parasites, tronqué, et sans code de formatage. */
    public static String clean(String raw) {
        String trimmed = ChatFormatting.stripFormatting(raw);
        if (trimmed == null) {
            return "";
        }
        trimmed = trimmed.trim();
        return trimmed.length() > Waypoint.NAME_LIMIT
                ? trimmed.substring(0, Waypoint.NAME_LIMIT) : trimmed;
    }

    /** Une couleur bornée à vingt-quatre bits : un client ne décide pas de la transparence. */
    private static int sane(int colour) {
        return colour & 0xFFFFFF;
    }

    public static void tell(ServerPlayer player, String text) {
        player.sendSystemMessage(Component.literal(text).withStyle(ChatFormatting.GRAY));
    }
}
