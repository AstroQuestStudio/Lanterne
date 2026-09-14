package fr.clubcitrouille.lanterne.core;

import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

/**
 * Le voile : ce qu'un mur cache n'est pas dessiné.
 *
 * <h2>Ce que le jeu coupe déjà, et ce qu'il ne coupe pas</h2>
 *
 * <p>Vanilla écarte les entités hors du champ de vision — c'est le {@code Frustum} passé à
 * {@code EntityRenderDispatcher.shouldRender}. Il n'écarte <b>pas</b> celles qui sont dans le champ
 * mais derrière de la pierre. Une ferme à mobs sous une colline, un enclos vu depuis l'autre côté de
 * sa grange, un donjon : toutes ces créatures traversent le test, et chacune paie ensuite le prix
 * fort.
 *
 * <h2>Où le prix se paie</h2>
 *
 * <p>Dans {@code LevelRenderer.extractVisibleEntities} ({@code .mcsrc}, ligne 872), le test est suivi
 * immédiatement de {@code this.extractEntity(entity, partialEntity)}. C'est là qu'est le coût :
 * construire l'état de rendu complet d'une créature — pose, animations, calques d'équipement,
 * éclairage, texture. Refuser <em>avant</em> ce point, c'est économiser tout cela ; refuser après
 * n'économiserait que le dessin.
 *
 * <p>C'est pourquoi ce module se branche sur {@code shouldRender} et nulle part ailleurs.
 *
 * <h2>Pourquoi le lancer de rayon est synchrone, contrairement à l'usage</h2>
 *
 * <p>Le mod de référence sur ce mécanisme fait ses lancers sur un fil séparé. C'est séduisant — le
 * coût disparaît du fil de rendu — mais {@code ClientLevel} n'est pas conçu pour être lu depuis un
 * autre fil : un chunk qui se décharge pendant qu'on le traverse est une course de données, et le
 * symptôme est un plantage rare et non reproductible. Ce projet a déjà refusé une entité parallèle
 * pour cette raison (voir {@code notes/MINE.md}, ligne « async »).
 *
 * <p>Le coût est donc maîtrisé autrement, par trois leviers qui suffisent :
 *
 * <ul>
 *   <li><b>Le cache.</b> Une entité n'est réexaminée qu'après {@link #delayNanos} — cent
 *       millisecondes par défaut, soit une vérification toutes les six images à soixante par
 *       seconde. Entre deux, on relit une réponse déjà connue.</li>
 *   <li><b>Le plafond par image.</b> Au plus {@link #budget} lancers par image. Au-delà, les
 *       entités non encore examinées gardent leur dernier état — <b>visible</b> par défaut, jamais
 *       l'inverse : un plafond atteint ne doit pas faire disparaître une créature.</li>
 *   <li><b>L'arrêt au premier succès.</b> Le lancer s'interrompt dès qu'un bloc opaque est
 *       rencontré, et l'examen d'une entité s'interrompt dès qu'un seul de ses points est atteint.
 *       Une créature bien visible coûte donc un seul rayon, souvent très court.</li>
 * </ul>
 *
 * <h2>Ce que ce module refuse de cacher</h2>
 *
 * <p>Un faux positif ici n'est pas une perte de performance, c'est une créature invisible — le
 * défaut le plus visible qu'un mod d'optimisation puisse produire. Les garde-fous sont donc
 * généreux : rien de plus près que {@link #near} blocs, rien qui brille ou qui brûle, rien qui porte
 * un nom. Voir {@link #spared}.
 */
public final class Shroud {
    /**
     * Points de la boîte englobante testés avant de déclarer une entité cachée.
     *
     * <p>Un seul point — le centre — suffirait si les créatures étaient des points. Elles ne le sont
     * pas : une vache dont le centre est derrière un muret mais dont la tête dépasse doit rester
     * visible. On teste donc le centre puis quatre coins, en s'arrêtant au premier qui passe.
     *
     * <p>Cinq, et non les huit coins : les quatre retenus sont deux à deux diagonalement opposés,
     * ce qui couvre les quatre directions d'un dépassement partiel. Les quatre autres n'ajouteraient
     * de l'information que pour une occultation en biais très particulière, au prix de soixante pour
     * cent de rayons en plus sur le cas défavorable — celui, justement, où l'entité est cachée et où
     * tous les points sont donc parcourus.
     */
    private static final int PROBES = 5;

    /** Garde-fou du parcours voxel : au-delà, on déclare visible plutôt que de boucler. */
    private static final int MAX_STEPS = 512;

    /** Retrait appliqué aux coins, pour ne pas sonder l'air juste au bord de la boîte. */
    private static final double INSET = 0.1d;

    /** Délai avant réexamen d'une même entité. */
    private static long delayNanos = 100_000_000L;

    /** En deçà de cette distance, une entité n'est jamais cachée. */
    private static double near = 4.0d;

    /** Au-delà de cette distance, on ne lance plus de rayon — voir {@link #spared}. */
    private static double far = 128.0d;

    /** Au-delà de cette taille de boîte, une entité n'est jamais cachée — voir {@link #spared}. */
    private static double bulky = 4.0d;

    /** Lancers autorisés par image. */
    private static int budget = 64;

    /** Prochaine date d'examen, par identifiant d'entité. */
    private static final Int2LongOpenHashMap NEXT = new Int2LongOpenHashMap();

    /** Les entités actuellement jugées cachées. */
    private static final IntOpenHashSet HIDDEN = new IntOpenHashSet();

    /**
     * Les points du monde déjà sondés, par position de bloc.
     *
     * <p>Le signe de la valeur porte la réponse — négative pour « caché », positive pour
     * « visible » — et sa valeur absolue l'échéance. Une seule table, une seule recherche, aucun
     * objet intermédiaire sur un chemin appelé des centaines de fois par seconde.
     */
    private static final it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap SPOTS =
            new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap();

    private static int spent;
    private static long seen;
    private static long veiled;
    private static long rays;

    /**
     * Créatures et points voilés pendant l'image en cours.
     *
     * <h2>Un compteur cumulé ne dit rien à personne</h2>
     *
     * <p>La jauge affichait d'abord {@link #veiled}, qui court depuis le lancement du jeu. Après
     * quelques minutes, elle annonçait « voilé 541 439 » — un nombre exact, impossible à
     * interpréter, et qui grossissait même en regardant un mur. Ce qui intéresse le joueur est
     * <b>combien le module écarte en ce moment</b>, parce que c'est cela qui varie selon l'endroit
     * où il se tient.
     */
    private static int veiledThisFrame;

    private static int veiledLastFrame;

    private Shroud() {}

    /**
     * Ouvre une image : rend son plafond de lancers et purge le cache s'il a dérivé.
     *
     * <p>Appelée depuis l'entrée de {@code extractVisibleEntities}, donc exactement une fois par
     * image, et sur le fil de rendu.
     */
    public static void openFrame() {
        spent = 0;
        veiledLastFrame = veiledThisFrame;
        veiledThisFrame = 0;
        // Les identifiants d'entités mortes ne sont jamais retirés un à un : les suivre coûterait
        // plus que de laisser la table enfler puis la vider d'un coup. Le seuil est large devant le
        // nombre d'entités qu'un client affiche, et la purge ne fait que provoquer un réexamen.
        if (NEXT.size() > 8192) {
            NEXT.clear();
            HIDDEN.clear();
        }
        if (SPOTS.size() > 8192) {
            SPOTS.clear();
        }
    }

    /** Remet le voile à zéro — changement de monde, ou bascule de l'interrupteur. */
    public static void forget() {
        NEXT.clear();
        HIDDEN.clear();
        SPOTS.clear();
        spent = 0;
    }

    /**
     * Ce point du monde est-il caché par de la pierre, vu depuis l'œil ?
     *
     * <h2>Le voile appliqué à ce qui n'est pas une créature</h2>
     *
     * <p>Une particule née derrière un mur coûte trois fois : sa création, son tick à chaque image
     * tant qu'elle vit, et son rendu. Vanilla n'en écarte aucune sur ce critère — il filtre par
     * distance (trente-deux blocs) et écarte du <em>rendu</em> ce qui sort du champ de vision, mais
     * une particule occultée est créée, tickée et conservée comme les autres.
     *
     * <p>Refuser sa naissance supprime les trois coûts d'un coup. C'est le même mécanisme que pour
     * les créatures, appliqué là où il rapporte davantage : une bête voilée continue d'exister côté
     * serveur, une particule refusée n'existe nulle part.
     *
     * <h2>Un cache par bloc, et non par particule</h2>
     *
     * <p>Une ferme qui tourne engendre des centaines de particules par seconde, et presque toujours
     * <b>aux mêmes endroits</b> — au-dessus du même bloc, dans le même coin de pièce. Lancer un
     * rayon par particule serait ruineux ; en lancer un par <em>bloc</em>, réutilisé pendant une
     * fraction de seconde, ne l'est pas.
     *
     * <p>La réponse par défaut, ici encore, est <b>visible</b> : budget épuisé, cache absent, doute
     * quelconque, la particule naît. Un mod d'optimisation qui fait disparaître des effets visuels
     * se remarque bien plus qu'un mod lent.
     */
    public static boolean spotHidden(Level level, double x, double y, double z,
            double camX, double camY, double camZ) {
        double dx = x - camX;
        double dy = y - camY;
        double dz = z - camZ;
        double square = dx * dx + dy * dy + dz * dz;
        if (square < near * near || square > far * far) {
            return false;
        }

        long key = net.minecraft.core.BlockPos.asLong(
                Mth.floor(x), Mth.floor(y), Mth.floor(z));
        long now = System.nanoTime();
        long cached = SPOTS.get(key);
        if (cached != 0L && now < Math.abs(cached)) {
            return cached < 0L;
        }
        if (spent >= budget) {
            return false;
        }
        spent++;

        boolean blocked = !clear(level, camX, camY, camZ, x, y, z,
                new BlockPos.MutableBlockPos());
        // L'échéance porte la réponse dans son signe : négative pour « caché », positive pour
        // « visible ». Une seule table, une seule recherche, pas d'objet intermédiaire.
        long due = now + delayNanos;
        SPOTS.put(key, blocked ? -due : due);
        if (blocked) {
            veiled++;
        }
        return blocked;
    }

    /**
     * Cette entité est-elle cachée par de la géométrie opaque ?
     *
     * @return vrai seulement si tous les points sondés sont bloqués. En cas de doute, de budget
     *     épuisé ou de garde-fou, faux — c'est-à-dire : on la dessine.
     */
    public static boolean hidden(Entity entity, double camX, double camY, double camZ) {
        seen++;
        if (spared(entity, camX, camY, camZ)) {
            return false;
        }

        int id = entity.getId();
        long now = System.nanoTime();
        long due = NEXT.getOrDefault(id, 0L);
        if (now < due) {
            boolean cached = HIDDEN.contains(id);
            if (cached) {
                veiled++;
                veiledThisFrame++;
            }
            return cached;
        }

        if (spent >= budget) {
            // Plafond atteint : on garde la réponse précédente, qui vaut « visible » si l'entité
            // n'a jamais été examinée. Jamais l'inverse — voir le Javadoc de classe.
            boolean cached = HIDDEN.contains(id);
            if (cached) {
                veiled++;
                veiledThisFrame++;
            }
            return cached;
        }

        spent++;
        boolean blocked = !reachable(entity, camX, camY, camZ);
        NEXT.put(id, now + stagger(id));
        if (blocked) {
            HIDDEN.add(id);
            veiled++;
            veiledThisFrame++;
        } else {
            HIDDEN.remove(id);
        }
        return blocked;
    }

    /**
     * Les cas où l'on renonce à cacher, quoi qu'en dise la géométrie.
     *
     * <p>Chacun corrige un défaut visible plutôt qu'un coût :
     *
     * <ul>
     *   <li><b>Trop près.</b> Sous quelques blocs, l'erreur d'un lancer de rayon — un coin de mur,
     *       une marche — se voit immédiatement, et le gain est nul puisqu'une entité proche est
     *       rarement occultée longtemps.</li>
     *   <li><b>Qui brille ou qui brûle.</b> Une créature en feu, marquée d'un halo, ou porteuse
     *       d'un effet lumineux projette de la lumière et des particules que le joueur voit même
     *       sans la voir elle. La cacher produirait un feu sans rien dedans.</li>
     *   <li><b>Qui porte un nom.</b> L'étiquette d'un animal nommé se lit à travers les blocs —
     *       c'est un comportement voulu du jeu, pas un oubli. Le retirer serait une régression de
     *       fonctionnalité déguisée en optimisation.</li>
     *   <li><b>Qui transporte.</b> Le véhicule du joueur et ce qu'il porte restent dessinés :
     *       vanilla le garantit déjà juste après notre point d'accroche, et lui désobéir ici
     *       casserait la vue à la première selle.</li>
     * </ul>
     */
    private static boolean spared(Entity entity, double camX, double camY, double camZ) {
        double dx = entity.getX() - camX;
        double dy = entity.getY() - camY;
        double dz = entity.getZ() - camZ;
        double square = dx * dx + dy * dy + dz * dz;
        if (square < near * near) {
            return true;
        }
        // Trop loin : le rayon serait long, donc cher, pour une créature que la distance de rendu
        // d'entités écarte souvent déjà. Le plafond de pas y suffirait, mais il ne le ferait
        // qu'APRÈS avoir payé cinq cents lectures de blocs.
        if (square > far * far) {
            return true;
        }
        if (entity.isOnFire() || entity.isCurrentlyGlowing()) {
            return true;
        }
        if (entity.hasCustomName() || entity.isVehicle()) {
            return true;
        }
        // Trop grosse : cinq points sondés décrivent mal un dragon ou une baleine de mod. Une bête
        // dont la boîte dépasse largement le bloc a de fortes chances de dépasser aussi du mur, et
        // la faire disparaître se verrait de loin.
        AABB box = entity.getBoundingBox();
        return box.getXsize() > bulky || box.getYsize() > bulky || box.getZsize() > bulky;
    }

    /** Un seul point atteint suffit à déclarer l'entité visible. */
    private static boolean reachable(Entity entity, double camX, double camY, double camZ) {
        AABB box = entity.getBoundingBox();
        Level level = entity.level();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        // Le centre en premier : c'est le point qui réussit le plus souvent, et un succès arrête
        // tout l'examen.
        if (clear(level, camX, camY, camZ, box.getCenter().x, box.getCenter().y, box.getCenter().z,
                cursor)) {
            return true;
        }

        double x0 = box.minX + INSET;
        double y0 = box.minY + INSET;
        double z0 = box.minZ + INSET;
        double x1 = box.maxX - INSET;
        double y1 = box.maxY - INSET;
        double z1 = box.maxZ - INSET;

        // Quatre coins deux à deux diagonalement opposés — voir PROBES.
        return clear(level, camX, camY, camZ, x0, y0, z0, cursor)
                || clear(level, camX, camY, camZ, x1, y1, z1, cursor)
                || clear(level, camX, camY, camZ, x0, y1, z1, cursor)
                || clear(level, camX, camY, camZ, x1, y0, z0, cursor);
    }

    /**
     * Le parcours voxel d'Amanatides et Woo, de l'œil vers un point.
     *
     * <h2>Pourquoi pas {@code Level.clip}</h2>
     *
     * <p>Le lancer de rayon du jeu résout des <em>formes de collision</em> : il alloue un contexte,
     * interroge la forme exacte de chaque bloc, gère les fluides et les boîtes partielles. Rien de
     * tout cela ne sert ici — la seule question posée est « ce bloc bouche-t-il la vue ». Un
     * parcours de grille qui teste {@code isSolidRender()} répond à cette question et à aucune
     * autre, sans rien allouer par appel.
     *
     * <h2>Les deux extrémités ne sont pas testées</h2>
     *
     * <p>Le bloc de départ est celui où se trouve l'œil : le tester ferait disparaître le monde
     * entier dès que la caméra effleure un bloc plein. Le bloc d'arrivée est celui où se trouve
     * l'entité : le tester ferait disparaître toute créature engagée dans un bloc — un mob poussé
     * dans un mur, une araignée collée à un plafond — au moment précis où le joueur la cherche.
     *
     * @return vrai si aucun bloc opaque ne sépare les deux points.
     */
    private static boolean clear(Level level, double x0, double y0, double z0,
            double x1, double y1, double z1, BlockPos.MutableBlockPos cursor) {
        int bx = Mth.floor(x0);
        int by = Mth.floor(y0);
        int bz = Mth.floor(z0);
        final int ex = Mth.floor(x1);
        final int ey = Mth.floor(y1);
        final int ez = Mth.floor(z1);

        double dx = x1 - x0;
        double dy = y1 - y0;
        double dz = z1 - z0;

        int stepX = dx > 0d ? 1 : -1;
        int stepY = dy > 0d ? 1 : -1;
        int stepZ = dz > 0d ? 1 : -1;

        // Une composante nulle donne un pas infini : la division par zéro en double vaut l'infini,
        // et la comparaison qui suit ne choisira jamais cet axe. C'est exact, et sans branche.
        double deltaX = Math.abs(1d / dx);
        double deltaY = Math.abs(1d / dy);
        double deltaZ = Math.abs(1d / dz);

        double maxX = deltaX * (dx > 0d ? 1d - frac(x0) : frac(x0));
        double maxY = deltaY * (dy > 0d ? 1d - frac(y0) : frac(y0));
        double maxZ = deltaZ * (dz > 0d ? 1d - frac(z0) : frac(z0));

        for (int step = 0; step < MAX_STEPS; step++) {
            if (bx == ex && by == ey && bz == ez) {
                return true;
            }
            if (maxX < maxY) {
                if (maxX < maxZ) {
                    bx += stepX;
                    maxX += deltaX;
                } else {
                    bz += stepZ;
                    maxZ += deltaZ;
                }
            } else if (maxY < maxZ) {
                by += stepY;
                maxY += deltaY;
            } else {
                bz += stepZ;
                maxZ += deltaZ;
            }
            // Le bloc d'arrivée n'est pas testé : voir le Javadoc.
            if (bx == ex && by == ey && bz == ez) {
                return true;
            }
            rays++;
            cursor.set(bx, by, bz);
            if (level.getBlockState(cursor).isSolidRender()) {
                return false;
            }
        }
        // Rayon anormalement long : on préfère dessiner que faire disparaître.
        return true;
    }

    private static double frac(double value) {
        return value - Math.floor(value);
    }

    /**
     * Le délai avant réexamen, décalé d'une créature à l'autre.
     *
     * <h2>Mille échéances tombant ensemble</h2>
     *
     * <p>Toutes les bêtes d'un troupeau entrent dans le champ à la même image. Avec un délai fixe,
     * elles en ressortent donc aussi à la même image : cent millisecondes plus tard, les mille
     * échéances expirent d'un coup, et le plafond par image étale la rafale sur seize images
     * consécutives — puis plus rien pendant six autres, puis la rafale recommence.
     *
     * <p>Le premier relevé libre du Voile porte cette signature : une médiane <b>excellente</b>
     * (5,03 ms contre 12,61 sans le module) et un centile le plus lent <b>pire</b> que sans lui
     * (48,22 ms contre 45,78). Beaucoup d'images très rapides, et un battement périodique qui mange
     * le gain — c'est exactement ce qu'un travail groupé produit.
     *
     * <p>Le décalage est tiré de l'identifiant de la créature, donc stable pour elle : sa prochaine
     * échéance ne dérive pas d'un examen à l'autre, elle est simplement décalée de celle de sa
     * voisine. Le mélange par multiplication et décalage suffit à disperser des identifiants
     * consécutifs, qui sont le cas normal pour un troupeau né d'une même boucle.
     */
    private static long stagger(int id) {
        int mixed = id * 0x9E3779B9;
        mixed ^= mixed >>> 16;
        // Entre la moitié et la totalité du délai : jamais zéro, jamais plus que demandé.
        long spread = delayNanos / 2L;
        return delayNanos - (spread == 0L ? 0L : Math.floorMod(mixed, spread));
    }

    // --- Réglages et rapport ------------------------------------------------

    public static void tune(long delayMillis, double nearBlocks, int perFrame, double farBlocks,
            double bulkyBlocks) {
        delayNanos = Math.max(0L, delayMillis) * 1_000_000L;
        near = Math.max(0d, nearBlocks);
        budget = Math.max(1, perFrame);
        far = Math.max(near, farBlocks);
        bulky = Math.max(1d, bulkyBlocks);
    }

    /** Entités examinées depuis le dernier rapport. */
    public static long seen() {
        return seen;
    }

    /** Entités effectivement voilées depuis le dernier rapport — pour les bancs. */
    public static long veiled() {
        return veiled;
    }

    /** Voilées pendant la dernière image complète — pour la jauge. Voir {@link #veiledThisFrame}. */
    public static int veiledPerFrame() {
        return veiledLastFrame;
    }

    /** Blocs traversés par les lancers — le prix payé pour cette économie. */
    public static long rays() {
        return rays;
    }

    public static void resetCounters() {
        seen = 0L;
        veiled = 0L;
        rays = 0L;
    }
}
