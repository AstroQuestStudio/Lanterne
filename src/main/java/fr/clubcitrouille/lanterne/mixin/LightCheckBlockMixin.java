package fr.clubcitrouille.lanterne.mixin;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.lighting.LevelLightEngine;

/**
 * Une des deux fermetures par appel de {@code checkBlock} — celle qui ne sert jamais en
 * production — supprimée.
 *
 * <h2>Ce qui a déjà été trouvé et vérifié avant ce fichier (voir {@code notes/recherche-chunks.md}
 * §7.2, et le javap refait sur le vrai jar client 26.3)</h2>
 *
 * <p>{@code ThreadedLevelLightEngine.checkBlock(BlockPos)} construit, à CHAQUE appel — donc à peu
 * près à chaque pose ou casse de bloc dont les propriétés de lumière diffèrent, en jeu normal, pas
 * seulement à la génération de chunk — deux fermetures :
 *
 * <pre>
 * this.addTask(sectionX, sectionZ, TaskType.PRE_UPDATE,
 *         Util.name(() -&gt; super.checkBlock(immutable), () -&gt; "checking " + immutable));
 * </pre>
 *
 * <p>La première (le {@code Runnable} de travail) est nécessaire : c'est elle qui reporte le
 * calcul réel sur le thread de la file de lumière. La seconde (le {@code Supplier<String>} passé à
 * {@code Util.name}) ne sert JAMAIS en production — {@code SharedConstants
 * .DEBUG_NAMED_RUNNABLES} vaut {@code false}, et {@code Util.name} rend alors son premier argument
 * tel quel sans même regarder le second — et pourtant elle est allouée, capture {@code immutable},
 * et part directement au ramasse-miettes, à chaque appel.
 *
 * <h2>La prémisse de ce chantier était fausse, et je ne l'ai pas supposé — je l'ai fait échouer au
 * compilateur</h2>
 *
 * <p>Le plan de départ tenait sur une hypothèse : {@code ThreadedLevelLightEngine$TaskType} serait
 * <b>package-private</b> — visible depuis n'importe quel fichier du paquet
 * {@code net.minecraft.server.level}, à condition d'y placer réellement un second mixin (patron
 * déjà en place dans ce dépôt pour une classe imbriquée inaccessible — {@code targets = "...
 * IOWorker$PendingStore"}, voir {@link fr.clubcitrouille.lanterne.mixin.IOWorkerPendingStoreMixin}
 * — mais cette fois en écrivant le fichier RÉELLEMENT dans le paquet visé, pour pouvoir nommer le
 * type directement).
 *
 * <p>Une première version de ce fichier faisait exactement ça — {@code package
 * net.minecraft.server.level;}, {@code @Mixin(ThreadedLevelLightEngine.class)}, un
 * {@code @Shadow} sur {@code addTask} déclarant son troisième paramètre
 * {@code ThreadedLevelLightEngine.TaskType} en toutes lettres. Elle a refusé de compiler :
 *
 * <pre>
 * error: TaskType has private access in ThreadedLevelLightEngine
 * </pre>
 *
 * <p><b>{@code private}, pas {@code package-private}.</b> Confirmé une seconde fois, indépendamment
 * du message du compilateur : {@code javap -v} sur {@code ThreadedLevelLightEngine.class} liste
 * {@code NestMembers: net/minecraft/server/level/ThreadedLevelLightEngine$TaskType} — l'encodage
 * moderne (JEP 181) d'une classe imbriquée réellement {@code private} au sens du code source de
 * Mojang, où l'accès est réservé au NID de {@code ThreadedLevelLightEngine} lui-même, pas à son
 * paquet. Vivre dans le même paquet ne donne donc RIEN ici — contrairement à
 * {@code IOWorker$PendingStore}, dont le champ ombré par {@code IOWorkerPendingStoreMixin} est,
 * lui, réellement accessible une fois qu'on cible la classe imbriquée par chaîne. La différence
 * entre les deux cas n'apparaît qu'à l'essai, pas à la lecture du nom du modificateur qu'on croit
 * connaître — d'où ce fichier, corrigé après un premier échec plutôt que réécrit sur une
 * hypothèse invérifiée.
 *
 * <h2>Ce que cet échec ferme, méthodiquement</h2>
 *
 * <ul>
 *   <li><b>Le second mixin « même paquet »</b> (option envisagée au départ) : inutile contre un
 *       type réellement {@code private} — voir ci-dessus. Aucun paquet, aucune classe extérieure au
 *       NID de {@code ThreadedLevelLightEngine} ne peut nommer {@code TaskType} en Java source.</li>
 *   <li><b>{@code @Invoker}/{@code @Shadow} avec un type élargi</b> ({@code Object},
 *       {@code Enum<?>}) à la place de {@code TaskType} pour ombrer {@code addTask} : lu dans le
 *       vrai bytecode de Mixin chargé par ce dépôt ({@code net.fabricmc:sponge-mixin
 *       :0.17.3+mixin.0.8.7} — voir {@code build.gradle}), {@code InvokerInfo} localise la méthode
 *       visée en construisant un sélecteur AVEC LE DESCRIPTEUR EXACT de la méthode déclarée dans
 *       l'accesseur ({@code new MemberInfo(name, null, this.method.desc)}) : un {@code Object} à la
 *       place de {@code TaskType} change ce descripteur, et {@code addTask} n'est simplement plus
 *       trouvée. Le seul endroit où un type élargi fonctionne réellement dans ce dépôt —
 *       {@code Optional<?>} dans {@link fr.clubcitrouille.lanterne.mixin.SectionStorageAccessor} —
 *       n'est pas une substitution que Mixin effectue : c'est l'EFFACEMENT DE GÉNÉRICITÉ de Java
 *       qui rend {@code Optional<?>} et {@code Optional<PoiSection>} bit-à-bit identiques une fois
 *       compilés. {@code TaskType} n'est pas un paramètre de type générique : rien de comparable ne
 *       s'applique à lui.</li>
 *   <li><b>{@code @Coerce}</b> (MixinExtras et Mixin lui-même) : documenté pour élargir un type
 *       inaccessible sur les PARAMÈTRES d'un gestionnaire {@code @Redirect} (qui REÇOIT une valeur
 *       déjà calculée par l'appelant — élargissement, toujours légal en bytecode) et sur les
 *       paramètres d'un {@code @Inject}. Explicitement absent de la javadoc de {@code Coerce} pour
 *       {@code @Shadow}, {@code @Invoker} ou {@code @Accessor} — exactement ce qu'il aurait fallu
 *       pour APPELER {@code addTask} nous-mêmes avec une valeur construite ici, le sens inverse
 *       (rétrécissement vers le type réel), que Mixin ne couvre pas pour ces annotations.</li>
 *   <li><b>Intercepter la construction de la seconde fermeture elle-même</b> — l'empêcher d'exister
 *       plutôt que de la neutraliser après coup : aucun point d'injection de Mixin ni de MixinExtras
 *       ne cible une {@code invokedynamic} brute. Les sélecteurs documentés pour
 *       {@code @WrapOperation} sont {@code INVOKE}, {@code FIELD}, {@code @Constant}, {@code NEW},
 *       {@code MIXINEXTRAS:EXPRESSION} (vérifié sur le wiki de MixinExtras) — aucun ne correspond à
 *       une construction de lambda. Et de toute façon, un {@code @Redirect} posé sur l'appel à
 *       {@code Util.name} arrive trop tard : au moment où il s'exécute, les DEUX fermetures ont déjà
 *       été construites — l'ordre d'évaluation de la JVM calcule les arguments avant l'appel, et
 *       aucune injection ancrée SUR ou APRÈS {@code invokestatic Util.name} ne peut remonter le
 *       temps jusqu'à l'{@code invokedynamic} qui précède. La seule façon d'empêcher cette
 *       construction est donc d'empêcher l'INSTRUCTION ENTIÈRE DE S'EXÉCUTER — remplacer la méthode
 *       depuis son {@code HEAD}, ce que ce fichier fait, ce qui ramène exactement au problème
 *       précédent : il faut alors appeler {@code addTask} soi-même.</li>
 * </ul>
 *
 * <h2>La solution retenue : lier {@code addTask} une seule fois, par réflexion, puis l'appeler par
 * {@code MethodHandle} — jamais par {@code Method.invoke}</h2>
 *
 * <p>Ce dépôt se méfie de la réflexion par principe — voir la javadoc de
 * {@link fr.clubcitrouille.lanterne.mixin.MouseTweaksMixin} (« Appel direct, jamais de
 * réflexion ») et celle de {@link fr.clubcitrouille.lanterne.mixin.StateCacheDedupMixin}, qui cite
 * le même détour chez un autre mod et s'en passe en se plaçant ailleurs. Dans LES DEUX cas, un vrai
 * mixin suffisait — un membre protégé accessible après fusion, un point d'injection où le type privé
 * est déjà en portée sans qu'on ait à le nommer. Ici, la liste ci-dessus n'est pas une supposition :
 * chaque piste a été essayée ou vérifiée dans la source réelle de Mixin, et aucune ne tient. La
 * réflexion n'est pas prise par confort — elle est ce qui reste après que le reste a échoué à
 * l'essai.
 *
 * <p>Le choix qui rend cette réflexion acceptable : elle ne s'exécute QU'UNE FOIS, au chargement de
 * la classe (initialiseurs statiques ci-dessous), jamais par appel de {@code checkBlock}. Le résultat
 * — un {@link MethodHandle} lié et adapté — est ensuite invoqué par {@code invokeExact}, pas par
 * {@code Method.invoke} : pas de tableau {@code Object[]} de boîtage construit à chaque appel, pas de
 * vérification d'accès répétée. Une fois la JVM échauffée, {@code invokeExact} sur un
 * {@code MethodHandle} constant s'aligne sur un appel direct — c'est précisément la garantie que
 * {@code java.lang.invoke} a été conçu pour offrir, et la raison pour laquelle ce détour n'est PAS
 * un déplacement du coût qu'on prétend avoir supprimé.
 *
 * <p><b>Échec bruyant, jamais silencieux</b> — la même exigence que
 * {@code MouseTweaksMixin} pose contre l'ancienne version qu'il a remplacée. Les deux méthodes de
 * résolution ci-dessous lèvent {@link ExceptionInInitializerError} si quoi que ce soit ne correspond
 * plus (méthode renommée, énumération restructurée) : la classe fusionnée refuse alors de se charger,
 * un écran d'erreur au démarrage — jamais un {@code catch} muet qui laisserait la fonctionnalité
 * inerte sans le dire. C'est la même philosophie que {@code defaultRequire: 1} sur le reste de ce
 * dépôt, appliquée là où {@code verifieMixins.py} ne peut PAS aider : ce contrôleur ne lit que des
 * cibles Mixin déclarées en toutes lettres ({@code @Mixin}, {@code method =}, {@code target =}) ; une
 * résolution par {@code Class.forName}/{@code getDeclaredMethod} est invisible pour lui. Seul un
 * vrai démarrage du jeu prouverait que la liaison réussit encore — non fait ici, et il ne faut pas
 * prétendre le contraire.
 *
 * <h2>Pourquoi {@code extends LevelLightEngine}, et pourquoi CE super-appel-là ne pose aucun des
 * problèmes ci-dessus</h2>
 *
 * <p>{@code LevelLightEngine} — contrairement à {@code TaskType} — est {@code public}, et son
 * {@code checkBlock(BlockPos)} aussi (vérifié au javap). Rien n'empêche de la nommer depuis
 * {@code fr.clubcitrouille.lanterne.mixin}. Étendre cette classe sert uniquement à ce que
 * {@code super.checkBlock(immutable)} désigne, dans la lambda ci-dessous, l'implémentation héritée
 * plutôt que de rappeler {@code ThreadedLevelLightEngine.checkBlock} elle-même (boucle infinie) :
 * exactement le patron déjà utilisé par {@link fr.clubcitrouille.lanterne.mixin.BeeHiveMixin} —
 * même constructeur jamais appelé (Mixin jette les constructeurs du mixin à la fusion), même
 * raison.
 *
 * <p>Que la réécriture d'un appel {@code super.xxx()} de mixin s'applique aussi à l'intérieur d'une
 * méthode synthétique de lambda (et pas seulement à la méthode annotée elle-même) est vérifié dans
 * le transformateur Mixin lui-même : {@code MixinTargetContext.updateStaticBinding} parcourt les
 * instructions de CHAQUE méthode copiée depuis la classe du mixin, sans distinction entre une
 * méthode annotée et une méthode {@code lambda$...} générée par {@code javac}.
 *
 * <h2>Verdict honnête</h2>
 *
 * <p><b>Une seule des deux fermetures disparaît</b>, pas les deux. Le {@code Runnable} de travail
 * reste alloué à chaque appel — c'est lui qui porte le report du calcul sur le thread de lumière,
 * et rien ici ne change cette conception ; vanilla en aurait besoin de toute façon. Ce qui disparaît
 * : le {@code Supplier<String>} de {@code Util.name} et l'appel à {@code Util.name} lui-même — une
 * fermeture en moins par appel de {@code checkBlock}, une méthode invoquée à peu près à chaque pose
 * ou casse de bloc affectant la lumière. Pas de chiffre : aucune mesure en jeu n'a été faite ici, et
 * il ne faut pas en inventer une.
 *
 * <p><b>Ce qui n'a pas été vérifié</b> : {@code compileJava} et {@code verifieMixins} confirment que
 * ce fichier compile et que sa cible {@code @Inject} existe dans le jar vanilla — ni l'un ni l'autre
 * ne peut confirmer que la liaison réflexive vers {@code addTask}/{@code TaskType.PRE_UPDATE}
 * réussit au chargement réel du jeu. Documenté plutôt que supposé, comme le reste de ce fichier.
 *
 * <h2>Hors de portée, volontairement</h2>
 *
 * <p>{@code queueSectionData}, {@code setLightEnabled}, {@code retainData},
 * {@code updateSectionStatus} et {@code propagateLightSources} enveloppent leur {@code Runnable}
 * exactement de la même façon, mais sont appelées bien moins souvent que {@code checkBlock}
 * (chargement/déchargement de section, pas chaque bloc posé). Cette branche s'appelle
 * {@code feature/light-checkblock-lambda} : elle ne touche que {@code checkBlock}. Le même patron —
 * et les mêmes impasses — s'appliqueraient aux autres méthodes ; laissé pour une passe séparée.
 */
@Mixin(ThreadedLevelLightEngine.class)
public abstract class LightCheckBlockMixin extends LevelLightEngine {
    /** Jamais appelé — voir la javadoc de classe, « extends LevelLightEngine ». */
    private LightCheckBlockMixin(LightChunkGetter chunkSource, boolean hasBlockLight,
            boolean hasSkyLight) {
        super(chunkSource, hasBlockLight, hasSkyLight);
    }

    /**
     * {@code TaskType.PRE_UPDATE}, résolue une seule fois par introspection d'énumération —
     * {@code Class.getEnumConstants()} contourne lui-même la visibilité de la classe (c'est un
     * service que le JDK rend volontairement pour toute énumération, publique ou non), donc aucun
     * {@code setAccessible} n'est nécessaire ici, contrairement à {@link #LANTERNE$ADD_TASK}.
     */
    @Unique
    private static final Object LANTERNE$PRE_UPDATE = lanterne$resolvePreUpdate();

    /**
     * {@code addTask(int, int, TaskType, Runnable)}, lié une seule fois et adapté pour accepter un
     * troisième argument {@code Object} — voir la javadoc de classe pour pourquoi cette conversion
     * est sûre et gratuite (rétrécissement vers {@code TaskType} inséré par {@code asType}, toujours
     * vérifié puisque seul {@link #LANTERNE$PRE_UPDATE} lui est jamais passé).
     */
    @Unique
    private static final MethodHandle LANTERNE$ADD_TASK = lanterne$resolveAddTask();

    @Unique
    private static Object lanterne$resolvePreUpdate() {
        try {
            Class<?> taskType = Class.forName(
                    "net.minecraft.server.level.ThreadedLevelLightEngine$TaskType");
            for (Object constante : taskType.getEnumConstants()) {
                if (((Enum<?>) constante).name().equals("PRE_UPDATE")) {
                    return constante;
                }
            }
            throw new NoSuchFieldException(
                    "net.minecraft.server.level.ThreadedLevelLightEngine$TaskType.PRE_UPDATE");
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @Unique
    private static MethodHandle lanterne$resolveAddTask() {
        try {
            Class<?> taskType = Class.forName(
                    "net.minecraft.server.level.ThreadedLevelLightEngine$TaskType");
            Method addTask = ThreadedLevelLightEngine.class.getDeclaredMethod(
                    "addTask", int.class, int.class, taskType, Runnable.class);
            addTask.setAccessible(true);
            MethodHandle brut = MethodHandles.lookup().unreflect(addTask);
            return brut.asType(MethodType.methodType(void.class,
                    ThreadedLevelLightEngine.class, int.class, int.class, Object.class,
                    Runnable.class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /**
     * Remplace intégralement le corps vanilla : mêmes calculs, même appel à {@code addTask}, une
     * seule fermeture au lieu de deux. Voir la javadoc de classe pour le détail vérifié.
     */
    @Inject(method = "checkBlock(Lnet/minecraft/core/BlockPos;)V", at = @At("HEAD"), cancellable = true)
    private void lanterne$checkBlock(BlockPos pos, CallbackInfo ci) {
        BlockPos immutable = pos.immutable();
        int sectionX = SectionPos.blockToSectionCoord(immutable.getX());
        int sectionZ = SectionPos.blockToSectionCoord(immutable.getZ());
        ThreadedLevelLightEngine self = (ThreadedLevelLightEngine) (Object) this;
        Runnable travail = () -> super.checkBlock(immutable);
        try {
            LANTERNE$ADD_TASK.invokeExact(self, sectionX, sectionZ, LANTERNE$PRE_UPDATE, travail);
        } catch (Throwable t) {
            // N'arrive qu'en cas d'incompatibilité binaire deja signalee bruyamment par
            // ExceptionInInitializerError au chargement — voir « Echec bruyant » ci-dessus. Ne
            // jamais laisser une file de lumiere silencieusement non peuplee.
            throw new RuntimeException("lanterne$checkBlock: échec de l'appel réfléchi à addTask", t);
        }
        ci.cancel();
    }
}
