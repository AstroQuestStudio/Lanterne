package fr.clubcitrouille.lanterne.core;

import java.util.HashSet;
import java.util.Set;
import java.util.WeakHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;

/**
 * Le leurre : anti x-ray par obfuscation de section, façon Orebfuscator/Paper anti-xray.
 *
 * <h2>Le principe, et pourquoi c'est la seule réponse possible</h2>
 *
 * <p>Un pack de ressources x-ray ne fait rien d'illégitime au sens du protocole : il change des
 * textures pour un client qui a le droit d'en changer. Ce qu'il exploite est un fait du jeu que
 * rien côté client ne peut corriger — le serveur envoie déjà, pour chaque chunk chargé, la donnée de
 * bloc de <b>toutes</b> les sections de la colonne, y compris celles à cent mètres sous les pieds du
 * joueur, jamais vues, jamais creusées. Rendre la pierre transparente suffit alors à voir le filon,
 * parce que le filon était déjà là, dans la mémoire du client, sous une texture opaque.
 *
 * <p>La seule réponse qui ferme ce trou agit donc <b>avant</b> l'envoi, sur les données elles-mêmes :
 * si le client ne reçoit jamais le vrai bloc, aucune texture ne peut le montrer. C'est ce que ce
 * module fait, et rien de plus — il ne touche ni au rendu, ni au pack du joueur, ni à quoi que ce
 * soit côté client.
 *
 * <h2>Le point d'accroche réel, vérifié par {@code javap -p -c -constants} sur le jar patché 26.3</h2>
 *
 * <p>{@code ClientboundLevelChunkPacketData(LevelChunk)} — le constructeur appelé par {@code
 * ClientboundLevelChunkWithLightPacket}, lui-même construit par {@code Emballage.paquet(...)} (voir
 * {@link Emballage} et {@code EmballageEnvoiMixin}) — délègue tout le travail à deux méthodes
 * statiques, toutes deux lisant {@code chunk.getSections()} :
 *
 * <pre>
 * calculateChunkSize(LevelChunk)              // additionne section.getSerializedSize()
 * extractChunkData(FriendlyByteBuf, LevelChunk)  // appelle section.write(buf) pour chaque section
 * </pre>
 *
 * <p>{@code LevelChunkSection.write(FriendlyByteBuf)} (même jar, même passe) écrit successivement
 * {@code nonEmptyBlockCount}, {@code fluidCount}, {@code states.write(buf)} puis {@code
 * biomes.write(buf)} — {@code states} étant le {@code PalettedContainer<BlockState>} de la section,
 * la même palette que {@code core.Loterie} et {@code OreCache} (côté AutoMiner) parcourent déjà pour
 * d'autres raisons. {@code getSerializedSize()} additionne les tailles sérialisées des deux mêmes
 * conteneurs. <b>Les deux méthodes doivent donc voir exactement la même donnée</b> — un
 * désaccord entre la taille annoncée et les octets réellement écrits lève une
 * {@code IllegalStateException} dans {@code extractChunkData} (vérifié dans le même désassemblage) :
 * c'est pourquoi {@link #voile} est appelée aux DEUX points, avec le même résultat mis en cache par
 * section plutôt que recalculé indépendamment deux fois.
 *
 * <p>{@code LeurreEmballageMixin} pose les deux {@code @Redirect} sur ces appels précis. Ce fichier ne
 * fait que la décision : quelle section envoyer, la vraie ou une doublure.
 *
 * <h2>Ce qui est cacheé, et le critère d'exposition</h2>
 *
 * <p>Une position protégée (voir {@link Config#LEURRE_BLOCKS}, par défaut tous les minerais vanilla,
 * leurs variantes deepslate et le débris antique) est envoyée telle quelle dès qu'au moins une de ses
 * six faces touche une case dont {@link BlockState#isSolidRender()} rend faux — la même notion
 * d'opacité que {@link Shroud} utilise déjà pour une raison voisine (occlusion de rendu), reprise ici
 * plutôt que réinventée. Sinon, elle est remplacée par le bloc d'une case voisine solide et NON
 * protégée — c'est-à-dire, presque toujours, la roche qui l'entoure réellement à cet endroit précis.
 *
 * <p>Cette dernière décision évite délibérément une table Y/dimension codée en dur : le remplissage
 * choisi est toujours un bloc qui EXISTE déjà à un pas de la position cachée, donc toujours plausible
 * — pierre en surface, deepslate en profondeur, netherrack au Nether, tuf dans une poche, ou
 * n'importe quel bloc de terrain d'un mod dont ce dépôt n'a jamais entendu parler. Le seul repli est
 * {@code Blocks.STONE} pour le cas dégénéré où les six voisins sont eux-mêmes protégés (un filon
 * compact d'un seul tenant, sans aucune matrice autour dans cette section) — voir {@link
 * #remplissageSiCache}.
 *
 * <h2>Performance : une fois par section, jamais par bloc ni par tick</h2>
 *
 * <p>{@link #voile} met en cache son résultat par section — la clé est l'objet {@link
 * LevelChunkSection} réel lui-même, dans une {@link WeakHashMap} : aucun budget à gérer, une section
 * déchargée (donc son objet devenu inatteignable) sort du cache toute seule au passage du ramasse-
 * miettes, sans code d'éviction à écrire. Le court-circuit le plus fréquent, avant même le cache, est
 * {@code section.maybeHas(...)} — une lecture de PALETTE, pas des quatre mille quatre-vingt-seize
 * cases — qui écarte sans en lire une seule la quasi-totalité des sections, exactement comme {@code
 * OreCache} (AutoMiner) et {@link Loterie} le font déjà pour des raisons voisines.
 *
 * <p>L'invalidation ({@code LeurreInvalidationMixin}, posée sur {@code LevelChunk.setBlockState}) ne
 * jette que la section touchée, et sa voisine directe quand la face modifiée est sur un bord de
 * section (haut/bas dans le même chunk ; est/ouest/nord/sud dans le chunk voisin, s'il est chargé) —
 * jamais tout le cache, jamais un recalcul programmé. Voir {@code lab/Palissade} pour la mesure
 * réelle du coût, avant/après, sur un chantier chargé en minerais.
 *
 * <h2>La tension avec AutoMiner — lue dans son code réel, pas supposée</h2>
 *
 * <p>{@code ClubCitrouilleAutoMiner/.../bot/OreCache.java#examine} — le cache de filons du bot — lit
 * {@code section.getBlockState(x, y, z)} pour les <b>quatre mille quatre-vingt-seize positions</b>
 * d'une section dès que sa palette laisse passer {@code Tags.Blocks.ORES}, sans aucune condition
 * d'exposition : il ne se limite pas à ce qui est adjacent à une case déjà creusée. C'est un balayage
 * complet de la section, structurellement indiscernable d'un x-ray pour le serveur — sauf qu'il porte
 * sur des données déjà reçues du serveur ({@code Level} y est un {@code ClientLevel}), jamais sur des
 * données obtenues autrement.
 *
 * <p>Or c'est précisément ce que ce module retire de ce que le serveur envoie. Le résultat est
 * mécanique, pas une supposition : un filon non exposé n'atteint plus jamais la mémoire du client, et
 * {@code OreCache.examine} — comme {@code isSolidRender} pour un pack x-ray — n'a plus rien à y lire.
 * {@code Prospector.sightings()} et donc {@code OreVision} cessent de montrer un filon tant qu'il
 * n'est pas exposé, exactement comme pour un joueur qui regarde la roche sans pack.
 *
 * <p><b>Ce n'est pas contourné ici, et ce n'est pas un défaut caché.</b> C'est une vraie tension de
 * conception, remontée telle quelle : l'anti x-ray et une prévision de filons à travers la roche non
 * creusée ne peuvent pas coexister sans qu'un mécanisme sache distinguer le bot du pack — et rien,
 * côté serveur, ne le peut <b>au sens général</b>. Ce que ce module GARANTIT malgré cette tension :
 * {@link #estProtege} ne s'applique qu'à un ensemble de blocs choisi ({@link Config#LEURRE_BLOCKS}),
 * et l'exposition suit l'avancement réel de la mine — dès qu'AutoMiner (ou un joueur) creuse et
 * expose une face, le prochain envoi du chunk porte la vraie donnée par la voie normale de mise à
 * jour de bloc, comme pour n'importe quel bloc modifié.
 *
 * <h2>La décision : AutoMiner voit tout, tout le reste reste voilé</h2>
 *
 * <p>La tension ci-dessus n'est pas résolue en affaiblissant la protection : elle est résolue en la
 * rendant <b>par connexion</b> plutôt que globale. C'est un choix explicite de l'administrateur de ce
 * serveur, pas une concession technique : AutoMiner est le bot officiel du Club Citrouille, sa
 * détection de filons non exposés est une fonctionnalité voulue, et un pack x-ray tiers doit rester
 * bloqué. Les deux ne sont possibles ensemble que si le serveur peut distinguer l'un de l'autre —
 * c'est tout l'objet de {@link #EXEMPT}.
 *
 * <h2>Le signal retenu, et pourquoi ce n'est PAS ce qui avait d'abord été envisagé</h2>
 *
 * <p>L'idée de départ — lire une liste complète des mods chargés par le client, distincte de tout
 * canal réseau, exposée pendant la poignée de main FML/NeoForge — a été <b>vérifiée fausse</b> pour ce
 * moteur (NeoForge 26.3.0.3-beta), pas supposée. Trois lectures de bytecode l'établissent (détail
 * complet dans la Javadoc de {@code core.network.LeurreNet}) : {@code ServerLoginPacketListenerImpl}
 * ne porte plus aucune trace de mod list depuis la réécriture du réseau NeoForge (post-1.20.2) ;
 * {@code ServerConfigurationPacketListenerImpl} ne négocie que des <b>canaux</b> ; et le jar NeoForge
 * lui-même ne conserve par connexion que des ensembles de canaux
 * ({@code ChannelAttributes.PAYLOAD_SETUP}), jamais une liste brute d'identifiants de mod. Un mod sans
 * canal réseau est donc, dans ce moteur précis, structurellement invisible au serveur — et AutoMiner
 * n'en a aucun (vérifié : {@code grep} sans résultat sur {@code PayloadRegistrar}/
 * {@code NetworkRegistry}/{@code SimpleChannel} dans son code).
 *
 * <p>Le signal réellement retenu est donc un <b>canal d'identité</b> que Lanterne — obligatoire des
 * deux côtés — prête à AutoMiner sans toucher à son code : {@code core.network.AutominerPresence}, un
 * paquet à zéro champ, jamais envoyé. La classe cliente de Lanterne ne le déclare, pendant la
 * négociation réseau, que si {@code ModList.get().isLoaded("autominer")} répond vrai — une lecture
 * LOCALE du chargeur de mods, dans la même JVM que le jeu qui tourne, pas une affirmation reçue du
 * réseau. Côté serveur, {@link fr.clubcitrouille.lanterne.mixin.LeurreConnexionMixin} lit le résultat
 * de la négociation via {@code ServerGamePacketListenerImpl.hasChannel(...)} — délègue vérifié par
 * désassemblage à {@code NetworkRegistry.hasChannel(Connection, ConnectionProtocol, Identifier)} — au
 * point exact où la connexion cible est connue : {@code PlayerChunkSender.sendChunk}. Voir la Javadoc
 * de ce mixin pour pourquoi ce point précis, et pas celui où {@link #voile} est appelée elle-même.
 *
 * <h2>Pourquoi ce signal est raisonnable ici, et ce qu'il ne prétend pas être</h2>
 *
 * <p>Ce n'est PAS une preuve cryptographique, et ça ne prétend pas l'être : un joueur qui écrirait son
 * propre faux mod portant l'identifiant {@code autominer} et rien d'autre obtiendrait la même
 * exemption sans faire tourner le vrai bot. Aucun signal auto-déclaré ne peut fermer cette porte —
 * c'est vrai de n'importe quel mécanisme d'identité côté client, sur n'importe quel serveur, avec
 * n'importe quel moteur. Ce qui compte est le NIVEAU de friction que ça impose, comparé à ce que ça
 * empêche :
 *
 * <ul>
 *   <li>un pack de ressources ne peut structurellement RIEN ici — il ne participe à aucune poignée de
 *       main, ne charge aucun code, ne peut pas déclarer de canal. C'est la menace réelle que ce
 *       module visait à l'origine (« son xray doit fonctionner mais les autres mods d'xray non »), et
 *       elle reste entièrement bloquée ;</li>
 *   <li>un vrai mod x-ray tiers devrait se faire passer pour AutoMiner — changer son propre
 *       identifiant de mod en {@code autominer} dans son {@code neoforge.mods.toml}. NeoForge ne
 *       l'empêche pas techniquement, mais ce n'est plus « installer un pack », c'est écrire ou modifier
 *       un mod NeoForge en connaissant le nom exact du canal à imiter ({@code autominer_presence}), un
 *       effort d'un tout autre ordre pour un serveur privé entre amis ;</li>
 *   <li>ce serveur n'est <b>pas</b> compétitif à fort enjeu — c'est un serveur privé du Club Citrouille,
 *       entre joueurs qui se connaissent. Le seuil de sécurité recherché est « décourager un pack
 *       téléchargé en deux clics », pas « résister à un attaquant qui recompile un mod ». Sur un
 *       serveur public à forts enjeux (PvP compétitif, économie réelle), ce signal seul serait
 *       insuffisant — il faudrait alors une vraie authentification du binaire, hors de portée d'un
 *       canal réseau auto-déclaré quel qu'il soit.</li>
 * </ul>
 *
 * <p>AutoMiner reste donc utilisable pour miner à distance à travers la roche — c'est la fonctionnalité
 * voulue par l'administrateur, pas un bug — tandis qu'un pack x-ray téléchargé par un joueur ordinaire
 * reste aveugle à tout ce que ce module protège, exactement comme sans AutoMiner du tout.
 *
 * <h2>Le Regard (HUD façon Jade), en comparaison : aucun conflit</h2>
 *
 * <p>{@code client/regard/Sight.java} lit {@code mc.hitResult}, le résultat du tir au but standard du
 * jeu (curseur), qui ne peut désigner qu'un bloc réellement touché par un rayon sans obstacle depuis
 * l'œil du joueur — c'est-à-dire déjà exposé par construction. Le Regard ne peut donc jamais viser un
 * bloc que ce module aurait caché : si le bloc est caché, le rayon s'arrête sur son remplissage avant
 * de l'atteindre, exactement comme il s'arrêterait sur la vraie pierre en vanilla.
 */
public final class Leurre {
    private Leurre() {}

    // ------------------------------------------------------------------------------------------
    // Réglages résolus : recalculés seulement quand Settings.epoch() bouge.
    // ------------------------------------------------------------------------------------------

    private static long epochVue = -1L;
    private static Set<Block> proteges = Set.of();

    // ------------------------------------------------------------------------------------------
    // Le cache : une section réelle vaut la section à envoyer (elle-même, ou une doublure).
    // ------------------------------------------------------------------------------------------

    private static final Object VERROU = new Object();
    private static final WeakHashMap<LevelChunkSection, LevelChunkSection> CACHE = new WeakHashMap<>();

    /**
     * Le cache est-il vide ? Lu HORS du verrou — {@link #invalidate} est appelée à chaque pose ou
     * casse de bloc dans le monde entier, et doit pouvoir s'arrêter là sans jamais prendre le
     * moniteur tant que rien n'a été mis en cache (module éteint, ou aucune section n'a encore
     * rencontré de bloc protégé). Même construction que {@link Emballage#vide}, mêmes raisons.
     */
    private static volatile boolean vide = true;

    private static long sectionsVues;
    private static long sectionsSubstituees;
    private static long blocsCaches;
    private static long invalidations;

    // ------------------------------------------------------------------------------------------
    // La décision par connexion : voir la Javadoc de classe, section « Le signal retenu ».
    // ------------------------------------------------------------------------------------------

    /**
     * Cette connexion a-t-elle réellement AutoMiner chargé ? Posée par {@link
     * fr.clubcitrouille.lanterne.mixin.LeurreConnexionMixin} à l'entrée de {@code
     * PlayerChunkSender.sendChunk} — le seul point de la pile d'appels qui connaît la connexion cible
     * — et lue par {@link #voile}, plusieurs cadres plus bas, sans qu'aucun paramètre ne puisse
     * transporter l'information à travers le constructeur de vanilla qui les sépare.
     *
     * <p>{@code ThreadLocal} plutôt qu'un simple champ statique : {@code sendChunk} ne s'exécute
     * qu'un fil à la fois sur la machine visée (voir la Javadoc de {@link Emballage}, même hypothèse),
     * mais un {@code ThreadLocal} coûte la même chose ici — une lecture de champ — et reste correct
     * même si un jour ce chemin cessait d'être mono-fil. {@code withInitial(() -> FALSE)} : le repli
     * par défaut est toujours « pas exempté », jamais l'inverse — voir la Javadoc du mixin pour
     * pourquoi ce sens est le seul sûr.
     */
    private static final ThreadLocal<Boolean> EXEMPT = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /** Posé par {@code LeurreConnexionMixin} avant la construction du paquet de cette connexion. */
    public static void beginEnvoi(boolean exempt) {
        EXEMPT.set(exempt);
    }

    /** Retire l'état posé par {@link #beginEnvoi}, une fois le paquet de cette connexion construit. */
    public static void finEnvoi() {
        EXEMPT.set(Boolean.FALSE);
    }

    /**
     * Lu par {@link Emballage} pour distinguer ses deux variantes de paquet en cache — voir sa
     * Javadoc, section sur la clef de cache. Ne doit jamais être appelée hors d'un {@link #beginEnvoi}
     * / {@link #finEnvoi} en cours : hors de cette fenêtre, la valeur par défaut ({@code false}) est
     * correcte mais ne reflète aucune connexion réelle.
     */
    public static boolean exempt() {
        return EXEMPT.get();
    }

    /**
     * La section à envoyer : la vraie, ou une doublure dont les positions protégées non exposées
     * portent un bloc de remplissage. Appelée à la place de la section réelle aux deux points
     * d'accroche de {@code LeurreEmballageMixin} — voir la Javadoc de classe pour pourquoi les deux
     * doivent voir exactement le même résultat.
     *
     * <p>Une connexion exemptée ({@link #EXEMPT}) court-circuite tout le reste de cette méthode, y
     * compris {@link #rafraichitReglages} : elle rend toujours {@code section} telle quelle, sans
     * jamais consulter ni le cache ni la liste des blocs protégés. C'est délibérément la branche la
     * MOINS chère de toute cette classe — une lecture de {@code ThreadLocal} et un retour — parce
     * qu'elle doit rester quasi gratuite même si un jour la totalité d'un serveur se composait de
     * connexions exemptées.
     */
    public static LevelChunkSection voile(LevelChunk chunk, LevelChunkSection section) {
        if (!Settings.leurre() || EXEMPT.get()) {
            return section;
        }
        rafraichitReglages();
        if (proteges.isEmpty()) {
            return section;
        }

        LevelChunkSection resolue;
        synchronized (VERROU) {
            resolue = CACHE.get(section);
        }
        if (resolue != null) {
            return resolue;
        }

        resolue = calcule(chunk, section);
        synchronized (VERROU) {
            CACHE.put(section, resolue);
            vide = false;
        }
        return resolue;
    }

    private static LevelChunkSection calcule(LevelChunk chunk, LevelChunkSection section) {
        sectionsVues++;
        // Lecture de palette seule, pas des 4096 cases : voir la Javadoc de classe. Écarte sans
        // en lire une seule la quasi-totalité des sections du sous-sol.
        if (section.hasOnlyAir() || !section.maybeHas(Leurre::estProtege)) {
            return section;
        }

        LevelChunkSection[] secs = chunk.getSections();
        int index = -1;
        for (int i = 0; i < secs.length; i++) {
            if (secs[i] == section) {
                index = i;
                break;
            }
        }
        if (index < 0) {
            // Ne devrait jamais arriver : `section` vient de chunk.getSections() à l'appel côté
            // mixin. Filet de sécurité plutôt qu'une exception — servir la vraie section reste
            // correct, seulement pas protégée cette fois-ci.
            return section;
        }

        PalettedContainer<BlockState> source = section.getStates();
        PalettedContainer<BlockState> copie = null; // n'alloue que si une substitution a vraiment lieu
        int caches = 0;

        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    BlockState etat = source.get(x, y, z);
                    if (!estProtege(etat)) {
                        continue;
                    }
                    BlockState remplissage = remplissageSiCache(chunk, secs, index, x, y, z);
                    if (remplissage == null) {
                        continue; // au moins une face exposée : envoyée telle quelle
                    }
                    if (copie == null) {
                        copie = source.copy();
                    }
                    copie.set(x, y, z, remplissage);
                    caches++;
                }
            }
        }

        if (copie == null) {
            return section; // toutes les positions protégées de cette section étaient déjà exposées
        }
        sectionsSubstituees++;
        blocsCaches += caches;
        return new LevelChunkSection(copie, section.getBiomes());
    }

    /**
     * Le bloc de remplissage si cette position doit être cachée, ou {@code null} si elle est
     * exposée (au moins une des six faces non opaque) et doit donc partir avec sa vraie donnée.
     */
    private static BlockState remplissageSiCache(LevelChunk chunk, LevelChunkSection[] secs, int index,
            int x, int y, int z) {
        BlockState bas = voisin(chunk, secs, index, x, y, z, 0, -1, 0);
        BlockState haut = voisin(chunk, secs, index, x, y, z, 0, 1, 0);
        BlockState nord = voisin(chunk, secs, index, x, y, z, 0, 0, -1);
        BlockState sud = voisin(chunk, secs, index, x, y, z, 0, 0, 1);
        BlockState ouest = voisin(chunk, secs, index, x, y, z, -1, 0, 0);
        BlockState est = voisin(chunk, secs, index, x, y, z, 1, 0, 0);

        if (!opaque(bas) || !opaque(haut) || !opaque(nord) || !opaque(sud)
                || !opaque(ouest) || !opaque(est)) {
            return null;
        }
        // Le remplissage est un voisin réel, solide et non protégé — donc plausible par
        // construction, quels que soient la dimension, l'altitude ou le mod qui a posé ce terrain.
        // "bas" en premier : c'est la matière la plus souvent identique sur toute la hauteur d'un
        // filon (le palier stone/deepslate se franchit rarement à l'intérieur d'une seule section).
        BlockState[] candidats = {bas, haut, nord, sud, ouest, est};
        for (BlockState candidat : candidats) {
            if (candidat != null && !estProtege(candidat)) {
                return candidat;
            }
        }
        // Filon compact d'un seul tenant, sans aucune matrice dans cette section : repli universel.
        return Blocks.STONE.defaultBlockState();
    }

    /**
     * L'état d'une position voisine, en franchissant au besoin une section ou un chunk voisin.
     * {@code null} si elle est inconnue (bord du monde, ou chunk voisin non chargé) — traité par
     * {@link #opaque} comme opaque : dans le doute, la position reste cachée plutôt que de risquer
     * une fuite. Au plus un des trois deltas est non nul (appelée uniquement pour les six faces).
     */
    private static BlockState voisin(LevelChunk chunk, LevelChunkSection[] secs, int index,
            int x, int y, int z, int dx, int dy, int dz) {
        int nx = x + dx;
        int ny = y + dy;
        int nz = z + dz;
        if (nx >= 0 && nx <= 15 && ny >= 0 && ny <= 15 && nz >= 0 && nz <= 15) {
            return secs[index].getBlockState(nx, ny, nz);
        }
        if (ny < 0) {
            return index > 0 ? secs[index - 1].getBlockState(nx, 15, nz) : null;
        }
        if (ny > 15) {
            return index + 1 < secs.length ? secs[index + 1].getBlockState(nx, 0, nz) : null;
        }
        // Seul un axe horizontal peut encore être hors bornes ici : chunk voisin.
        Level level = chunk.getLevel();
        if (!(level instanceof ServerLevel serverLevel)) {
            return null;
        }
        int voisinCx = chunk.getPos().x() + (nx < 0 ? -1 : nx > 15 ? 1 : 0);
        int voisinCz = chunk.getPos().z() + (nz < 0 ? -1 : nz > 15 ? 1 : 0);
        LevelChunk voisinChunk = serverLevel.getChunkSource().getChunkNow(voisinCx, voisinCz);
        if (voisinChunk == null) {
            return null; // pas chargé : on ne sait rien, on reste prudent (voir Javadoc de la méthode)
        }
        LevelChunkSection[] secsVoisin = voisinChunk.getSections();
        if (index < 0 || index >= secsVoisin.length) {
            return null;
        }
        int wx = ((nx % 16) + 16) % 16;
        int wz = ((nz % 16) + 16) % 16;
        return secsVoisin[index].getBlockState(wx, ny, wz);
    }

    /** {@code null} (inconnu) compte comme opaque — voir la Javadoc de {@link #voisin}. */
    private static boolean opaque(BlockState etat) {
        return etat == null || etat.isSolidRender();
    }

    private static boolean estProtege(BlockState etat) {
        return proteges.contains(etat.getBlock());
    }

    private static void rafraichitReglages() {
        long epoch = Settings.epoch();
        if (epoch == epochVue) {
            return;
        }
        epochVue = epoch;
        Set<Block> resolu = new HashSet<>();
        for (String brut : Config.LEURRE_BLOCKS.get()) {
            Identifier id = Identifier.tryParse(brut);
            if (id == null) {
                continue;
            }
            Block bloc = BuiltInRegistries.BLOCK.getOptional(id).orElse(null);
            if (bloc != null && bloc != Blocks.AIR) {
                resolu.add(bloc);
            }
        }
        proteges = Set.copyOf(resolu);
        // La liste vient peut-être de changer : tout ce qui est en cache pourrait être faux (un
        // bloc retiré de la liste ne doit plus être caché, un bloc ajouté doit l'être dès le
        // prochain envoi). Purger est le seul moyen honnête de le garantir.
        purge();
    }

    /**
     * Cette position vient de changer : sa section, et sa voisine directe si la face touchée est
     * sur un bord de section, ne valent plus rien. Appelée depuis {@code LeurreInvalidationMixin},
     * à la tête de {@code LevelChunk.setBlockState} — le chemin chaud de ce module, comme pour
     * {@link Emballage#oublie}, d'où le même court-circuit sur {@link #vide}.
     */
    public static void invalidate(LevelChunk chunk, BlockPos pos) {
        if (vide) {
            return;
        }
        LevelChunkSection[] secs = chunk.getSections();
        int index = chunk.getSectionIndex(pos.getY());
        if (index < 0 || index >= secs.length) {
            return;
        }
        invalidations++;
        drop(secs[index]);

        int ly = pos.getY() & 15;
        if (ly == 0 && index > 0) {
            drop(secs[index - 1]);
        }
        if (ly == 15 && index + 1 < secs.length) {
            drop(secs[index + 1]);
        }

        int lx = pos.getX() & 15;
        int lz = pos.getZ() & 15;
        if (lx != 0 && lx != 15 && lz != 0 && lz != 15) {
            return; // pas sur un bord horizontal de section : aucun chunk voisin concerné
        }
        Level level = chunk.getLevel();
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        int cx = chunk.getPos().x();
        int cz = chunk.getPos().z();
        if (lx == 0) {
            dropChezVoisin(serverLevel, cx - 1, cz, index);
        }
        if (lx == 15) {
            dropChezVoisin(serverLevel, cx + 1, cz, index);
        }
        if (lz == 0) {
            dropChezVoisin(serverLevel, cx, cz - 1, index);
        }
        if (lz == 15) {
            dropChezVoisin(serverLevel, cx, cz + 1, index);
        }
    }

    private static void dropChezVoisin(ServerLevel level, int cx, int cz, int index) {
        // getChunkNow, jamais getChunk : un chunk voisin non chargé ne peut rien avoir en cache
        // ici, et cette invalidation ne doit surtout pas en provoquer le chargement.
        LevelChunk voisin = level.getChunkSource().getChunkNow(cx, cz);
        if (voisin == null) {
            return;
        }
        LevelChunkSection[] secs = voisin.getSections();
        if (index >= 0 && index < secs.length) {
            drop(secs[index]);
        }
    }

    private static void drop(LevelChunkSection section) {
        synchronized (VERROU) {
            if (CACHE.remove(section) != null && CACHE.isEmpty()) {
                vide = true;
            }
        }
    }

    /** Tout jeter — la liste de blocs protégés a changé, ou le module s'éteint. */
    public static void purge() {
        synchronized (VERROU) {
            CACHE.clear();
            vide = true;
        }
    }

    /** Sections dont l'obfuscation a été calculée (ou écartée par la palette) depuis le démarrage. */
    public static long sectionsVues() {
        return sectionsVues;
    }

    /** Sections où au moins une position protégée a effectivement été remplacée. */
    public static long sectionsSubstituees() {
        return sectionsSubstituees;
    }

    /** Positions effectivement remplacées, toutes sections confondues. */
    public static long blocsCaches() {
        return blocsCaches;
    }

    /** Invalidations traitées (section touchée + voisines) depuis le démarrage. */
    public static long invalidations() {
        return invalidations;
    }
}
