package fr.clubcitrouille.lanterne.core;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Consumer;

import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.configuration.ServerConfigurationPacketListener;
import net.minecraft.resources.Identifier;
import net.minecraft.server.network.ConfigurationTask;

import net.neoforged.neoforge.network.configuration.ICustomConfigurationTask;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.payload.FrozenRegistryPayload;
import net.neoforged.neoforge.network.payload.FrozenRegistrySyncCompletedPayload;
import net.neoforged.neoforge.network.payload.FrozenRegistrySyncStartPayload;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

import fr.clubcitrouille.lanterne.Lanterne;
import fr.clubcitrouille.lanterne.core.network.MecheChunk;
import fr.clubcitrouille.lanterne.core.network.MecheConfigPaquets;
import fr.clubcitrouille.lanterne.core.network.MecheManifest;
import fr.clubcitrouille.lanterne.core.network.MecheRequest;

/**
 * La Mèche en PHASE DE CONFIGURATION : le client se met à jour AVANT la synchronisation des
 * registres, là où un bloc ajouté le faisait refuser.
 *
 * <h2>Le problème (vécu le 26/09/2026)</h2>
 *
 * <p>La 26.3.145 ajoutait {@code lanterne:ancre_de_ferme}. Serveur en 145, clients en 144 : « The server
 * sent registries with unknown keys ». La Mèche historique ({@link Meche}) parle sur la connexion de
 * JEU, qui n'existe qu'APRÈS la configuration : elle n'a jamais pu tourner. Tout ajout de contenu
 * rendait donc la mise à jour elle-même impossible.
 *
 * <h2>L'ordre des tâches, lu dans les sources de la 26.3.0.7-beta</h2>
 *
 * <p>{@code ServerConfigurationPacketListenerImpl.runConfiguration()} fait, dans cet ordre :
 * <ol>
 *   <li>{@code ConfigurationInitialization.configureEarlyTasks(this, configurationTasks::add)} — la
 *       seule tâche « précoce » de NeoForge, {@code SyncRegistries} (les registres figés : blocs,
 *       objets…) ;</li>
 *   <li>{@code SynchronizeRegistriesTask} vanilla (registres de données + étiquettes) ;</li>
 *   <li>{@code addOptionalTasks()} : code de conduite, pack de ressources, PUIS les tâches de
 *       {@code RegisterConfigurationTasksEvent} ;</li>
 *   <li>{@code PrepareSpawnTask}, {@code JoinWorldTask}.</li>
 * </ol>
 * Une tâche enregistrée par l'évènement public arrive donc APRÈS la synchronisation des registres —
 * trop tard. {@code mixin.MecheConfigurationMixin} redirige l'appel à {@code configureEarlyTasks} vers
 * {@link #planifier}, qui ajoute la tâche de la Mèche AVANT d'appeler l'original : la file est une
 * {@code ConcurrentLinkedQueue} consommée dans l'ordre d'ajout, l'ordre est donc garanti par
 * construction.
 *
 * <h2>Les trois sortes de clients</h2>
 *
 * <ul>
 *   <li><b>Client ≥ 26.3.147</b> (il a négocié {@link MecheConfigPaquets.Annonce}) : la tâche
 *       {@link Tache} lui annonce version, empreinte et clés ; il répond, télécharge au besoin PENDANT
 *       la configuration, puis continue (registres compatibles) ou se déconnecte avec « Lanterne mis
 *       à jour (X → Y). Relance le jeu pour rejoindre. ».</li>
 *   <li><b>Client ancien</b> (≤ 26.3.146, pas de canal de configuration) et serveur dont toutes les
 *       clés Lanterne sont dans la référence ({@link MecheRegistres}) : rien ne change — connexion
 *       normale, mise à jour par la Mèche historique (connexion de jeu).</li>
 *   <li><b>Client ancien et nouvelles clés</b> : voir {@link MecheCompat} — synchronisation filtrée
 *       puis antichambre, ou refus propre avant la synchronisation.</li>
 * </ul>
 *
 * <h2>Pourquoi un canal inconnu du client ne le fait pas refuser</h2>
 *
 * <p>Les trois canaux sont enregistrés {@code optional()} : la négociation de NeoForge
 * ({@code NetworkComponentNegotiator}) n'échoue que sur un canal NON optionnel absent d'un côté. Et
 * la tâche n'est ajoutée que si {@code NetworkRegistry.hasChannel} confirme que le client a négocié
 * l'annonce : un client ancien ne la voit jamais, rien ne peut l'attendre. Par prudence, la tâche se
 * termine d'elle-même après {@link #ATTENTE_MAX} ticks sans réponse.
 */
public final class MecheConfig {
    public static final ConfigurationTask.Type TYPE = new ConfigurationTask.Type(
            Identifier.fromNamespaceAndPath(Lanterne.ID, "meche"));

    /** Tranches par tick : la configuration n'a pas de monde à faire tourner, on peut aller plus vite. */
    private static final int TRANCHES_PAR_TICK = 8;

    /** 30 s sans réponse : la tâche rend la main plutôt que de bloquer la connexion. */
    private static final int ATTENTE_MAX = 600;

    /** Les tâches en cours, par écouteur de configuration — lues par le gestionnaire de réponse. */
    private static final Map<ServerConfigurationPacketListener, Tache> EN_COURS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private MecheConfig() {}

    /**
     * Remplace l'appel à {@code ConfigurationInitialization.configureEarlyTasks} — voir la Javadoc de
     * classe. {@code neoforge} est l'appel d'origine : il ajoute {@code SyncRegistries}.
     */
    public static void planifier(ServerConfigurationPacketListener listener, Consumer<ConfigurationTask> tasks,
            Runnable neoforge) {
        try {
            Connection connection = listener.getConnection();
            if (connection.isMemoryConnection()) {
                neoforge.run();
                return;
            }
            boolean nouveauClient = NetworkRegistry.hasChannel(connection, ConnectionProtocol.CONFIGURATION,
                    MecheConfigPaquets.Annonce.TYPE.id());
            if (nouveauClient) {
                if (Settings.meche() && Meche.describeOwnJar(Lanterne.ID) != null) {
                    tasks.accept(new Tache(listener));
                }
                neoforge.run();
                return;
            }
            boolean synchroNeoForge = listener.hasChannel(FrozenRegistrySyncStartPayload.TYPE)
                    && listener.hasChannel(FrozenRegistryPayload.TYPE)
                    && listener.hasChannel(FrozenRegistrySyncCompletedPayload.TYPE);
            var inconnues = MecheRegistres.inconnuesDesAnciens();
            if (!synchroNeoForge || inconnues.isEmpty()) {
                neoforge.run(); // client ancien, mais rien qu'il ne connaisse : connexion normale
                return;
            }
            boolean mecheHistorique = hasPlay(connection, MecheManifest.TYPE)
                    && hasPlay(connection, MecheChunk.TYPE) && hasPlay(connection, MecheRequest.TYPE);
            if (Settings.meche() && mecheHistorique && Meche.describeOwnJar(Lanterne.ID) != null) {
                Lanterne.LOG.info("[MÈCHE] client ancien, {} clé(s) Lanterne qu'il ne connaît pas ({}…) : "
                        + "synchronisation filtrée puis antichambre de mise à jour.", inconnues.size(),
                        inconnues.get(0));
                tasks.accept(MecheCompat.synchronisationFiltree(connection));
                return; // PAS neoforge.run() : notre tâche remplace SyncRegistries
            }
            Lanterne.LOG.info("[MÈCHE] client ancien sans voie de mise à jour utilisable ({} clé(s) "
                    + "inconnues) : refus expliqué avant la synchronisation.", inconnues.size());
            tasks.accept(MecheCompat.refus(listener));
        } catch (RuntimeException imprevu) {
            // Jamais une connexion cassée par la Mèche : au pire, le comportement de NeoForge d'origine.
            Lanterne.LOG.warn("[MÈCHE] planification de la configuration échouée : {}", imprevu.toString());
            neoforge.run();
        }
    }

    private static boolean hasPlay(Connection connection, CustomPacketPayload.Type<?> type) {
        return NetworkRegistry.hasChannel(connection, ConnectionProtocol.PLAY, type.id());
    }

    /** Ce client a-t-il négocié la Mèche de configuration ? (il n'a alors plus besoin du manifeste de jeu) */
    public static boolean clientConfigurable(Connection connection) {
        return NetworkRegistry.hasChannel(connection, ConnectionProtocol.CONFIGURATION,
                MecheConfigPaquets.Annonce.TYPE.id());
    }

    /** Réponse du client — fil principal du serveur. */
    public static void onReponse(MecheConfigPaquets.Reponse reponse, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.listener() instanceof ServerConfigurationPacketListener listener)) {
                return;
            }
            Tache tache = EN_COURS.get(listener);
            if (tache == null) {
                return;
            }
            if (reponse.code() == MecheConfigPaquets.Reponse.DEMANDE && tache.etat == Etat.ANNONCE) {
                byte[] data = Meche.ownJarBytesPourConfig();
                if (data == null) {
                    tache.terminerDepuisReponse(context);
                    return;
                }
                tache.data = data;
                tache.offset = 0;
                tache.etat = Etat.ENVOI;
                tache.ticks = 0;
                Lanterne.LOG.info("[MÈCHE] {} télécharge Lanterne pendant la configuration ({} octets).",
                        tache.nom(), data.length);
            } else if (reponse.code() == MecheConfigPaquets.Reponse.CONTINUER) {
                tache.terminerDepuisReponse(context);
            }
        });
    }

    private enum Etat { ANNONCE, ENVOI, ATTENTE }

    /** La tâche de configuration : annonce, puis envoi par tranches à la demande. */
    static final class Tache implements ICustomConfigurationTask {
        private final ServerConfigurationPacketListener listener;
        private Consumer<CustomPacketPayload> sender;
        private Etat etat = Etat.ANNONCE;
        private byte[] data;
        private int offset;
        private int ticks;
        private boolean finie;

        Tache(ServerConfigurationPacketListener listener) {
            this.listener = listener;
        }

        String nom() {
            try {
                return ((net.minecraft.server.network.ServerConfigurationPacketListenerImpl) listener)
                        .getOwner().name();
            } catch (RuntimeException inconnu) {
                return "un client";
            }
        }

        @Override
        public void run(Consumer<CustomPacketPayload> sender) {
            this.sender = sender;
            Meche.JarInfo info = Meche.describeOwnJar(Lanterne.ID);
            if (info == null) {
                finie = true; // tick() rendra la main tout de suite
                return;
            }
            EN_COURS.put(listener, this);
            sender.accept(new MecheConfigPaquets.Annonce(info.version(), info.sha256(), info.size(),
                    MecheRegistres.clesLanterne()));
        }

        @Override
        public boolean tick() {
            if (finie) {
                EN_COURS.remove(listener);
                return true;
            }
            ticks++;
            if (etat == Etat.ENVOI) {
                for (int i = 0; i < TRANCHES_PAR_TICK && offset < data.length; i++) {
                    int longueur = Math.min(MecheConfigPaquets.TRANCHE_MAX, data.length - offset);
                    byte[] morceau = new byte[longueur];
                    System.arraycopy(data, offset, morceau, 0, longueur);
                    sender.accept(new MecheConfigPaquets.Tranche(offset, data.length, morceau));
                    offset += longueur;
                }
                if (offset >= data.length) {
                    Lanterne.LOG.info("[MÈCHE] envoi (configuration) terminé pour {}.", nom());
                    etat = Etat.ATTENTE;
                    ticks = 0;
                    data = null;
                }
                return false;
            }
            if (ticks >= ATTENTE_MAX) {
                Lanterne.LOG.warn("[MÈCHE] {} n'a pas répondu à la Mèche de configuration en {} s : "
                        + "la connexion continue sans elle.", nom(), ATTENTE_MAX / 20);
                EN_COURS.remove(listener);
                return true;
            }
            return false;
        }

        void terminerDepuisReponse(IPayloadContext context) {
            if (finie) {
                return;
            }
            finie = true;
            EN_COURS.remove(listener);
            try {
                context.finishCurrentTask(TYPE);
            } catch (IllegalStateException pasLaTacheCourante) {
                // tick() la terminera au prochain passage (finie = true).
            }
        }

        @Override
        public ConfigurationTask.Type type() {
            return TYPE;
        }
    }
}
