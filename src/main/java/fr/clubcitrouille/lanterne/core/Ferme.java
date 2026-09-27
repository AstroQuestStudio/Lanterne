package fr.clubcitrouille.lanterne.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongConsumer;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.Ticket;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;

import org.jspecify.annotations.Nullable;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import fr.clubcitrouille.lanterne.Lanterne;
import fr.clubcitrouille.lanterne.mixin.ChunkMapTicketsAccessor;
import fr.clubcitrouille.lanterne.mixin.TicketStorageAccessor;

/**
 * L'Ancre de ferme : une ferme à monstres qui produit quand son propriétaire est loin.
 *
 * <h2>Pourquoi l'Ancre de chunk ne suffit pas</h2>
 *
 * <p>Le ticket {@code forced} de vanilla — celui de {@code /forceload} et de l'Ancre — charge et
 * simule son chunk : les cultures poussent, les entonnoirs vident, les entités bougent. Mais il ne
 * fait naître <b>aucun monstre</b>, et une ferme à monstres ancrée reste donc désespérément vide dès
 * que le joueur s'éloigne. Le javap du serveur 26.3 montre pourquoi, et l'obstacle est en cinq étages,
 * pas un :
 *
 * <ol>
 * <li>{@code ChunkMap.collectSpawningChunks} ne parcourt que les chunks <em>proches d'un joueur</em>
 * (le compteur {@code naturalSpawnChunkCounter}). Le drapeau {@code forceNaturalSpawning} d'un ticket
 * n'y sert qu'à repêcher un chunk déjà dans ce voisinage : un chunk à deux mille blocs n'y est jamais.
 * <b>Remède :</b> {@code FermeChunkMapMixin} ajoute les chunks « ferme » à la liste.</li>
 * <li>Le plafond local ({@code LocalMobCapCalculator.canSpawn}) répond non quand aucun joueur n'est
 * près du chunk. <b>Remède :</b> {@code FermeSpawnMixin} le remplace, pour ces seuls chunks, par
 * {@link #roomFor} — au plus {@code ferme_plafond} monstres à 32 blocs.</li>
 * <li>{@code NaturalSpawner.isValidSpawnPostitionForType} refuse un type qui ne sait pas « naître loin
 * d'un joueur » au-delà de 128 blocs. <b>Remède :</b> même mixin.</li>
 * <li>{@code NaturalSpawner.isValidPositionForMob} refuse une créature qui disparaîtrait aussitôt
 * (plus de 128 blocs, {@code removeWhenFarAway}). <b>Remède :</b> même mixin.</li>
 * <li>{@code Mob.checkDespawn} efface sur-le-champ un monstre à plus de 128 blocs du joueur le plus
 * proche. <b>Remède :</b> {@code FermeDespawnMixin} retire cette disparition instantanée et garde la
 * disparition aléatoire vanilla (1/800 par tick après 600 ticks d'inaction).</li>
 * </ol>
 *
 * <p>Ce qui reste exigé, volontairement : un joueur <b>connecté</b>. Sans personne en ligne, rien
 * n'apparaît : une ferme ne doit pas remplir un serveur vide.
 *
 * <h2>Un joueur connecté, dans n'importe quelle dimension</h2>
 *
 * <p>Vanilla demande davantage : un joueur <em>dans la dimension</em>. Une ferme à wither squelettes
 * dans une forteresse du Nether resterait donc vide tant que tout le monde est dans l'Overworld. Avec
 * {@code ferme_toute_dimension} (vrai par défaut), trois obstacles de plus sont levés — pour les seuls
 * chunks « ferme », et seulement quand la dimension n'a aucun joueur (hors spectateurs) :
 *
 * <ol start="6">
 * <li>Le plafond global ({@code SpawnState.canSpawnForCategoryGlobal}) vaut
 * {@code 70 × chunks proches d'un joueur / 289} : zéro sans joueur, et la catégorie MONSTER n'est même
 * pas tentée. <b>Remède :</b> {@code FermeChunkCacheMixin} la rétablit avec le plafond d'un joueur
 * seul (70 monstres non persistants dans la dimension). Seuls les chunks « ferme » sont alors dans le
 * tour d'apparition — les autres n'ont aucun joueur autour — et le plafond local de l'étage 2 ne
 * laisse passer qu'eux.</li>
 * <li>{@code spawnCategoryForPosition} abandonne la tentative si {@code getNearestPlayer} est nul.
 * <b>Remède :</b> {@code FermeSpawnMixin} lui présente {@link #ailleurs}, un joueur connecté dans une
 * autre dimension, et la distance à ce joueur vaut {@link #LOIN} — « très loin », jamais la distance
 * absurde entre deux dimensions. Tous les tests qui suivent (24 blocs, point d'apparition,
 * {@code canSpawnFarFromPlayer}, {@code isValidPositionForMob}) voient donc un joueur lointain, ce que
 * les étages 3 et 4 règlent déjà.</li>
 * <li>{@code Mob.checkDespawn} ne fait rien sans joueur dans la dimension — pas même la disparition
 * aléatoire, et un monstre coincé boucherait la salle pour toujours. <b>Remède :</b>
 * {@code FermeDespawnMixin} lui présente le même joueur lointain : l'étage 5 s'applique tel quel.</li>
 * </ol>
 *
 * <p>Le joueur d'une autre dimension ne sert qu'à ces calculs de distance, jamais ailleurs : il n'est
 * ni ciblé, ni compté, ni suivi. La simulation ne demande rien de plus : le ticket de ferme porte
 * {@code FLAG_KEEP_DIMENSION_ACTIVE} (sans joueur, {@code ServerLevel.tick} continue de tiquer les
 * entités de la dimension) et {@code FLAG_SIMULATION} (le chunk est « entity ticking » : IA,
 * déplacement, chute).
 *
 * <h2>Pourquoi pas {@code MobDespawnEvent}</h2>
 *
 * <p>Le crochet de NeoForge ne laisse que trois réponses : {@code ALLOW} efface, {@code DEFAULT} laisse
 * vanilla effacer (on est à plus de 128 blocs), et {@code DENY} remet {@code noActionTime} à zéro à
 * chaque tick — ce qui rendrait la disparition aléatoire impossible, puisqu'elle exige 600 ticks
 * d'inaction. Aucune des trois ne dit « pas tout de suite, mais un jour ». D'où le mixin, qui neutralise
 * le premier appel à {@code removeWhenFarAway} et laisse le second à vanilla.
 *
 * <h2>Ce que ça coûte</h2>
 *
 * <p>Le Xeon mono-cœur du serveur ne pardonne pas une boucle de tick qui balaie des chunks : rien ici
 * ne le fait. Les chunks « ferme » sont tenus dans un {@code LongSet} par dimension, alimenté à la pose
 * et au retrait, et reconstruit une seule fois au démarrage depuis les tickets vanilla (qui sont, eux,
 * la source de vérité persistante). Tous les tests chauds commencent par {@code total == 0}, qui répond
 * vrai sur un serveur sans ancre de ferme. Le seul comptage d'entités — le plafond local — ne porte que
 * sur la boîte de 80 × 80 blocs d'une ferme, s'arrête dès le plafond atteint, et n'est pas refait
 * pendant une demi-seconde quand la salle est pleine. Le balayage des monstres figés ({@link #sweep})
 * passe une fois toutes les trente secondes, sur la même boîte.
 *
 * <p>Ce qui coûte vraiment, c'est ce qu'on a demandé : une quarantaine de monstres simulés à plein
 * régime, exactement comme si le joueur attendait à côté de sa ferme. Au banc (Ryzen, monde plat
 * d'essai) : 0,4 ms par tick sans ferme, 1,5 ms avec une ferme pleine.
 */
public final class Ferme {
    private Ferme() {}

    /**
     * La distance (au carré) prêtée au joueur d'une autre dimension : un million de blocs. Au-delà de
     * tous les seuils de vanilla (24, 32, 128 blocs), et finie — rien ne déborde si un calcul la
     * reprend.
     */
    public static final double LOIN = 1.0E12;

    /** Rayon, en blocs, autour du chunk, dans lequel on compte les monstres pour le plafond local. */
    public static final int RAYON_PLAFOND = 32;

    /**
     * Rayon passé à {@code addTicketWithRadius} : {@code 33 - 2 = 31}, exactement le niveau du ticket
     * {@code forced} ({@code ChunkMap.FORCED_TICKET_LEVEL}) — le chunk tique ses entités, ses voisins
     * ses blocs, comme sous {@code /forceload}. Ni plus, ni moins.
     */
    private static final int TICKET_RADIUS = 2;

    public static final DeferredRegister<TicketType> TICKET_TYPES =
            DeferredRegister.create(Registries.TICKET_TYPE, Lanterne.ID);

    /**
     * Le ticket de la ferme : les drapeaux du ticket {@code forced} (persistant, charge, simule, garde
     * la dimension active) et {@code forceNaturalSpawning = true}.
     *
     * <p>Le drapeau d'apparition n'est pas décoratif : c'est lui qui fait passer le chunk dans
     * {@code chunksWithForceNaturalSpawning}, et donc repêcher par vanilla quand un joueur passe dans
     * le voisinage. Il le distingue aussi du ticket {@code forced} — {@code TicketType} est un record,
     * et deux records aux champs identiques sont égaux : sans ce booléen, vanilla prendrait le nôtre
     * pour un {@code /forceload}.
     *
     * <p><b>Persistance :</b> {@code FLAG_PERSIST} suffit — {@code TicketStorage.packTickets} écrit tout
     * ticket persistant dans {@code data/chunk_tickets.dat}, avec l'identifiant du type
     * ({@code lanterne:ferme}), et le rétablit au démarrage. Aucun code du mod n'a à s'en souvenir.
     */
    public static final DeferredHolder<TicketType, TicketType> TICKET = TICKET_TYPES.register("ferme",
            () -> new TicketType(TicketType.NO_TIMEOUT,
                    TicketType.FLAG_PERSIST | TicketType.FLAG_LOADING | TicketType.FLAG_SIMULATION
                            | TicketType.FLAG_KEEP_DIMENSION_ACTIVE,
                    true));

    /** Les chunks « ferme », par dimension. Lu depuis le fil serveur seulement. */
    private static final Map<ResourceKey<Level>, LongOpenHashSet> CHUNKS = new HashMap<>();

    /**
     * Les chunks « ferme » et leurs huit voisins, par dimension : la zone où un monstre n'est pas
     * effacé d'un coup à distance, et où la cadence de ce mod ne s'applique pas. Recalculée à chaque
     * pose ou retrait — quelques dizaines d'entrées — pour que le test par entité reste un seul
     * {@code contains}.
     *
     * <p>Pourquoi les voisins : une ferme dont la chambre de mort est dans le chunk d'à côté, tenu par
     * une Ancre de chunk, verrait sinon ses monstres effacés à l'instant où ils y tombent — aucun
     * butin, et aucune explication. Les voisins d'une ferme seule ne tiquent pas leurs entités : la
     * mesure ne change rien pour eux.
     */
    private static final Map<ResourceKey<Level>, LongOpenHashSet> HALO = new HashMap<>();

    /** Nombre total de chunks « ferme », toutes dimensions : la sortie rapide de tous les tests chauds. */
    private static int total;

    /** Délai, en ticks, avant de recompter une ferme trouvée pleine. Voir {@link #roomFor}. */
    private static final long RECOMPTE_APRES = 10L;

    /** Pour chaque ferme trouvée pleine, le tick avant lequel on ne la recompte pas. */
    private static final it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap PLEINE_JUSQUA =
            new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap();

    /** Tous les combien de ticks on retire les monstres figés autour des fermes. Voir {@link #sweep}. */
    private static final int SWEEP_PERIOD = 600;

    // Compteurs pour le rapport ({@code /lanterne ferme}) : relevés, jamais lus dans une décision.
    private static long admises;
    private static long plafonnees;
    private static long epargnees;
    private static long balayes;
    private static long ailleursNes;
    private static long dernierJournalBalayage = Long.MIN_VALUE / 2;

    public static void register(IEventBus modBus) {
        TICKET_TYPES.register(modBus);
        NeoForge.EVENT_BUS.addListener(Ferme::onStarted);
        NeoForge.EVENT_BUS.addListener(Ferme::onStopping);
        NeoForge.EVENT_BUS.addListener(Ferme::onCommands);
        NeoForge.EVENT_BUS.addListener(Ferme::onTick);
    }

    // ------------------------------------------------------------------ pose et retrait

    /** Pose le ticket de ferme sur ce chunk. Idempotent : vanilla fusionne deux tickets identiques. */
    public static void anchor(ServerLevel level, ChunkPos chunk) {
        level.getChunkSource().addTicketWithRadius(TICKET.get(), chunk, TICKET_RADIUS);
        if (CHUNKS.computeIfAbsent(level.dimension(), key -> new LongOpenHashSet()).add(chunk.pack())) {
            total++;
            recomputeHalo(level.dimension());
        }
    }

    /** Retire le ticket de ferme de ce chunk. */
    public static void release(ServerLevel level, ChunkPos chunk) {
        level.getChunkSource().removeTicketWithRadius(TICKET.get(), chunk, TICKET_RADIUS);
        LongOpenHashSet set = CHUNKS.get(level.dimension());
        if (set != null && set.remove(chunk.pack())) {
            total--;
            recomputeHalo(level.dimension());
        }
    }

    private static void recomputeHalo(ResourceKey<Level> dimension) {
        LongOpenHashSet set = CHUNKS.get(dimension);
        if (set == null || set.isEmpty()) {
            HALO.remove(dimension);
            return;
        }
        LongOpenHashSet halo = new LongOpenHashSet(set.size() * 9);
        set.forEach((long packed) -> {
            ChunkPos center = ChunkPos.unpack(packed);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    halo.add(ChunkPos.pack(center.x() + dx, center.z() + dz));
                }
            }
        });
        HALO.put(dimension, halo);
    }

    // ------------------------------------------------------------------ tests chauds

    /** Ce chunk porte-t-il une Ancre de ferme active ? */
    public static boolean isFarm(ServerLevel level, long chunk) {
        if (total == 0 || !Settings.ferme()) {
            return false;
        }
        LongOpenHashSet set = CHUNKS.get(level.dimension());
        return set != null && set.contains(chunk);
    }

    public static boolean isFarm(ServerLevel level, ChunkPos chunk) {
        return total != 0 && isFarm(level, chunk.pack());
    }

    /** Ce chunk est-il une ferme, ou l'un de ses huit voisins ? Voir {@link #HALO}. */
    public static boolean nearFarm(ServerLevel level, long chunk) {
        if (total == 0 || !Settings.ferme()) {
            return false;
        }
        LongOpenHashSet halo = HALO.get(level.dimension());
        return halo != null && halo.contains(chunk);
    }

    /**
     * Cette entité se trouve-t-elle dans une ferme ou son pourtour ? Appelé pour chaque entité à
     * chaque tick par {@link EntityThrottle} : la première comparaison règle le cas de tout serveur
     * sans ferme.
     */
    public static boolean covers(Entity entity) {
        return total != 0 && entity.level() instanceof ServerLevel level
                && nearFarm(level, entity.chunkPosition().pack());
    }

    /** Parcourt les chunks « ferme » de cette dimension. */
    public static void forEach(ServerLevel level, LongConsumer action) {
        if (total == 0 || !Settings.ferme()) {
            return;
        }
        LongOpenHashSet set = CHUNKS.get(level.dimension());
        if (set != null) {
            set.forEach(action);
        }
    }

    // ------------------------------------------------------------------ joueur d'une autre dimension

    /**
     * Un joueur connecté, hors spectateurs, pour une ferme dont la dimension n'en a aucun ; nul si
     * personne n'est en ligne ou si {@code ferme_toute_dimension} est coupé.
     *
     * <p>« Connecté » veut dire : dans la liste des joueurs du serveur. Les faux joueurs des autres
     * mods n'y sont pas, et ne font donc pas tourner une ferme sur un serveur vide. Les spectateurs
     * non plus : vanilla ne fait rien naître autour d'eux. Seul le témoin du banc d'essai
     * ({@link Temoin}, {@code -Dlanterne.labo=true}) compte en plus.
     *
     * <p>Appelé seulement quand vanilla n'a trouvé personne dans la dimension, et pour un chunk
     * « ferme » : le parcours de quelques joueurs ne pèse rien.
     */
    public static @Nullable Player ailleurs(ServerLevel level) {
        if (total == 0 || !Settings.fermeTouteDimension()) {
            return null;
        }
        for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
            if (!player.isSpectator() && !(player instanceof FakePlayer)) {
                return player;
            }
        }
        for (FakePlayer temoin : Temoin.ACTIFS.keySet()) {
            return temoin;
        }
        return null;
    }

    /**
     * La dimension n'a-t-elle aucun joueur, au sens de vanilla ({@code getNearestPlayer} : hors
     * spectateurs) ? C'est le seul cas où {@link #ailleurs} entre en jeu.
     */
    public static boolean sansJoueur(ServerLevel level) {
        for (ServerPlayer player : level.players()) {
            if (!player.isSpectator()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Faut-il rétablir la catégorie MONSTER que le plafond global de vanilla vient d'écarter ? Oui
     * pour une dimension qui porte une ferme et n'a aucun joueur, alors qu'un joueur est connecté
     * ailleurs — et tant qu'elle compte moins de monstres non persistants que le plafond d'un joueur
     * seul (70). Voir l'étage 6 du Javadoc de classe. Une fois par tick et par dimension.
     */
    public static boolean plafondGlobalPourFerme(ServerLevel level, int monstres) {
        if (total == 0 || !Settings.fermeTouteDimension()) {
            return false;
        }
        LongOpenHashSet set = CHUNKS.get(level.dimension());
        return set != null && !set.isEmpty()
                && monstres < MobCategory.MONSTER.getMaxInstancesPerChunk()
                && sansJoueur(level) && ailleurs(level) != null;
    }

    /** Un monstre va naître dans une ferme sans joueur dans sa dimension (rapport seulement). */
    public static void noteAilleurs() {
        ailleursNes++;
    }

    /**
     * Le plafond local de la ferme : reste-t-il de la place pour un monstre de plus ?
     *
     * <p>On compte les monstres dans la boîte du chunk élargie de {@link #RAYON_PLAFOND} blocs, sur
     * toute la hauteur — une ferme a sa salle d'apparition en haut et sa chambre de mort en bas, et
     * les deux comptent. Le parcours s'arrête dès le plafond atteint.
     */
    public static boolean roomFor(ServerLevel level, ChunkPos chunk) {
        int cap = Settings.fermePlafond();
        long now = level.getGameTime();
        long key = chunk.pack();
        if (cap <= 0 || now < PLEINE_JUSQUA.get(key)) {
            plafonnees++;
            return false;
        }
        int count = countMonsters(level, chunk, cap);
        if (count >= cap) {
            // Salle pleine : on ne recompte pas avant une demi-seconde. Une ferme au plafond est
            // l'état normal d'une ferme qui tourne, et recompter vingt fois par seconde une salle
            // qu'aucun monstre n'a quittée serait le seul coût récurrent de ce module.
            PLEINE_JUSQUA.put(key, now + RECOMPTE_APRES);
            plafonnees++;
            return false;
        }
        admises++;
        placesLibres = cap - count;
        return true;
    }

    /**
     * Les places que {@link #roomFor} vient de trouver libres, pour la tentative en cours ; -1 hors
     * d'une tentative décidée par la ferme. Fil serveur seulement.
     */
    private static int placesLibres = -1;

    /**
     * Un monstre va naître : prend-il une place de la ferme ? Toujours oui hors d'une tentative de
     * la ferme (joueur proche, autre catégorie, chunk ordinaire) ; sinon oui tant qu'il en reste.
     */
    public static boolean prendrePlace() {
        if (placesLibres < 0) {
            return true;
        }
        if (placesLibres == 0) {
            plafonnees++;
            return false;
        }
        placesLibres--;
        return true;
    }

    /** Clôt la tentative en cours : voir {@link #placesLibres}. */
    public static void finTentative() {
        placesLibres = -1;
    }

    private static AABB box(ServerLevel level, ChunkPos chunk) {
        return new AABB(chunk.getMinBlockX() - RAYON_PLAFOND, level.getMinY(),
                chunk.getMinBlockZ() - RAYON_PLAFOND, chunk.getMaxBlockX() + 1 + RAYON_PLAFOND,
                level.getMaxY(), chunk.getMaxBlockZ() + 1 + RAYON_PLAFOND);
    }

    /**
     * Les monstres vivants et <b>simulés</b> autour d'une ferme, jusqu'à {@code limit}.
     *
     * <p>Les deux adjectifs ont été appris au banc. « Vivants » : un monstre tué dans un chunk qui ne
     * tique pas reste indéfiniment « en train de mourir », présent pour le jeu, absent pour
     * {@code @e}. « Simulés » : un slime né sur le toit de la ferme qui saute dans le chunk voisin
     * s'y fige (les voisins d'un chunk forcé ne tiquent pas leurs entités). Les compter tous, c'était
     * voir la salle pleine avec vingt-sept monstres sur quarante — la ferme s'étouffait d'elle-même.
     * Les figés sont retirés par {@link #sweep}.
     */
    private static int countMonsters(ServerLevel level, ChunkPos chunk, int limit) {
        int[] count = {0};
        level.getEntities().get(EntityTypeTest.forClass(Mob.class), box(level, chunk), mob -> {
            if (mob.getType().getCategory() == MobCategory.MONSTER && mob.isAlive()
                    && level.isPositionEntityTicking(mob.blockPosition()) && ++count[0] >= limit) {
                return net.minecraft.util.Continuation.ABORT;
            }
            return net.minecraft.util.Continuation.CONTINUE;
        });
        return count[0];
    }

    /** Un monstre d'une ferme vient d'échapper à la disparition instantanée à distance. */
    public static void noteSpared() {
        epargnees++;
    }

    // ------------------------------------------------------------------ démarrage

    /**
     * Retrouve les fermes dans les tickets rechargés du disque.
     *
     * <p>Au démarrage, vanilla range les tickets lus dans {@code chunk_tickets.dat} parmi les
     * « désactivés » avant de les réactiver : on lit donc les deux tables. Une fois par démarrage et
     * par dimension, sur quelques dizaines de tickets persistants au plus.
     */
    public static void rebuild(MinecraftServer server) {
        CHUNKS.clear();
        HALO.clear();
        PLEINE_JUSQUA.clear();
        total = 0;
        TicketType type = TICKET.get();
        for (ServerLevel level : server.getAllLevels()) {
            TicketStorageAccessor storage = (TicketStorageAccessor)
                    ((ChunkMapTicketsAccessor) level.getChunkSource().chunkMap).lanterne$ticketStorage();
            LongOpenHashSet set = new LongOpenHashSet();
            collect(storage.lanterne$tickets(), type, set);
            collect(storage.lanterne$deactivatedTickets(), type, set);
            if (!set.isEmpty()) {
                CHUNKS.put(level.dimension(), set);
                total += set.size();
                recomputeHalo(level.dimension());
                set.forEach((long packed) -> {
                    ChunkPos chunk = ChunkPos.unpack(packed);
                    Lanterne.LOG.info("[ANCRE] ferme retrouvée au démarrage : chunk ({}, {}) dans {}.",
                            chunk.x(), chunk.z(), level.dimension().identifier());
                });
            }
        }
        if (total > 0) {
            Lanterne.LOG.info("[ANCRE] {} chunk(s) de ferme actif(s){}.", total,
                    Settings.ferme() ? "" : " — mais ancre_de_ferme=false : elles ne font rien apparaître");
        }
    }

    private static void collect(Long2ObjectOpenHashMap<List<Ticket>> tickets, TicketType type,
            LongOpenHashSet into) {
        for (Long2ObjectOpenHashMap.Entry<List<Ticket>> entry : tickets.long2ObjectEntrySet()) {
            for (Ticket ticket : entry.getValue()) {
                if (ticket.getType() == type) {
                    into.add(entry.getLongKey());
                    break;
                }
            }
        }
    }

    private static void onTick(ServerTickEvent.Post event) {
        if (total == 0 || !Settings.ferme()) {
            return;
        }
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % SWEEP_PERIOD != 0) {
            return;
        }
        for (ServerLevel level : server.getAllLevels()) {
            LongOpenHashSet set = CHUNKS.get(level.dimension());
            if (set != null && !set.isEmpty()) {
                sweep(level, set);
            }
        }
    }

    /**
     * Retire les monstres figés autour d'une ferme — ceux qui en sont sortis vers un chunk qui ne
     * tique pas.
     *
     * <h2>La fuite que ce balayage bouche</h2>
     *
     * <p>Les voisins d'un chunk forcé sont chargés, mais n'y tiquent que les blocs. Un monstre qui y
     * passe — un slime du toit, une araignée qui escalade — s'y fige : il ne vieillit plus, ne
     * disparaît plus, et reste chargé tant que la ferme l'est, c'est-à-dire toujours. Or le plafond
     * GLOBAL de vanilla ({@code NaturalSpawner.createState}) compte les monstres de tous les chunks
     * chargés : chaque figé volerait pour toujours une place aux joueurs, partout sur la carte.
     *
     * <p>Vanilla, s'il pouvait les tiquer, les effacerait sur-le-champ : ils sont à plus de 128 blocs
     * de tout joueur et ne sont plus dans la ferme. On applique donc exactement sa règle — mêmes
     * exemptions (persistants, nommés, {@code removeWhenFarAway} faux), même distance — une fois
     * toutes les trente secondes, sur la seule boîte de chaque ferme.
     */
    private static void sweep(ServerLevel level, LongOpenHashSet farms) {
        int[] removed = {0};
        farms.forEach((long packed) -> {
            ChunkPos chunk = ChunkPos.unpack(packed);
            List<Mob> frozen = new ArrayList<>();
            level.getEntities().get(EntityTypeTest.forClass(Mob.class), box(level, chunk), mob -> {
                if (mob.getType().getCategory() == MobCategory.MONSTER
                        && !mob.isPersistenceRequired() && !mob.requiresCustomPersistence()
                        && !level.isPositionEntityTicking(mob.blockPosition())) {
                    frozen.add(mob);
                }
                return net.minecraft.util.Continuation.CONTINUE;
            });
            for (Mob mob : frozen) {
                Player player = level.getNearestPlayer(mob, -1.0);
                double distance;
                if (player != null) {
                    distance = player.distanceToSqr(mob);
                } else if (ailleurs(level) != null) {
                    // Personne dans la dimension, quelqu'un ailleurs : le figé est « très loin », comme
                    // pour tout le reste de la ferme. Sans personne en ligne, on n'y touche pas.
                    distance = LOIN;
                } else {
                    continue;
                }
                int despawn = mob.getType().getCategory().getDespawnDistance();
                if (distance > (double) despawn * despawn && mob.removeWhenFarAway(distance)) {
                    mob.discard();
                    removed[0]++;
                }
            }
        });
        if (removed[0] > 0) {
            balayes += removed[0];
            long now = level.getGameTime();
            if (now - dernierJournalBalayage >= 12_000L) {
                dernierJournalBalayage = now;
                Lanterne.LOG.info("[ANCRE] ferme : {} monstre(s) figé(s) hors de la zone simulée retiré(s)"
                        + " dans {} ({} depuis le démarrage ; prochain relevé dans 10 min au plus tôt).",
                        removed[0], level.dimension().identifier(), balayes);
            }
        }
    }

    private static void onStarted(ServerStartedEvent event) {
        rebuild(event.getServer());
    }

    private static void onStopping(ServerStoppingEvent event) {
        Temoin.dismissAll();
    }

    // ------------------------------------------------------------------ commande

    private static void onCommands(RegisterCommandsEvent event) {
        register(event.getDispatcher());
    }

    /**
     * {@code /lanterne ferme} : l'état des fermes. Et, sur un serveur lancé avec
     * {@code -Dlanterne.labo=true} seulement, {@code /lanterne ferme temoin} : voir {@link Temoin}.
     */
    private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        var ferme = Commands.literal("ferme")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .executes(context -> {
                    CommandSourceStack source = context.getSource();
                    source.sendSuccess(() -> Component.literal(describe()), false);
                    for (ServerLevel level : source.getServer().getAllLevels()) {
                        LongOpenHashSet set = CHUNKS.get(level.dimension());
                        if (set == null) {
                            continue;
                        }
                        set.forEach((long packed) -> {
                            ChunkPos chunk = ChunkPos.unpack(packed);
                            int monsters = countMonsters(level, chunk, Integer.MAX_VALUE);
                            source.sendSuccess(() -> Component.literal(String.format(
                                    "  %s chunk (%d, %d) blocs (%d, %d) : %d monstre(s) à %d blocs, %s",
                                    level.dimension().identifier(), chunk.x(), chunk.z(),
                                    chunk.getMinBlockX(), chunk.getMinBlockZ(), monsters,
                                    RAYON_PLAFOND,
                                    level.getChunkSource().chunkMap.anyPlayerCloseEnoughForSpawning(chunk)
                                            ? "un joueur est proche (règles vanilla)"
                                            : !sansJoueur(level)
                                                    ? "aucun joueur proche (règles de la ferme)"
                                                    : ailleurs(level) != null
                                                            ? "aucun joueur dans la dimension, un joueur"
                                                                    + " connecté ailleurs (règles de la ferme)"
                                                            : Settings.fermeTouteDimension()
                                                                    ? "aucun joueur connecté : à l'arrêt"
                                                                    : "aucun joueur dans la dimension"
                                                                            + " (ferme_toute_dimension=false) :"
                                                                            + " à l'arrêt")), false);
                        });
                    }
                    return total;
                });
        if (Boolean.getBoolean("lanterne.labo")) {
            ferme.then(Commands.literal("temoin")
                    .then(Commands.literal("retirer").executes(context -> {
                        int removed = Temoin.dismissAll();
                        context.getSource().sendSuccess(
                                () -> Component.literal("[ANCRE] témoin(s) retiré(s) : " + removed), true);
                        return removed;
                    }))
                    .then(Commands.argument("x", IntegerArgumentType.integer())
                            .then(Commands.argument("z", IntegerArgumentType.integer())
                                    .executes(context -> {
                                        ServerLevel level = context.getSource().getLevel();
                                        int x = IntegerArgumentType.getInteger(context, "x");
                                        int z = IntegerArgumentType.getInteger(context, "z");
                                        Temoin.summon(level, x, z);
                                        context.getSource().sendSuccess(() -> Component.literal(
                                                "[ANCRE] témoin posé en (" + x + ", " + z + ") dans "
                                                        + level.dimension().identifier()), true);
                                        return 1;
                                    }))));
        }
        dispatcher.register(Commands.literal("lanterne").then(ferme));
    }

    public static String describe() {
        return String.format("[ANCRE] fermes : %d chunk(s), %s, plafond %d ; apparitions admises %d,"
                        + " refusées au plafond %d, disparitions instantanées évitées %d (par tick),"
                        + " monstres figés retirés %d ; joueur d'une autre dimension %s, tentatives"
                        + " ainsi %d",
                total, Settings.ferme() ? "actives" : "COUPÉES (ancre_de_ferme=false)",
                Settings.fermePlafond(), admises, plafonnees, epargnees, balayes,
                Settings.fermeTouteDimension() ? "accepté" : "refusé (ferme_toute_dimension=false)",
                ailleursNes);
    }

    // ------------------------------------------------------------------ banc d'essai

    /**
     * Le témoin : un joueur connecté, loin, pour le banc d'essai seulement.
     *
     * <p>L'Ancre de ferme exige un joueur connecté (dans sa dimension si
     * {@code ferme_toute_dimension=false}), et un serveur d'essai n'en a pas. Le témoin est un {@link FakePlayer} de NeoForge qu'on rend visible aux deux seules
     * questions que le jeu pose ici : « qui est le joueur le plus proche ? » ({@code level.players()},
     * que lit {@code getNearestPlayer}) et « combien de chunks entourent des joueurs ? » (le
     * {@code DistanceManager}, qui fixe le plafond global de vanilla et charge le terrain autour de
     * lui comme pour un vrai joueur). Il n'est pas dans la {@code PlayerMap} du {@code ChunkMap} :
     * aucune apparition n'a donc lieu autour de lui, ce qui laisse la mesure propre.
     *
     * <p>Commande absente sans {@code -Dlanterne.labo=true} : jamais sur un serveur de jeu.
     */
    static final class Temoin {
        static final Map<FakePlayer, SectionPos> ACTIFS = new HashMap<>();

        static void summon(ServerLevel level, int x, int z) {
            UUID id = UUID.nameUUIDFromBytes(("lanterne-temoin-" + x + "," + z).getBytes());
            FakePlayer fake = FakePlayerFactory.get(level, new GameProfile(id, "Temoin"));
            fake.setPos(x + 0.5, level.getMaxY() - 8, z + 0.5);
            SectionPos section = SectionPos.of(fake);
            if (!level.players().contains(fake)) {
                level.players().add(fake);
            }
            level.getChunkSource().chunkMap.getDistanceManager().addPlayer(section, fake);
            ACTIFS.put(fake, section);
            Lanterne.LOG.info("[ANCRE] témoin de banc posé en ({}, {}) : {} joueur(s) vu(s) par la dimension.",
                    x, z, level.players().size());
        }

        static int dismissAll() {
            int n = ACTIFS.size();
            for (Map.Entry<FakePlayer, SectionPos> entry : ACTIFS.entrySet()) {
                FakePlayer fake = entry.getKey();
                ServerLevel level = fake.level();
                level.getChunkSource().chunkMap.getDistanceManager().removePlayer(entry.getValue(), fake);
                level.players().remove(fake);
            }
            ACTIFS.clear();
            return n;
        }
    }
}
