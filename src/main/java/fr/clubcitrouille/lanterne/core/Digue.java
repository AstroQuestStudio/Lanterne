package fr.clubcitrouille.lanterne.core;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * La digue : le tick du serveur ne descend jamais au disque.
 *
 * <h2>Ce qu'est un chargement synchrone, et pourquoi il coûte si cher ici</h2>
 *
 * <p>Une poignée de chemins du jeu demandent un chunk <em>tout de suite</em>, au milieu du tick.
 * {@code ServerChunkCache.getChunk(x, z, FULL, true)} pose alors un ticket, puis appelle
 * {@code mainThreadProcessor.managedBlock(future::isDone)} : le fil du serveur cesse de ticker et se
 * met à faire tourner la file de génération jusqu'à ce que le chunk existe. Lire une région sur
 * disque, la décompresser, ou pire la <b>générer</b>, prend de l'ordre de la centaine de
 * millisecondes — deux à quatre ticks perdus d'un coup, et le joueur le voit.
 *
 * <p>Sur la cible de ce projet — un VPS à <b>un seul cœur</b> — il n'y a aucun autre cœur pour
 * absorber ce travail. Le fil de génération, le ramasse-miettes et le fil du serveur se partagent le
 * même processeur : le « chargement asynchrone » du jeu n'est asynchrone que sur une machine qui a de
 * quoi l'être.
 *
 * <p>La digue ne rend donc rien plus rapide. Elle <b>refuse</b> : elle répond « ce chunk n'est pas
 * là » au lieu d'aller le chercher, et le jeu suit sa branche « pas là », qui existe déjà partout où
 * elle est posée.
 *
 * <h2>Refuser change un comportement, et il faut le dire à chaque fois</h2>
 *
 * <p>Ce n'est pas une optimisation neutre, et c'est le seul module de ce mod dont chaque point
 * d'accroche doit s'accompagner d'une phrase disant <em>ce qu'on perd</em>. Une carte qui ne se
 * dessine pas au-delà du chargé, une abeille qui ne trouve pas sa ruche, un villageois qui ne se
 * couche pas : trois pertes réelles, de trois gravités différentes. Le détail est dans chaque mixin.
 *
 * <p>Deux points d'accroche de {@code ServerCore} ont d'ailleurs été <b>écartés</b> après lecture des
 * sources 26.2 — voir {@link #REJECTED_UPDATE_NEIGHBOUR} et {@link #REJECTED_STRUCTURE_BIOME}.
 *
 * <h2>Le test de présence, et pourquoi il ne peut pas être {@code hasChunkAt}</h2>
 *
 * <p>{@code ServerChunkCache.hasChunk(x, z)} ne bloque plus en 26.2 — il ne regarde qu'un niveau de
 * ticket. Mais il répond <b>vrai</b> pour un chunk dont le ticket est posé et dont la génération
 * n'est pas finie : le {@code getBlockState} qui suit bloque alors quand même. Le seul test qui
 * garantit l'absence d'attente est {@code getChunkNow}, que {@code ServerChunkCache} redéfinit
 * (ligne 184 de {@code ServerChunkCache.java}) pour ne lire que le porteur déjà complet.
 *
 * <p>Attention : ce même {@code getChunkNow} rend {@code null} <b>hors du fil du serveur</b>, sans
 * rapport avec le chargement. On vérifie donc le fil avant de conclure, faute de quoi la digue
 * refuserait tout ce qui passe par un fil de travail — et l'on aurait remplacé un gel par un monde
 * qui se croit vide.
 */
public final class Digue {
    /**
     * {@code Level.updateNeighbourForOutputSignal}, écarté après lecture de la 26.2.
     *
     * <p>{@code ServerCore} y redirige {@code hasChunkAt} vers un test strict. Le raisonnement valait
     * quand {@code ChunkSource.hasChunk} appelait {@code getChunk(x, z, FULL, false)} — c'est encore
     * le corps de la classe de base ({@code ChunkSource.java} ligne 25). Mais
     * {@code ServerChunkCache} le <b>redéfinit</b> en 26.2 (ligne 266) :
     *
     * <pre>
     * ChunkHolder holder = this.getVisibleChunkIfPresent(new ChunkPos(x, z).pack());
     * return !this.chunkAbsent(holder, ChunkLevel.byStatus(ChunkStatus.FULL));
     * </pre>
     *
     * <p>Aucune attente. Il reste la fenêtre étroite « ticket posé, génération en cours », où le
     * {@code getBlockState} suivant bloque — mais le prix à payer serait un point d'accroche sur un
     * chemin parcouru à <b>chaque changement de bloc voisin d'un comparateur</b>, et la perte, elle,
     * serait visible : un comparateur qui ne se met pas à jour est une ferme qui s'arrête.
     *
     * <p>Ce dépôt a déjà payé trois fois pour la même leçon — la bordure du monde, le repos posé, le
     * brassage des positions : <b>le coût d'un mixin sur un chemin chaud dépasse celui du travail
     * qu'on y supprime</b> dès que le taux de déclenchement est faible. Ici il est presque nul.
     */
    public static final boolean REJECTED_UPDATE_NEIGHBOUR = true;

    /**
     * Le pré-test de biome de {@code StructureCheck.checkStart}, écarté — et ce n'était pas un
     * chargement synchrone.
     *
     * <p>{@code ServerCore} range ce mixin avec les autres, mais il fait autre chose : il devine le
     * biome d'un trésor enfoui à hauteur de la mer pour éviter l'échantillonnage de bruit de
     * {@code getFirstOccupiedHeight}. Son propre commentaire admet <em>« an extremely small chance
     * that it will return the wrong biome »</em> — c'est-à-dire une carte au trésor qui ne mène nulle
     * part.
     *
     * <p>Et le gain n'est pas celui qu'on croit en 26.2. {@code checkStart} filtre <b>avant</b> :
     *
     * <pre>
     * if (!placement.applyAdditionalChunkRestrictions(pos.x(), pos.z(), this.seed)) {
     *     return StructureCheckResult.START_NOT_PRESENT;   // StructureCheck.java ligne 92
     * }
     * </pre>
     *
     * <p>Le trésor enfoui a une fréquence de 1 % : quatre-vingt-dix-neuf chunks sur cent n'atteignent
     * jamais le calcul coûteux, et le centième est mémorisé dans {@code featureChecks}. Il reste le
     * cas de {@code /locate}, qui balaie des milliers de chunks — un gain réel, sur une commande, au
     * prix d'une divergence de génération. On n'échange pas ça.
     */
    public static final boolean REJECTED_STRUCTURE_BIOME = true;

    private static long bee;
    private static long map;
    private static long event;
    private static long bed;
    private static long block;

    /**
     * Retire la protection de mémoire de la ruche, et fait donc échouer l'épreuve.
     *
     * <p>Voir {@code BeeHiveMixin} : refuser le chargement suffit à faire oublier sa ruche à
     * l'abeille, parce que {@code Bee.aiStep} efface {@code hivePos} dès que {@code isHiveValid()}
     * rend faux. C'est le comportement de {@code ServerCore}, et c'est un défaut.
     *
     * <p>{@code LANTERNE_BREAK_DIGUE=1} le rétablit. L'épreuve {@code lab.Levee} <b>doit</b> alors
     * annoncer des abeilles sans ruche — une épreuve qui ne peut pas échouer ne prouve rien.
     */
    private static final boolean BROKEN = "1".equals(System.getenv("LANTERNE_BREAK_DIGUE"));

    private Digue() {}

    public static boolean broken() {
        return BROKEN;
    }

    /**
     * Le chunk, s'il est déjà là — et {@code null} si l'obtenir demanderait d'attendre.
     *
     * <p>Rend {@code null} aussi bien pour « absent » que pour « hors du fil du serveur ». Les
     * appelants ne doivent donc refuser qu'après {@link #guards}.
     */
    public static @Nullable LevelChunk now(LevelReader reader, int chunkX, int chunkZ) {
        return reader instanceof ServerLevel level && level.getServer().isSameThread()
                ? level.getChunkSource().getChunkNow(chunkX, chunkZ)
                : null;
    }

    /**
     * La digue garde-t-elle ce monde à cet instant ?
     *
     * <p>Trois conditions, et les trois comptent. Le module doit être allumé ; le monde doit être un
     * monde de serveur — sur le client, aucun de ces chemins ne charge quoi que ce soit de manière
     * bloquante, et refuser y serait une régression pure ; et l'on doit se trouver sur le fil du
     * serveur, sans quoi {@code getChunkNow} rendrait {@code null} pour une raison qui n'a rien à
     * voir avec le chargement.
     */
    public static boolean guards(LevelReader reader) {
        return Settings.digue()
                && reader instanceof ServerLevel level
                && level.getServer().isSameThread();
    }

    /** Le chunk de cette position est-il déjà en mémoire, prêt, sans attendre ? */
    public static boolean present(LevelReader reader, BlockPos pos) {
        return now(reader, SectionPos.blockToSectionCoord(pos.getX()),
                SectionPos.blockToSectionCoord(pos.getZ())) != null;
    }

    public static void noteBee() {
        bee++;
    }

    public static void noteMap() {
        map++;
    }

    public static void noteEvent() {
        event++;
    }

    public static void noteBed() {
        bed++;
    }

    public static void noteBlock() {
        block++;
    }

    /** Ruches non cherchées dans un chunk absent. */
    public static long bees() {
        return bee;
    }

    /** Cases de carte non peintes faute de chunk. */
    public static long maps() {
        return map;
    }

    /** Déplacements d'écouteur d'événement non répercutés. */
    public static long events() {
        return event;
    }

    /** Lits non consultés. */
    public static long beds() {
        return bed;
    }

    /** Blocs à casser non validés. */
    public static long blocks() {
        return block;
    }

    /**
     * Le total, qui est le seul chiffre dont un banc a le droit de se servir pour conclure.
     *
     * <p>Une épreuve qui annonce un gain alors que ce compteur vaut zéro n'a rien mesuré : elle a
     * comparé deux fois le même code. Quatre épreuves de ce dépôt l'ont fait.
     */
    public static long refusals() {
        return bee + map + event + bed + block;
    }

    public static void reset() {
        bee = 0L;
        map = 0L;
        event = 0L;
        bed = 0L;
        block = 0L;
    }

    /** Le détail, pour le rapport. */
    public static String describe() {
        return String.format(java.util.Locale.ROOT,
                "ruches %d · cartes %d · événements %d · lits %d · blocs %d",
                bee, map, event, bed, block);
    }
}
