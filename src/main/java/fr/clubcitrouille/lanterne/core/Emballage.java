package fr.clubcitrouille.lanterne.core;

import java.lang.ref.WeakReference;
import java.util.BitSet;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;

import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.lighting.LevelLightEngine;

/**
 * L'emballage : le paquet d'un chunk n'est pas reconstruit à chaque envoi.
 *
 * <h2>Ce que la mesure a établi</h2>
 *
 * <p>Le banc du seuil ({@code lab/Seuil}) compare deux arrivées de joueur en ne faisant varier
 * qu'une condition. À distance de vue dix, sur un monde déjà écrit :
 *
 * <pre>
 * A - arrivee a FROID : 444 chunks, 2197 ms cumulees  (lire du disque PUIS emballer)
 * B - arrivee a CHAUD : 441 chunks,  939 ms cumulees  (chunks deja en memoire : emballage SEUL)
 * </pre>
 *
 * <p><b>Quarante-trois pour cent du coût d'une arrivée est de l'emballage pur.</b> Et il est repayé
 * <b>intégralement</b> à chaque connexion, chaque changement de dimension, chaque aller-retour, et
 * chaque fois qu'un chunk ressort puis rentre dans la distance de vue d'un joueur qui marche.
 * Aucune pré-génération ne l'enlève : pré-générer remplit le disque, pas ce tampon-là.
 *
 * <h2>D'où vient ce coût, exactement</h2>
 *
 * <p>{@code PlayerChunkSender.sendChunk} construit un {@code ClientboundLevelChunkWithLightPacket}
 * <b>par joueur et par envoi</b>. Ce paquet sérialise le chunk entier : les vingt-quatre sections
 * (états de blocs <em>et</em> biomes, bit-packés à travers leurs palettes), les cartes de hauteur,
 * l'étiquette de mise à jour de chaque entité de bloc, et une copie de chaque couche de lumière non
 * vide. Deux joueurs côte à côte reçoivent deux fois le même travail.
 *
 * <p>Ce module garde ce paquet et le rend tel quel au joueur suivant.
 *
 * <h2>Le point qui décide de tout : l'invalidation</h2>
 *
 * <p>Un paquet périmé montre au joueur un monde qui n'existe plus — un bloc cassé qui réapparaît,
 * un coffre qui revient. C'est <b>pire</b> que le coût qu'on voulait éviter, parce qu'un serveur
 * lent reste un serveur juste.
 *
 * <p>La règle qu'on s'est donnée est donc : <b>si on ne peut pas garantir l'invalidation sur un
 * chemin, ce chemin n'est pas mis en cache.</b> Voici ce que la lecture du code a donné.
 *
 * <h3>Le entonnoir principal : {@code LevelChunk.markUnsaved}</h3>
 *
 * <p>Tout ce qui rend un chunk différent de ce qui est sur le disque passe par là, et c'est
 * précisément la définition dont on a besoin. On y attrape :
 *
 * <ul>
 *   <li><b>les blocs</b> — {@code LevelChunk.setBlockState} termine par {@code markUnsaved()} ;</li>
 *   <li><b>le contenu des entités de bloc</b> — {@code BlockEntity.setChanged()} appelle
 *       {@code Level.blockEntityChanged(pos)}, qui appelle {@code markUnsaved()} sur le chunk. Cela
 *       couvre le texte d'un panneau, le motif d'une bannière, le profil d'une tête : tout ce que
 *       {@code getUpdateTag} met dans le paquet ;</li>
 *   <li><b>la lumière</b> — voir la section qui suit, elle mérite son paragraphe ;</li>
 *   <li><b>les biomes</b> — {@code /fillbiome} appelle {@code markUnsaved()} sur chaque chunk
 *       touché. Les biomes sont bien dans le paquet : {@code LevelChunkSection.write} écrit le
 *       conteneur d'états <em>puis</em> le conteneur de biomes ;</li>
 *   <li><b>les pièces annexes</b> — départs et références de structures, {@code setLightCorrect},
 *       les attachements NeoForge ({@code ChunkAccess.setData}/{@code removeData}), et la lumière
 *       auxiliaire de NeoForge ({@code LevelChunkAuxiliaryLightManager.setLightAt}).</li>
 * </ul>
 *
 * <h3>La lumière, et pourquoi elle est sûre</h3>
 *
 * <p>Le paquet embarque les données de lumière, et la lumière <b>peut changer sans qu'aucun bloc du
 * chunk ne change</b> : un bloc cassé dans le chunk voisin propage son obscurité par-dessus la
 * frontière. Il fallait donc un chemin à part, et il existe.
 *
 * <p>Ce que le paquet lit est la carte <em>visible</em> du stockage de lumière. Or cette carte ne
 * change qu'à un seul endroit — {@code LayerLightSectionStorage.swapSectionMap} — et cette méthode
 * publie la nouvelle carte <b>puis notifie immédiatement</b> chaque section touchée par
 * {@code chunkSource.onLightUpdate}. Cette notification traverse
 * {@code ServerChunkCache.onLightUpdate} (qui la reporte sur le fil principal), puis
 * {@code ChunkHolder.sectionLightChanged}, qui appelle {@code chunk.markUnsaved()} — <b>sans
 * condition</b>, avant même de regarder si le chunk est envoyable.
 *
 * <p>Publication et notification sont donc solidaires : il n'existe pas de lumière changée sans
 * invalidation qui suit. Le seul écart est le saut de fil — le calcul de lumière tourne sur son
 * propre fil, la notification s'exécute au prochain vidage de la file du fil principal, soit dans
 * le même tick. Vanilla vit exactement le même écart, et pire : il lit la carte de lumière depuis
 * le fil principal pendant que le fil de lumière l'écrit, sans aucune synchronisation. On n'ajoute
 * donc pas une classe de défaut, on élargit d'un tick au plus une fenêtre qui existe déjà.
 *
 * <h3>Les chemins qu'on a dû prendre à part</h3>
 *
 * <p>Trois trous dans l'entonnoir, trouvés en le lisant ligne à ligne, et bouchés chacun :
 *
 * <ol>
 *   <li><b>{@code setBlockState} a une sortie anticipée qui saute {@code markUnsaved}.</b> Après
 *       {@code section.setBlockState(...)} — donc après que la section a <em>déjà</em> changé — un
 *       {@code if (!section.getBlockState(...).is(newBlock)) return null;} sort sans rien marquer.
 *       Ce cas arrive quand la mise à jour des voisins a remplacé le bloc qu'on venait de poser.
 *       On s'accroche donc aussi en <b>tête</b> de {@code setBlockState}, ce qui rend la sortie
 *       anticipée sans effet sur nous.</li>
 *   <li><b>{@code setBlockEntity} et {@code removeBlockEntity} n'appellent pas
 *       {@code markUnsaved}.</b> Elles sont presque toujours atteintes depuis {@code setBlockState}
 *       — mais pas toujours : {@code getBlockEntity(..., IMMEDIATE)} crée une entité de bloc à la
 *       première lecture, et le ticker en retire une quand elle se signale supprimée. Or la liste
 *       des entités de bloc est dans le paquet. On s'accroche donc aux deux.</li>
 *   <li><b>{@code ChunkAccess.fillBiomesFromNoise} ne marque rien non plus.</b> La commande
 *       {@code /fillbiome} appelle {@code markUnsaved()} juste après, de son côté ; mais un mod qui
 *       repeint des biomes sans le faire laisserait des couleurs périmées. On s'accroche à la
 *       méthode elle-même, qui est froide.</li>
 * </ol>
 *
 * <h3>Ce qu'on a renoncé à couvrir, et pourquoi c'est sans conséquence</h3>
 *
 * <p>{@code ChunkAccess.setHeightmap} ne marque pas le chunk. Ses trois appelants écrivent tous
 * <b>avant</b> que le chunk existe comme {@code LevelChunk} envoyable — promotion depuis le
 * {@code ProtoChunk} (qui finit par {@code markUnsaved()}), lecture depuis le disque, et
 * {@code replaceWithPacketData}, qui est du code client. Il n'y a pas de fenêtre.
 *
 * <p>Les entités de bloc encore <em>empaquetées</em> ({@code pendingBlockEntities}, chargées mais
 * pas encore promues) ne figurent pas dans {@code getBlockEntities()} et donc pas dans le paquet —
 * exactement comme chez vanilla. Leur promotion passe par {@code setBlockEntity}, qu'on surveille :
 * le paquet est donc réemballé au moment où elles apparaissent.
 *
 * <h2>Le destinataire : rien dans ce paquet ne dépend de lui</h2>
 *
 * <p>Il fallait le vérifier avant d'envoyer le même octet à deux joueurs, et la réponse est nette.
 * Le paquet ne contient que la position, les sections, les cartes de hauteur, les étiquettes des
 * entités de bloc et la lumière — aucun de ces morceaux ne consulte le joueur. Les identifiants de
 * registre qui y sont écrits (type d'entité de bloc, biome, état de bloc) viennent des registres du
 * <b>serveur</b>, communs à toutes les connexions.
 *
 * <p>Un morceau, en revanche, dépend bien du moment de l'envoi : la lumière auxiliaire de NeoForge.
 * {@code sendChunk} n'envoie pas notre paquet seul, il l'emballe dans un lot avec une charge utile
 * {@code AuxiliaryLightDataPayload} construite par {@code sendLightDataTo}. C'est pour cela qu'on
 * s'accroche au <b>constructeur du paquet de chunk</b> et pas à l'envoi : le lot, lui, continue
 * d'être fabriqué frais à chaque fois, et la lumière auxiliaire reste à jour sans qu'on y touche.
 *
 * <p>Réutiliser un objet {@code Packet} sur plusieurs connexions est par ailleurs ce que vanilla
 * fait déjà : {@code ChunkHolder.broadcast} envoie une seule instance à tous les joueurs d'un
 * chunk, et {@code ClientboundChunkBatchStartPacket.INSTANCE} est un singleton. L'encodage ne lit
 * que des champs immuables.
 *
 * <h2>La mémoire : une borne en octets, et des références faibles là où il faut</h2>
 *
 * <p>La machine visée a <b>quatre gigaoctets en tout et un seul cœur</b>. Une arrivée à distance de
 * vue dix, c'est 441 chunks ; un paquet pèse de quelques kilo-octets (une dalle de pierre) à
 * plusieurs dizaines (une base construite, ou un chunk dont vingt couches de lumière sont non
 * vides, à 2 048 octets la couche). <b>Le rapport entre le plus petit et le plus gros est d'un
 * ordre de grandeur</b> : borner le cache en <em>nombre d'entrées</em> serait donc borner n'importe
 * quoi. La borne est en octets, et elle est à {@link #BUDGET}.
 *
 * <p>Les références faibles, elles, ne servent pas à ce qu'on croit. Elles ne portent <b>pas</b> le
 * paquet — un paquet tenu faiblement serait ramassé au premier soupir du GC et le cache ne
 * servirait plus à rien, tout en payant sa comptabilité. Elles portent le <b>chunk</b>, et
 * uniquement comme jeton d'identité :
 *
 * <ul>
 *   <li>une référence <em>forte</em> vers le chunk épinglerait en mémoire ses sections, ses entités
 *       de bloc et ses listes de ticks — des centaines de kilo-octets, contre quelques dizaines
 *       pour le paquet. Le cache retiendrait alors dix fois son propre poids en chunks déchargés.
 *       Sur une machine à quatre gigaoctets, c'est rédhibitoire ;</li>
 *   <li>en échange, on obtient une garantie <em>structurelle</em> plutôt que comptable : un chunk
 *       rechargé est un objet neuf, donc un défaut de cache certain. Même chose pour deux chunks de
 *       dimensions différentes qui tomberaient sur la même clé. On ne peut pas servir le paquet
 *       d'un autre chunk, jamais, même si l'on oubliait de l'évincer.</li>
 * </ul>
 *
 * <p>Ce que cela coûte, puisqu'il faut le chiffrer et pas le supposer : une {@link WeakReference}
 * de plus par entrée (trente-deux octets, un millième d'un paquet) et un appel à
 * {@link WeakReference#get()} par lecture de cache — soit une lecture de champ, plus une barrière
 * de chargement sur les ramasse-miettes concurrents. Cet appel a lieu une fois par <em>envoi de
 * chunk</em>, pas une fois par bloc : à côté des deux millisecondes que la mesure attribue à un
 * emballage, il est indétectable.
 *
 * <h2>Ce à quoi il ne faut pas s'attendre</h2>
 *
 * <p>Ce module ne fait rien pour la moitié froide de l'arrivée — les 1 258 millisecondes de lecture
 * de disque. Il s'attaque aux 939 autres, et seulement quand le même chunk part plus d'une fois.
 * Le premier joueur d'un serveur vide paie exactement le prix de vanilla, plus une insertion dans
 * une table.
 *
 * <p>Et un chunk qui change tout le temps ne se met pas en cache : une base avec des entonnoirs qui
 * tournent invalide son paquet à chaque transfert d'objet, par {@code setChanged}. C'est voulu, et
 * ce n'est pas grave — ce sont des chunks déjà chez leurs joueurs. Ce que le cache sert est la
 * campagne tranquille qu'on traverse, c'est-à-dire l'essentiel des 441.
 */
public final class Emballage {
    /**
     * Le plafond du cache, en octets.
     *
     * <p>Trente-deux mébioctets tiennent à peu près une arrivée et demie à distance de vue dix, ce
     * qui est exactement l'ensemble utile : les chunks qu'un second joueur redemandera. Au-delà, on
     * garderait de la campagne que plus personne ne traverse.
     *
     * <p>La division par soixante-quatre est le garde-fou du petit serveur. Un hébergement à un
     * gigaoctet de tas n'a pas les mêmes moyens qu'une machine à quatre : il aura seize mébioctets,
     * et le cache restera à un pour cent et demi de son tas dans les deux cas. Un cache
     * d'optimisation qui provoque un ramasse-miettes a perdu d'avance — et sur un seul cœur, le GC
     * vole le tick.
     */
    private static final long BUDGET = Math.min(32L * 1024L * 1024L, Runtime.getRuntime().maxMemory() / 64L);

    /**
     * Le poids qu'on prête à l'étiquette d'une entité de bloc.
     *
     * <p>C'est une estimation, et elle est dite comme telle : la liste des étiquettes est privée
     * dans le paquet, et la seule façon d'en connaître la taille exacte serait de refaire le NBT
     * qu'on cherche justement à ne pas refaire. Un demi-kilo-octet est large pour un panneau ou une
     * tête, court pour un fourneau plein d'objets à métadonnées.
     *
     * <p>L'estimation est volontairement <b>haute</b> : une borne qu'on sous-estime est une borne
     * qu'on dépasse, et c'est le seul sens dans lequel se tromper est dangereux ici.
     */
    private static final int ETIQUETTE = 512;

    /** L'en-tête d'une entrée : l'objet paquet, ses deux sous-objets, la référence faible, la case. */
    private static final int SOCLE = 256;

    /** Une couche de lumière fait toujours 2 048 octets — un demi-octet par bloc d'une section. */
    private static final int COUCHE = 2048;

    private Emballage() {}

    /**
     * Un colis : le paquet gardé, son jeton d'identité, et ce qu'il pèse.
     *
     * <p>Le jeton est faible et n'est jamais déréférencé pour autre chose qu'une comparaison
     * d'identité — voir l'en-tête sur la mémoire. S'il est vide, c'est que le chunk a été déchargé
     * et ramassé : l'entrée est alors un défaut certain, et le premier passage l'évincera.
     *
     * <p>Un {@code paquet} nul n'est pas un colis mais une <b>réservation</b> : voir
     * {@link Emballage#paquet}. C'est la pièce qui ferme la fenêtre entre la construction d'un
     * paquet et son rangement.
     */
    private record Colis(WeakReference<LevelChunk> chunk, ClientboundLevelChunkWithLightPacket paquet, int octets) {}

    /**
     * Le cache, du moins récemment servi au plus récemment servi.
     *
     * <p>L'ordre d'insertion de fastutil fait l'usure : {@code getAndMoveToLast} sur une lecture
     * réussie remet le colis en queue, {@code removeFirst} évince par la tête. On ne paie donc ni
     * horodatage ni tri.
     */
    private static final Long2ObjectLinkedOpenHashMap<Colis> COLIS = new Long2ObjectLinkedOpenHashMap<>();

    /**
     * Le verrou, et pourquoi il y en a un.
     *
     * <p>{@code markUnsaved} n'est pas garanti sur le fil principal : la lumière auxiliaire de
     * NeoForge s'annonce explicitement « threadsafe » et tient sa table en {@code ConcurrentHashMap}
     * — donc un mod a le droit de l'appeler d'ailleurs, et elle appelle {@code markUnsaved()}.
     * Un cache corrompu par une écriture concurrente servirait n'importe quoi, ce qui est le défaut
     * qu'on s'est promis de ne pas introduire.
     *
     * <p>Sur la machine visée, à <b>un seul cœur</b>, une contention est de toute façon impossible :
     * deux fils ne s'exécutent jamais en même temps. Le coût réel est celui d'un moniteur non
     * contesté, que le compilateur à la volée réduit à quelques nanosecondes.
     */
    private static final Object VERROU = new Object();

    /**
     * Le cache est-il vide ? Lu <b>hors</b> du verrou, et c'est tout l'intérêt.
     *
     * <p>{@link #oublie} est appelé à chaque pose de bloc, à chaque {@code setChanged} d'un
     * entonnoir : c'est le chemin chaud de ce module. Tant que rien n'est en cache — serveur qui
     * démarre, module éteint — il s'arrête sur cette lecture de champ volatile et ne prend jamais
     * le verrou.
     */
    private static volatile boolean vide = true;

    /** Poids courant du cache, en octets. Sous verrou. */
    private static long octets;

    /** Paquets rendus sans les reconstruire. C'est le gain, et c'est ce qu'il faut regarder. */
    private static long reutilises;

    /** Paquets construits puis rangés. */
    private static long emballes;

    /** Colis retirés parce que le chunk a changé. */
    private static long oublies;

    /** Colis retirés parce que le plafond était atteint. */
    private static long evinces;

    /**
     * La clé d'un chunk : sa position, brassée avec l'identité de son monde.
     *
     * <p>Une position seule confondrait l'origine du Nether et celle de l'Overworld. On y mêle donc
     * l'identité de l'objet monde. Ce n'est pas gratuit — la machine virtuelle calcule le hachage
     * d'identité à la première demande et l'installe dans l'en-tête de l'objet — mais un
     * {@code ServerLevel} vit autant que le serveur : la première demande a lieu au premier chunk
     * envoyé, et toutes les suivantes ne sont plus qu'une lecture de cet en-tête.
     *
     * <p>Un mélange reste un hachage, donc les collisions restent possibles ; c'est le jeton
     * d'identité du colis qui les rend inoffensives, pas cette multiplication. Elle ne fait que les
     * rendre rares.
     */
    private static long cle(Level monde, long position) {
        return position * 0x9E3779B97F4A7C15L + System.identityHashCode(monde);
    }

    /**
     * Le paquet d'un chunk : celui qu'on avait, ou un neuf qu'on garde.
     *
     * <p>Appelée à la place du constructeur de vanilla, avec ses arguments exacts. Le contrat est
     * donc : <b>rendre toujours un paquet valide</b>, cache ou pas, module allumé ou éteint.
     *
     * <p>Les deux filtres de lumière ne sont jamais autre chose que {@code null} dans
     * {@code sendChunk} — c'est la forme « lumière complète ». S'ils cessaient de l'être, ou si un
     * mod appelait ce constructeur autrement, on refuserait le cache plutôt que de deviner : un
     * paquet à lumière partielle n'est pas le même objet et ne se réutilise pas.
     *
     * <h2>La réservation, et la fenêtre qu'elle ferme</h2>
     *
     * <p>Construire le paquet prend environ deux millisecondes, et on ne le fait <b>pas</b> sous le
     * verrou : le tenir si longtemps bloquerait le fil de lumière quatre cent quarante fois de
     * suite pendant une arrivée. Mais alors il existe un intervalle entre la lecture du cache et le
     * rangement, et un chunk qui changerait pendant cet intervalle se ferait ranger périmé — le
     * défaut exact qu'on s'interdit.
     *
     * <p>On pose donc une <b>réservation</b> avant de construire : une entrée sans paquet, sous la
     * clé visée. {@link #oublie} la retire comme il retirerait un vrai colis, sans rien savoir
     * d'elle. Au retour, on ne range que si la réservation est <b>toujours la nôtre</b> ; sinon
     * c'est qu'une invalidation est passée, et le paquet part au joueur sans être gardé. La
     * comparaison porte sur l'identité de l'objet réservation, ce qui règle aussi le cas où deux
     * fils emballeraient le même chunk en même temps.
     *
     * <p>Cela couvre même le cas tordu où la construction du paquet invaliderait le chunk qu'elle
     * lit : {@code getUpdateTag} d'une entité de bloc mal élevée peut appeler {@code setChanged}.
     * La réservation disparaît alors sous nos pieds, et c'est la bonne réponse.
     */
    public static ClientboundLevelChunkWithLightPacket paquet(
            LevelChunk chunk, LevelLightEngine lumieres, BitSet filtreCiel, BitSet filtreBlocs) {
        if (!Settings.emballage()) {
            // Éteint en cours de route : on rend la mémoire tout de suite plutôt qu'à la prochaine
            // éviction, qui n'aura jamais lieu puisque plus rien n'entre.
            if (!vide) {
                purge();
            }
            return new ClientboundLevelChunkWithLightPacket(chunk, lumieres, filtreCiel, filtreBlocs);
        }
        if (filtreCiel != null || filtreBlocs != null) {
            return new ClientboundLevelChunkWithLightPacket(chunk, lumieres, filtreCiel, filtreBlocs);
        }

        long cle = cle(chunk.getLevel(), chunk.getPos().pack());
        Colis reservation = new Colis(new WeakReference<>(chunk), null, 0);
        synchronized (VERROU) {
            Colis garde = COLIS.getAndMoveToLast(cle);
            if (garde != null && garde.paquet() != null && garde.chunk().get() == chunk) {
                reutilises++;
                return garde.paquet();
            }
            // Ce qui reste ici est soit un colis d'un autre chunk — chunk rechargé, ou collision
            // de clé entre deux dimensions —, soit une réservation abandonnée. Dans les deux cas la
            // place revient à celui qui la demande.
            if (garde != null) {
                octets -= garde.octets();
            }
            COLIS.put(cle, reservation);
            vide = false;
        }

        ClientboundLevelChunkWithLightPacket neuf =
                new ClientboundLevelChunkWithLightPacket(chunk, lumieres, null, null);
        int poids = poids(chunk, neuf);
        synchronized (VERROU) {
            if (COLIS.get(cle) != reservation) {
                // Invalidé pendant la construction. Le joueur reçoit un paquet juste ; on ne garde
                // rien, et le prochain envoi réessaiera.
                return neuf;
            }
            COLIS.put(cle, new Colis(reservation.chunk(), neuf, poids));
            octets += poids;
            emballes++;
            while (octets > BUDGET && COLIS.size() > 1) {
                Colis parti = COLIS.removeFirst();
                octets -= parti.octets();
                evinces++;
            }
            vide = COLIS.isEmpty();
        }
        return neuf;
    }

    /**
     * Ce chunk a changé : son paquet ne vaut plus rien.
     *
     * <p>C'est le chemin chaud — une pose de bloc, un objet qui passe dans un entonnoir. Tout est
     * fait pour qu'il ne coûte rien quand il n'y a rien à faire : une lecture de champ volatile, et
     * si le cache est vide, on s'arrête là.
     *
     * <p>La comparaison d'identité n'est pas une précaution de plus, elle est nécessaire : sur une
     * collision de clé entre deux dimensions, retirer aveuglément jetterait le colis d'un chunk
     * parfaitement à jour. On ne retire que le sien.
     */
    public static void oublie(LevelChunk chunk) {
        if (vide) {
            return;
        }
        long cle = cle(chunk.getLevel(), chunk.getPos().pack());
        synchronized (VERROU) {
            Colis garde = COLIS.get(cle);
            if (garde == null || garde.chunk().get() != chunk) {
                return;
            }
            COLIS.remove(cle);
            octets -= garde.octets();
            oublies++;
            vide = COLIS.isEmpty();
        }
    }

    /** Tout jeter. Employé quand le module s'éteint, pour rendre la mémoire sans attendre. */
    public static void purge() {
        synchronized (VERROU) {
            COLIS.clear();
            octets = 0L;
            vide = true;
        }
    }

    /**
     * Ce que pèse un colis, en octets.
     *
     * <p>Trois des quatre termes sont exacts, et ce sont les trois gros : le tampon des sections
     * (états et biomes bit-packés) se lit sur le paquet lui-même ; les cartes de hauteur sont des
     * tableaux de {@code long} ; la lumière est un nombre entier de couches de 2 048 octets, que le
     * paquet expose par ses deux listes de mises à jour.
     *
     * <p>Le quatrième — les étiquettes des entités de bloc — est estimé, pour la raison donnée à
     * {@link #ETIQUETTE}. Sur un chunk de campagne il vaut zéro, ce qui est exact ; sur une base il
     * surestime, ce qui est le bon sens de l'erreur.
     */
    private static int poids(LevelChunk chunk, ClientboundLevelChunkWithLightPacket paquet) {
        ClientboundLevelChunkPacketData donnees = paquet.getChunkData();
        int total = SOCLE + donnees.getReadBuffer().readableBytes();
        for (long[] carte : donnees.getHeightmaps().values()) {
            total += carte.length * Long.BYTES;
        }
        ClientboundLightUpdatePacketData lumiere = paquet.getLightData();
        total += (lumiere.getSkyUpdates().size() + lumiere.getBlockUpdates().size()) * COUCHE;
        return total + chunk.getBlockEntities().size() * ETIQUETTE;
    }

    /** Paquets rendus sans reconstruction depuis le démarrage : le gain, en clair. */
    public static long reutilises() {
        return reutilises;
    }

    /** Paquets construits puis rangés depuis le démarrage. */
    public static long emballes() {
        return emballes;
    }

    /** Colis jetés parce que leur chunk a changé. */
    public static long oublies() {
        return oublies;
    }

    /** Colis jetés parce que le plafond de {@link #BUDGET} était atteint. */
    public static long evinces() {
        return evinces;
    }

    /** Ce que le cache pèse en ce moment, en octets. */
    public static long octets() {
        return octets;
    }

    /** Le plafond retenu au démarrage, en octets. Dépend du tas, voir {@link #BUDGET}. */
    public static long budget() {
        return BUDGET;
    }
}
