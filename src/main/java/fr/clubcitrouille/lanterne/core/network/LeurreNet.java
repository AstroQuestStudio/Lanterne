package fr.clubcitrouille.lanterne.core.network;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * Le branchement réseau de l'exemption AutoMiner. Voir {@code core.Leurre} pour la décision
 * elle-même ; ce fichier ne fait que déclarer (ou non) le canal qui la rend possible.
 *
 * <h2>Ce qui a été cherché, et qui n'existe pas dans ce moteur</h2>
 *
 * <p>La première idée — lire une liste complète des mods chargés par le client, distincte de tout
 * canal réseau, exposée pendant la poignée de main FML/NeoForge — a été <b>vérifiée fausse</b> pour ce
 * moteur (NeoForge 26.3.0.3-beta), pas supposée. Trois classes patchées par NeoForge dans le jar
 * Minecraft ont été désassemblées avec {@code javap -p -c} :
 *
 * <ul>
 *   <li>{@code ServerLoginPacketListenerImpl} : <b>aucune</b> référence à quoi que ce soit sous
 *       {@code net.neoforged}. La phase de login ne transporte plus de liste de mods depuis la
 *       réécriture du réseau de NeoForge (post-1.20.2) — c'était le comportement de l'ancien Forge,
 *       disparu ici.</li>
 *   <li>{@code ServerConfigurationPacketListenerImpl} : les seules références NeoForge portent sur
 *       {@code ConnectionType} (un booléen — « ce client est-il un client NeoForge, oui/non » — vrai
 *       pour <b>tout</b> joueur de ce serveur puisque Lanterne est obligatoire des deux côtés, donc
 *       inutile pour distinguer AutoMiner) et sur {@code NetworkRegistry.initializeNeoForgeConnection},
 *       qui négocie exclusivement des <b>canaux</b> ({@code ModdedNetworkQueryPayload},
 *       {@code CommonRegisterPayload}) — jamais une liste brute d'identifiants de mod.</li>
 *   <li>Le jar {@code neoforge-26.3.0.3-beta-universal.jar} lui-même : aucune classe, dans tout le
 *       paquet {@code net.neoforged.neoforge.network}, ne conserve par connexion autre chose que des
 *       ensembles de canaux ({@code ChannelAttributes.PAYLOAD_SETUP}, {@code ADHOC_CHANNELS},
 *       {@code COMMON_CHANNELS}). {@code ModMismatchEvent} existe, mais compare les mods du
 *       <b>monde sauvegardé</b> à ceux du <b>serveur local</b> — rien à voir avec l'identité d'une
 *       connexion distante.</li>
 * </ul>
 *
 * <p>Conclusion vérifiée, pas supposée : <b>un mod sans canal réseau est, dans ce moteur,
 * structurellement invisible au serveur.</b> C'est exactement le cas d'AutoMiner (confirmé par
 * {@code grep} sur son code source : aucun {@code PayloadRegistrar}, aucun {@code NetworkRegistry},
 * aucun {@code SimpleChannel}). Le seul signal que ce moteur négocie réellement à la connexion, pour
 * n'importe quel mod, est la présence d'un <b>canal réseau</b> — {@code NetworkRegistry.hasChannel},
 * lui-même vérifié par désassemblage : {@code ICommonPacketListener.hasChannel(Identifier)} délègue
 * directement à {@code NetworkRegistry.hasChannel(getConnection(), protocol(), id)}.
 *
 * <h2>Ce que ce fichier fait à la place</h2>
 *
 * <p>Puisqu'AutoMiner n'a et ne veut aucun canal applicatif, Lanterne — mod obligatoire des deux
 * côtés — lui en prête un <b>d'identité pure</b> : {@link AutominerPresence}, un paquet à zéro champ,
 * jamais envoyé. La classe cliente de Lanterne ({@code this} fichier, exécuté aussi côté client)
 * déclare ce canal <b>uniquement si {@code ModList.get().isLoaded("autominer")} répond vrai</b> — une
 * lecture locale, dans la même JVM, du vrai chargeur de mods NeoForge : pas une affirmation du réseau,
 * une lecture directe de ce que ModLauncher a réellement instancié sur cette machine.
 *
 * <p>Le canal est déclaré {@code optional()} des deux côtés : un client sans AutoMiner ne le déclare
 * pas, et la négociation ne doit surtout pas le traiter comme une incompatibilité — voir
 * {@code NetworkRegistry.hasAdhocChannel} et {@code NetworkComponentNegotiator.negotiate}, où un
 * canal optionnel absent d'un côté est simplement omis du résultat final, jamais un motif de
 * déconnexion. Sans ce marqueur, tout joueur sans AutoMiner échouerait sa connexion.
 *
 * <h2>Pourquoi le SERVEUR le déclare aussi, inconditionnellement</h2>
 *
 * <p>La négociation de {@code NetworkComponentNegotiator} apparie des composants présents <b>des deux
 * côtés</b> — un identifiant que le client seul déclare, sans miroir côté serveur, n'aboutit à aucun
 * {@code NetworkChannel} dans {@code NetworkPayloadSetup}. Le serveur doit donc connaître
 * {@code autominer_presence} pour pouvoir reconnaître sa présence côté client ; il ne l'envoie ni ne
 * l'attend jamais, il se contente de savoir que ce nom existe.
 *
 * <h2>Ce que ce signal garantit, et ce qu'il ne garantit pas</h2>
 *
 * <p>Voir la Javadoc de {@code core.Leurre}, section « Le signal retenu ».
 */
public final class LeurreNet {

    /**
     * L'identifiant de mod d'AutoMiner, lu dans son {@code neoforge.mods.toml} réel
     * ({@code ClubCitrouilleAutoMiner/build/resources/main/META-INF/neoforge.mods.toml},
     * {@code modId="autominer"}) — pas supposé.
     */
    public static final String AUTOMINER_MOD_ID = "autominer";

    /** Version du protocole du canal d'identité. Ne changera jamais : il ne porte aucune donnée. */
    private static final String PROTOCOL = "1";

    private LeurreNet() {}

    public static void register(IEventBus modBus) {
        modBus.addListener(LeurreNet::onRegisterPayloads);
    }

    /**
     * Déclare le canal — ou pas. Exécutée telle quelle des deux côtés (comme {@code MecheNet} et
     * {@code SouvenirNet}), mais la décision elle-même diverge par côté :
     *
     * <ul>
     *   <li>côté SERVEUR (dédié) : toujours, pour que la négociation ait quelque chose à apparier ;</li>
     *   <li>côté CLIENT : seulement si AutoMiner est réellement chargé ici.</li>
     * </ul>
     */
    private static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        if (!FMLEnvironment.getDist().isDedicatedServer() && !autominerCharge()) {
            return;
        }
        event.registrar(PROTOCOL).optional().playToServer(
                AutominerPresence.TYPE, AutominerPresence.STREAM_CODEC,
                LeurreNet::onPresenceRecue);
    }

    /**
     * Ne devrait jamais être appelée : ce paquet n'est jamais envoyé, voir la Javadoc de
     * {@link AutominerPresence}. Un gestionnaire est néanmoins exigé par {@code PayloadRegistrar} —
     * celui-ci se contente de journaliser, au cas où un client futur en ferait un usage imprévu.
     */
    private static void onPresenceRecue(AutominerPresence payload,
            net.neoforged.neoforge.network.handling.IPayloadContext context) {
        Lanterne.LOG.debug("[LEURRE] Paquet d'identité autominer_presence reçu — inattendu, "
                + "ce canal ne sert qu'à sa propre négociation.");
    }

    /**
     * AutoMiner est-il réellement chargé sur CETTE JVM ? Lecture locale du chargeur de mods, jamais
     * une affirmation venue du réseau. Même garde défensive que {@code client.portals.Portail} :
     * {@code RegisterPayloadHandlersEvent} se déclenche après la découverte des mods, donc
     * {@code ModList.get()} devrait toujours répondre ici — le {@code try/catch} protège contre un
     * ordre d'évènement qui changerait dans une future version de NeoForge sans qu'on l'ait revérifié.
     */
    private static boolean autominerCharge() {
        try {
            return ModList.get().isLoaded(AUTOMINER_MOD_ID);
        } catch (Throwable tooEarly) {
            return false;
        }
    }
}
