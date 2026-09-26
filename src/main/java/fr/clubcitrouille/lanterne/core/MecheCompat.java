package fr.clubcitrouille.lanterne.core;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.function.Consumer;

import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;

import net.minecraft.core.Registry;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import net.minecraft.network.protocol.common.ClientboundUpdateTagsPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.configuration.ServerConfigurationPacketListener;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ConfigurationTask;
import net.minecraft.tags.TagNetworkSerialization;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.configuration.ICustomConfigurationTask;
import net.neoforged.neoforge.network.configuration.SyncRegistries;
import net.neoforged.neoforge.network.payload.FrozenRegistryPayload;
import net.neoforged.neoforge.network.payload.FrozenRegistrySyncCompletedPayload;
import net.neoforged.neoforge.network.payload.FrozenRegistrySyncStartPayload;
import net.neoforged.neoforge.registries.RegistryManager;
import net.neoforged.neoforge.registries.RegistrySnapshot;

import fr.clubcitrouille.lanterne.Lanterne;
import fr.clubcitrouille.lanterne.core.network.MecheChunk;
import fr.clubcitrouille.lanterne.core.network.MecheManifest;

/**
 * Le mode de compatibilité : faire entrer un client ANCIEN (≤ 26.3.146, dont le code ne changera
 * plus) sur un serveur qui a des blocs/objets qu'il ne connaît pas, juste le temps que la Mèche
 * historique (connexion de jeu) lui livre le jar — puis le renvoyer relancer.
 *
 * <h2>Pourquoi on ne peut pas simplement « retirer les clés inconnues »</h2>
 *
 * <p>NeoForge synchronise les registres figés par leurs IDENTIFIANTS NUMÉRIQUES : le client adopte
 * ceux du serveur ({@code RegistryManager.applySnapshot} → {@code registerIdMapping}). Les
 * identifiants du serveur sont l'ordre d'enregistrement ({@code MappedRegistry.register(byId.size(),
 * …)}) : un bloc Lanterne ajouté tombe au milieu, pas à la fin (d'autres mods s'enregistrent après).
 * Retirer sa clé laisserait un TROU dans {@code byId} du client ; l'itérateur du registre
 * ({@code Iterators.transform(byId.iterator(), Holder::value)}) lèverait alors une
 * {@code NullPointerException} dès le rappel de cuisson des blocs (reconstruction de
 * {@code Block.BLOCK_STATE_REGISTRY}), et NeoForge couperait avec « registry sync failed ».
 *
 * <h2>Ce qu'on fait à la place</h2>
 *
 * <ol>
 *   <li><b>Synchronisation filtrée et RENUMÉROTÉE</b> ({@link SyncFiltree}) : à la place de
 *       {@code SyncRegistries}, on envoie les mêmes registres SANS AUCUNE clé {@code lanterne} (pas
 *       seulement les nouvelles : ainsi aucune hypothèse sur la version exacte du client), avec des
 *       identifiants compacts 0…n-1 — plus de trou. Les entrées Lanterne du client restent dans son
 *       registre, simplement sans identifiant, exactement comme les entrées « en plus » d'un client
 *       que NeoForge tolère déjà. Même type de tâche que {@code SyncRegistries} : c'est la réponse
 *       habituelle du client ({@code FrozenRegistrySyncCompletedPayload}) qui la termine.</li>
 *   <li><b>Étiquettes traduites</b> : le paquet vanilla des étiquettes porte des listes
 *       d'identifiants ; on les traduit dans la numérotation du client (les entrées Lanterne sont
 *       retirées) — {@link #traduireEtiquettes}.</li>
 *   <li><b>Antichambre</b> : ses identifiants ne sont plus ceux du serveur, donc en jeu ce client ne
 *       doit RIEN recevoir qui en porte (tronçons, inventaire, entités, recettes…). Tout paquet de
 *       jeu est retenu sauf une liste blanche : connexion ({@code ClientboundLoginPacket}, pour que le
 *       joueur existe et que la Mèche puisse répondre), maintien de connexion, déconnexion, et les
 *       deux charges de la Mèche historique ({@link MecheManifest}, {@link MecheChunk}). Aucun monde
 *       affiché, donc aucun monde corrompu : le joueur voit l'écran de chargement du terrain.</li>
 *   <li>Dès l'envoi du jar terminé (plus deux secondes pour qu'il l'écrive), le serveur le
 *       déconnecte avec « Lanterne mis à jour vers Y. Relance le jeu pour rejoindre. ». Il ne subit
 *       aucun dégât pendant ce temps.</li>
 * </ol>
 *
 * <h2>Le repli</h2>
 *
 * <p>Un client sans la Mèche historique (plus ancien que le 19/09/2026), ou un serveur dont la Mèche
 * est éteinte : {@link #refus} le déconnecte AVANT la synchronisation, avec un message qui dit
 * quoi faire, et l'adresse de téléchargement HTTP si {@link MecheHttp} est allumé.
 */
@EventBusSubscriber(modid = Lanterne.ID)
public final class MecheCompat {
    public static final ConfigurationTask.Type TYPE_REFUS = new ConfigurationTask.Type(
            Identifier.fromNamespaceAndPath(Lanterne.ID, "meche_refus"));

    /** Délai entre la fin de l'envoi et la déconnexion : le client hache et écrit le jar (< 1 s). */
    private static final int DELAI_APRES_ENVOI = 40;

    /** Sans demande du client au bout de 15 s, il ne la fera pas (déjà téléchargé ou refusée). */
    private static final int ATTENTE_DEMANDE = 300;

    /** L'état d'une connexion en compatibilité. */
    static final class Etat {
        /** Registre -> (identifiant serveur -> identifiant client), pour les registres renumérotés. */
        final Map<Identifier, Int2IntOpenHashMap> renumerotation = new HashMap<>();
        volatile Object etiquettesTraduites;
        UUID joueur;
        int ticks;
        boolean demande;
        int finEnvoi = -1;
    }

    private static final Map<Connection, Etat> ETATS = Collections.synchronizedMap(new WeakHashMap<>());

    /** L'hôte tel que le joueur l'a tapé (poignée de main), pour construire l'adresse HTTP. */
    private static final Map<Connection, String> HOTES = Collections.synchronizedMap(new WeakHashMap<>());

    private MecheCompat() {}

    public static void noterHote(Connection connection, String hote) {
        if (hote == null) {
            return;
        }
        int nul = hote.indexOf('\0');
        String propre = (nul >= 0 ? hote.substring(0, nul) : hote).strip();
        if (propre.endsWith(".")) {
            propre = propre.substring(0, propre.length() - 1);
        }
        if (!propre.isEmpty() && propre.length() <= 255) {
            HOTES.put(connection, propre);
        }
    }

    public static boolean enCompatibilite(Connection connection) {
        return ETATS.containsKey(connection);
    }

    static String versionServeur() {
        Meche.JarInfo info = Meche.describeOwnJar(Lanterne.ID);
        return info == null ? "?" : info.version();
    }

    // --- Configuration : synchronisation filtrée ------------------------------

    static ConfigurationTask synchronisationFiltree(Connection connection) {
        Etat etat = new Etat();
        ETATS.put(connection, etat);
        return new SyncFiltree(etat);
    }

    /** Remplace {@code SyncRegistries} pour un client ancien — voir la Javadoc de classe. */
    static final class SyncFiltree implements ICustomConfigurationTask {
        private final Etat etat;

        SyncFiltree(Etat etat) {
            this.etat = etat;
        }

        @Override
        public void run(Consumer<CustomPacketPayload> sender) {
            Map<Identifier, RegistrySnapshot> instantanes =
                    RegistryManager.takeSnapshot(RegistryManager.SnapshotType.SYNC_TO_CLIENT);
            sender.accept(new FrozenRegistrySyncStartPayload(RegistryManager.getRegistryNamesForSyncToClient()));
            int retirees = 0;
            for (var entree : instantanes.entrySet()) {
                RegistrySnapshot original = entree.getValue();
                Int2IntOpenHashMap table = new Int2IntOpenHashMap();
                table.defaultReturnValue(-1);
                FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
                try {
                    var ids = original.getIds();
                    int gardees = 0;
                    for (var id : ids.int2ObjectEntrySet()) {
                        if (!Lanterne.ID.equals(id.getValue().getNamespace())) {
                            gardees++;
                        }
                    }
                    if (gardees == ids.size()) {
                        sender.accept(new FrozenRegistryPayload(entree.getKey(), original));
                        continue;
                    }
                    retirees += ids.size() - gardees;
                    buf.writeVarInt(gardees);
                    int compact = 0;
                    for (var id : ids.int2ObjectEntrySet()) {
                        if (Lanterne.ID.equals(id.getValue().getNamespace())) {
                            continue;
                        }
                        table.put(id.getIntKey(), compact);
                        buf.writeVarInt(compact);
                        buf.writeIdentifier(id.getValue());
                        compact++;
                    }
                    var alias = original.getAliases().entrySet().stream()
                            .filter(a -> !Lanterne.ID.equals(a.getKey().getNamespace())
                                    && !Lanterne.ID.equals(a.getValue().getNamespace()))
                            .toList();
                    buf.writeVarInt(alias.size());
                    for (var a : alias) {
                        buf.writeIdentifier(a.getKey());
                        buf.writeIdentifier(a.getValue());
                    }
                    RegistrySnapshot filtre = RegistrySnapshot.STREAM_CODEC.decode(buf);
                    etat.renumerotation.put(entree.getKey(), table);
                    sender.accept(new FrozenRegistryPayload(entree.getKey(), filtre));
                } finally {
                    buf.release();
                }
            }
            sender.accept(FrozenRegistrySyncCompletedPayload.INSTANCE);
            Lanterne.LOG.info("[MÈCHE] synchronisation filtrée envoyée : {} entrée(s) Lanterne retirée(s), "
                    + "{} registre(s) renuméroté(s).", retirees, etat.renumerotation.size());
        }

        @Override
        public ConfigurationTask.Type type() {
            return SyncRegistries.TYPE; // terminée par la réponse habituelle du client
        }
    }

    /**
     * Le paquet d'étiquettes traduit dans la numérotation du client, ou {@code null} s'il n'y a rien à
     * traduire (connexion normale, ou paquet déjà traduit).
     */
    public static Packet<?> traduireEtiquettes(Connection connection, ClientboundUpdateTagsPacket paquet) {
        Etat etat = ETATS.get(connection);
        if (etat == null || etat.renumerotation.isEmpty() || etat.etiquettesTraduites == paquet) {
            return null;
        }
        Map<ResourceKey<? extends Registry<?>>, TagNetworkSerialization.NetworkPayload> traduit = new HashMap<>();
        for (var entree : paquet.tags().entrySet()) {
            Int2IntOpenHashMap table = etat.renumerotation.get(entree.getKey().identifier());
            if (table == null) {
                traduit.put(entree.getKey(), entree.getValue());
                continue;
            }
            Map<Identifier, IntList> etiquettes = new HashMap<>();
            for (var tag : entree.getValue().tags().entrySet()) {
                IntList ids = new IntArrayList(tag.getValue().size());
                for (int i = 0; i < tag.getValue().size(); i++) {
                    int client = table.get(tag.getValue().getInt(i));
                    if (client >= 0) {
                        ids.add(client);
                    }
                }
                etiquettes.put(tag.getKey(), ids);
            }
            traduit.put(entree.getKey(), new TagNetworkSerialization.NetworkPayload(etiquettes));
        }
        ClientboundUpdateTagsPacket nouveau = new ClientboundUpdateTagsPacket(traduit);
        etat.etiquettesTraduites = nouveau;
        return nouveau;
    }

    // --- Jeu : l'antichambre ------------------------------------------------------

    /**
     * Ce paquet de JEU doit-il être retenu pour cette connexion ? Seulement en compatibilité — voir la
     * Javadoc de classe pour la liste blanche.
     */
    public static boolean retenir(Connection connection, Packet<?> paquet) {
        if (!ETATS.containsKey(connection)) {
            return false;
        }
        if (paquet instanceof ClientboundLoginPacket || paquet instanceof ClientboundKeepAlivePacket
                || paquet instanceof ClientboundPingPacket || paquet instanceof ClientboundDisconnectPacket) {
            return false;
        }
        if (paquet instanceof ClientboundCustomPayloadPacket custom) {
            var type = custom.payload().type();
            return !(type.equals(MecheManifest.TYPE) || type.equals(MecheChunk.TYPE));
        }
        return true;
    }

    /** Appelé par {@code MecheNet.onJoin} : ce joueur est-il dans l'antichambre ? */
    public static boolean entrerAntichambre(ServerPlayer player) {
        Etat etat = ETATS.get(player.connection.getConnection());
        if (etat == null) {
            return false;
        }
        etat.joueur = player.getUUID();
        Lanterne.LOG.info("[MÈCHE] {} est dans l'antichambre de mise à jour (client ancien, rien du monde "
                + "ne lui est envoyé).", player.getGameProfile().name());
        return true;
    }

    static void demandeRecue(ServerPlayer player) {
        Etat etat = ETATS.get(player.connection.getConnection());
        if (etat != null) {
            etat.demande = true;
        }
    }

    static void envoiTermine(ServerPlayer player) {
        Etat etat = ETATS.get(player.connection.getConnection());
        if (etat != null) {
            etat.finEnvoi = etat.ticks;
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (ETATS.isEmpty()) {
            return;
        }
        for (ServerPlayer player : List.copyOf(event.getServer().getPlayerList().getPlayers())) {
            Etat etat = ETATS.get(player.connection.getConnection());
            if (etat == null || etat.joueur == null) {
                continue;
            }
            etat.ticks++;
            String version = versionServeur();
            if (etat.finEnvoi >= 0 && etat.ticks - etat.finEnvoi >= DELAI_APRES_ENVOI) {
                Lanterne.LOG.info("[MÈCHE] {} a reçu Lanterne {} : déconnecté pour relancer.",
                        player.getGameProfile().name(), version);
                player.connection.disconnect(Component.literal("Lanterne mis à jour vers " + version
                        + ".\n\nRelance le jeu pour rejoindre.\n\n"
                        + "L'ancien lanterne-….jar de ton dossier mods/ s'efface tout seul quelques "
                        + "secondes après la fermeture du jeu. Si le jeu refuse de démarrer (« deux "
                        + "versions du même mod »), supprime-le à la main et garde lanterne-" + version
                        + ".jar."));
                ETATS.remove(player.connection.getConnection());
            } else if (!etat.demande && etat.ticks >= ATTENTE_DEMANDE) {
                Lanterne.LOG.info("[MÈCHE] {} n'a pas demandé Lanterne {} : déconnecté avec les instructions.",
                        player.getGameProfile().name(), version);
                player.connection.disconnect(Component.literal(messageSansDemande(
                        player.connection.getConnection(), version)));
                ETATS.remove(player.connection.getConnection());
            }
        }
    }

    private static String messageSansDemande(Connection connection, String version) {
        return "Ce serveur utilise Lanterne " + version + ", avec des blocs ou objets que ta version ne "
                + "connaît pas.\n\n"
                + "Si tu viens déjà de le télécharger (lanterne-" + version + ".jar dans mods/) : "
                + "relance simplement le jeu.\n\n"
                + "Sinon, la mise à jour automatique est coupée chez toi : mets synchro_auto = true dans "
                + "config/lanterne-maj-client.toml puis reconnecte-toi"
                + ouLien(connection, version) + ".";
    }

    private static String ouLien(Connection connection, String version) {
        String url = MecheHttp.adresse(HOTES.get(connection), version);
        return url == null ? ", ou demande lanterne-" + version + ".jar à l'administrateur"
                : ", ou télécharge-le ici : " + url;
    }

    @SubscribeEvent
    public static void onDamage(LivingIncomingDamageEvent event) {
        if (!ETATS.isEmpty() && event.getEntity() instanceof ServerPlayer player
                && ETATS.containsKey(player.connection.getConnection())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onLeave(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            ETATS.remove(player.connection.getConnection());
        }
    }

    // --- Repli : refus expliqué, avant la synchronisation ----------------------------

    static ConfigurationTask refus(ServerConfigurationPacketListener listener) {
        return new ConfigurationTask() {
            @Override
            public void start(Consumer<Packet<?>> sender) {
                String version = versionServeur();
                Connection connection = listener.getConnection();
                String url = MecheHttp.adresse(HOTES.get(connection), version);
                listener.disconnect(Component.literal("Ce serveur utilise Lanterne " + version
                        + ", avec des blocs ou objets que ta version de Lanterne ne connaît pas, et ta "
                        + "version est trop ancienne pour se mettre à jour toute seule.\n\n"
                        + (url != null ? "Télécharge la nouvelle version : " + url + "\n"
                                : "Demande le fichier lanterne-" + version + ".jar à l'administrateur.\n")
                        + "Mets-le dans ton dossier mods/, SUPPRIME l'ancien lanterne-….jar, puis relance le jeu."));
            }

            @Override
            public Type type() {
                return TYPE_REFUS;
            }
        };
    }
}
