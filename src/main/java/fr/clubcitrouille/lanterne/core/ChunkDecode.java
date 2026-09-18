package fr.clubcitrouille.lanterne.core;

import java.io.DataInputStream;
import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * La restitution : lire un chunk déjà écrit ne devrait pas coûter un fil entier à lui tout seul.
 *
 * <h2>Ce que le fil unique de vanilla mélange, et ce qui peut s'en défaire</h2>
 *
 * <p>{@code IOWorker} tient un {@code PriorityConsecutiveExecutor} par type de stockage — un pour les
 * chunks, un pour les entités, un pour les points d'intérêt, par dimension. Vérifié dans
 * {@code AbstractConsecutiveExecutor.run()} : chaque tâche est dépilée et exécutée <b>jusqu'à son
 * retour</b> avant que la suivante ne parte, même si le bassin qui l'accueille ({@code Util.ioPool()})
 * compte lui-même plusieurs fils. Quatre cent quarante et un chunks à lire sont donc quatre cent
 * quarante et une tâches strictement l'une après l'autre, quel que soit le nombre de cœurs de la
 * machine.
 *
 * <p>Or cette sérialisation ne protège qu'<b>une seule chose</b> : le cache de fichiers de région de
 * {@code RegionFileStorage} ({@code regionCache}, une simple {@code Long2ObjectLinkedOpenHashMap} sans
 * le moindre verrou — vérifié). C'est ce cache qu'il faut préserver d'un accès concurrent, pas le
 * décodage qui suit. {@code RegionFile.getChunkDataInputStream} (vérifié, ligne 112) est lui-même
 * {@code synchronized} et ne fait qu'une lecture de secteur déjà en mémoire ; la décompression et
 * l'analyse NBT n'ont lieu qu'<b>après</b>, quand {@code NbtIo.read} consomme le flux — et à cet
 * instant, plus rien ne touche au cache. C'est donc uniquement l'ouverture du flux qu'il faut garder
 * sur le fil unique de l'IOWorker ; le décodage peut partir ailleurs.
 *
 * <h2>Le découpage : vite sur le fil unique, lentement à côté</h2>
 *
 * <p>{@link fr.clubcitrouille.lanterne.mixin.RegionFileStorageMixin#lanterne$openChunkStream} isole le
 * geste rapide — un appel système déjà exécuté, un flux prêt à lire. {@code IOWorkerMixin} l'appelle
 * <b>sur le fil de l'IOWorker</b>, exactement comme vanilla le fait aujourd'hui, puis remet ce flux à
 * {@link #decode}, qui tourne sur un bassin séparé. Le fil de l'IOWorker n'attend jamais ce résultat :
 * il complète sa tâche dès que le flux est ouvert et passe à la suivante, ce qui est tout l'intérêt —
 * voir le Javadoc de {@code AbstractConsecutiveExecutor} : une tâche qui revient vite libère la
 * suivante, que le travail qu'elle a délégué soit fini ou non.
 *
 * <h2>Le nombre de fils, et pourquoi il ne mord jamais</h2>
 *
 * <p>Le nombre de cœurs que {@link Quota} promet, moins un, jamais sous un seul — voir sa Javadoc de
 * classe pour la raison de ne plus se fier directement à {@code Util.maxAllowedExecutorThreads()} ou à
 * {@code Runtime.getRuntime().availableProcessors()} : sur un hébergement mutualisé qui ne confine pas
 * correctement le conteneur, ce nombre peut afficher le matériel entier plutôt que le quota réellement
 * promis. Sur la machine de développement — plusieurs cœurs — cela ouvre un vrai recouvrement. Sur la
 * cible annoncée pour le déploiement dédié — un seul cœur logique, confirmé par {@link Quota} plutôt
 * que supposé — la formule rend exactement <b>un</b>, et le comportement redevient rigoureusement
 * celui d'aujourd'hui : une tâche à la fois, sans fil supplémentaire à faire vivre, sans risque de
 * régression sur la machine qui en a le moins les moyens.
 *
 * <h2>Ce que ce module ne change pas</h2>
 *
 * <p>Ni le format sur disque, ni le codec de compression choisi par chunk ({@link Attic} s'en occupe
 * déjà, et seulement pour l'écriture), ni l'ordre dans lequel les chunks sont demandés. Un chunk lu en
 * avance d'un autre reste possible comme chez vanilla — rien ici n'imposait déjà d'ordre entre deux
 * lectures distinctes, seulement entre une lecture et l'accès au cache de fichiers qui la précède.
 */
public final class ChunkDecode {
    private static final AtomicInteger THREAD_INDEX = new AtomicInteger();

    /**
     * Le bassin de décodage. Fils démons, nommés pour se reconnaître dans un profileur ou un
     * {@code jstack} — voir {@code Util.makeIoExecutor} pour le même geste côté vanilla.
     */
    private static final ExecutorService POOL = Executors.newFixedThreadPool(threads(), runnable -> {
        Thread thread = new Thread(runnable, "Lanterne-ChunkDecode-" + THREAD_INDEX.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    });

    /** Décodages réussis depuis le démarrage — le seul chiffre qui dit si ce module sert. */
    private static final AtomicLong decoded = new AtomicLong();
    /** Temps cumulé passé à décoder, en nanosecondes — pour rapporter une moyenne, pas juste un compte. */
    private static final AtomicLong decodeNanos = new AtomicLong();
    /** Diagnostic temporaire : entrées dans {@code lanterne$parallelDecode}, module actif ou non. */
    private static final AtomicLong entered = new AtomicLong();
    /** Diagnostic temporaire : entrées où {@code Settings.decode()} était vrai. */
    private static final AtomicLong engaged = new AtomicLong();

    private ChunkDecode() {}

    /**
     * Les cœurs promis par {@link Quota}, moins un — jamais sous un seul.
     *
     * <p>Ce n'était, jusqu'ici, que {@code Runtime.getRuntime().availableProcessors() - 1}, le même
     * calcul que {@code Util.maxAllowedExecutorThreads()} de vanilla. Sur du matériel dédié cela
     * suffit ; sur un conteneur facturé pour un seul cœur mais posé sur une machine qui en affiche
     * vingt-six sans confinement cgroup correct — le piège précisément nommé pour ce déploiement —
     * ce calcul aurait ouvert vingt-cinq fils pour un seul cœur de temps CPU réel. {@link Quota} lit
     * d'abord le quota du conteneur avant de se rabattre sur ce nombre, et le dit au journal quand il
     * doit s'y rabattre. Sur une machine à un seul cœur logique confirmé, ceci rend toujours {@code 1}
     * — le bassin existe mais ne peut jamais faire mieux qu'exécuter les décodages l'un après
     * l'autre, exactement comme le ferait le fil unique de vanilla.
     *
     * <h2>Machine a le dernier mot, si {@link Config#ETALON_AUTO} le permet</h2>
     *
     * <p>Dimensionner sur le nombre de cœurs suppose que paralléliser aide <em>ici</em> —
     * {@link Machine} le vérifie par une mesure chronométrée plutôt que de le supposer ; voir sa
     * Javadoc de classe pour l'incident de production qui a rendu cette mesure fiable (un facteur
     * douze entre deux démarrages identiques, avant correctif). Si son verdict dit que ça ne vaut
     * pas le coup sur cette machine, le bassin est ramené à un seul fil quel que soit ce que
     * {@link Quota} promet : plusieurs fils qui se disputent un gain absent n'ajoutent que de la
     * contention à un décodage déjà correct en série. {@link Config#ETALON_AUTO} à {@code false}
     * rend la main entière au calcul ci-dessus, sans correction — voir sa Javadoc pour pourquoi
     * aucun autre bassin de ce dépôt n'est concerné par ce réglage.
     */
    private static int threads() {
        int quotaBased = Mth.clamp(Quota.cores() - 1, 1, 16);
        if (!Config.ETALON_AUTO.get()) {
            return quotaBased;
        }
        // Idempotent — voir la Javadoc de Machine.appraise(). Cet appel ne fait que garantir un
        // verdict déjà mesuré ; en pratique le constructeur du mod l'a toujours appelé avant que ce
        // bassin ne se charge, puisque ChunkDecode n'est touché qu'à la première lecture d'un chunk.
        Machine.appraise();
        if (Machine.worthParallelising()) {
            return quotaBased;
        }
        Lanterne.LOG.info("[CHUNKDECODE] Machine juge la parallélisation contre-productive sur "
                + "cette machine (gain réel ×{}) : bassin ramené à 1 fil — Quota en promettait {}. "
                + "ETALON_AUTO=false pour l'imposer quand même.",
                String.format(java.util.Locale.ROOT, "%.2f", Machine.speedup()), quotaBased);
        return 1;
    }

    /**
     * Décode un flux déjà ouvert, sur le bassin séparé.
     *
     * <p>Le flux a été obtenu sur le fil de l'IOWorker — voir le Javadoc de classe — et n'est plus
     * touché par rien d'autre à partir d'ici : le décoder ailleurs ne risque aucune course.
     */
    public static CompletableFuture<Optional<CompoundTag>> decode(DataInputStream stream) {
        return CompletableFuture.supplyAsync(() -> {
            long start = System.nanoTime();
            try (stream) {
                CompoundTag tag = NbtIo.read(stream);
                decoded.incrementAndGet();
                decodeNanos.addAndGet(System.nanoTime() - start);
                return Optional.ofNullable(tag);
            } catch (IOException e) {
                throw new CompletionException(e);
            }
        }, POOL);
    }

    public static long decoded() {
        return decoded.get();
    }

    public static long noteEntered() {
        return entered.incrementAndGet();
    }

    public static void noteEngaged() {
        engaged.incrementAndGet();
    }

    public static long entered() {
        return entered.get();
    }

    public static long engaged() {
        return engaged.get();
    }

    /** Temps moyen d'un décodage, en millisecondes — {@code 0} tant que rien n'a été décodé. */
    public static double averageMillis() {
        long count = decoded.get();
        return count == 0L ? 0d : decodeNanos.get() / 1.0E6d / count;
    }

    public static void resetStats() {
        decoded.set(0L);
        decodeNanos.set(0L);
        entered.set(0L);
        engaged.set(0L);
    }
}
