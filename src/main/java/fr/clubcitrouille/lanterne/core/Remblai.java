package fr.clubcitrouille.lanterne.core;

import java.lang.reflect.Method;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * Le remblai : poser et casser beaucoup de blocs, et ne payer que ce qui change quelque chose.
 *
 * <h2>Le geste visé</h2>
 *
 * <p>{@code /fill}, {@code /clone}, une machine qui rase un carré, un joueur qui mine à la chaîne :
 * c'est la même mécanique, <b>beaucoup de modifications de blocs dans très peu de ticks</b>. Écrire
 * l'état dans la palette est le poste le moins cher de l'affaire ; tout le reste est du travail
 * <em>autour</em> de la pose.
 *
 * <h2>Ce que {@code /fill} fait déjà, et qu'il ne faut surtout pas réinventer</h2>
 *
 * <p>La première idée qui vient — « il notifie les voisins à chaque bloc, accumulons et notifions
 * une fois à la fin » — est <b>déjà celle de Mojang</b>. Il faut lire {@code FillCommand.fillBlocks}
 * avant de supposer quoi que ce soit :
 *
 * <pre>
 * block.place(level, pos, 2 | (strict ? 816 : 256));   // 2 = UPDATE_CLIENTS, 256 = SKIP_BE_SIDEEFFECTS
 * …
 * for (UpdatedPosition pos : updatePositions) {
 *     level.updateNeighboursOnBlockSet(pos.pos, pos.oldState);   // une seule fois, à la fin
 * }
 * </pre>
 *
 * <p>Le drapeau {@code UPDATE_NEIGHBORS = 1} n'est <b>pas</b> passé : pendant la boucle, aucune
 * notification de voisinage n'a lieu. Elles sont toutes reportées après la dernière pose. Ce module
 * n'a donc rien à différer de ce côté — c'est fait.
 *
 * <h2>L'asymétrie, et c'est elle le gisement</h2>
 *
 * <p>Le drapeau {@code UPDATE_KNOWN_SHAPE = 16} n'est pas passé non plus. Or dans
 * {@code Level.markAndNotifyBlock} il commande un tout autre bloc de code :
 *
 * <pre>
 * if ((updateFlags &amp; 16) == 0 &amp;&amp; updateLimit &gt; 0) {
 *     oldState.updateIndirectNeighbourShapes(…);
 *     blockState.updateNeighbourShapes(…);      // SIX appels, un par direction
 *     blockState.updateIndirectNeighbourShapes(…);
 * }
 * </pre>
 *
 * <p>Autrement dit : Mojang a différé les <b>notifications</b> et laissé tourner les <b>recalculs de
 * forme</b>, six par bloc posé, à l'intérieur de la boucle. C'est là qu'il faut regarder, et nulle
 * part ailleurs.
 *
 * <h2>Ce que coûte un recalcul de forme qui ne change rien</h2>
 *
 * <p>{@code updateNeighbourShapes} traverse {@code Level.neighborShapeChanged} puis
 * {@code CollectingNeighborUpdater.shapeUpdate}, qui <b>alloue</b> :
 *
 * <pre>
 * this.addAndRun(pos, new ShapeUpdate(direction, neighborState, pos.immutable(), neighborPos.immutable(), …));
 * </pre>
 *
 * <p>Un enregistrement {@code ShapeUpdate}, plus une {@code BlockPos} immuable née d'une
 * {@code MutableBlockPos} — deux objets par direction, <b>douze objets par bloc posé</b>. Puis
 * l'empilement dans la pile du notificateur, son dépilement, et enfin :
 *
 * <pre>
 * // NeighborUpdater.executeShapeUpdate
 * BlockState currentState = level.getBlockState(pos);
 * BlockState newState = currentState.updateShape(…);
 * Block.updateOrDestroy(currentState, newState, level, pos, …);   // ne fait RIEN si newState == currentState
 * </pre>
 *
 * <p>La chaîne se termine sur {@code Block.updateOrDestroy}, dont le corps entier est gardé par
 * {@code if (newState != blockState)}. Pour de la pierre contre de la pierre, pour de l'air contre
 * de la terre, <b>rien ne se passe</b> — mais tout a été payé.
 *
 * <p>Le même raisonnement vaut pour le second poste,
 * {@code ServerLevel.updateNeighboursOnBlockSet}, appelé une fois par bloc modifié après la boucle
 * de {@code /fill} : il alloue un {@code MultiNeighborUpdate}, six {@code BlockPos} par
 * {@code relative(direction)}, et aboutit à {@code state.handleNeighborChanged(…)} →
 * {@code getBlock().neighborChanged(…)}.
 *
 * <h2>Le théorème, et il est local</h2>
 *
 * <p>Les deux chaînes se terminent sur une méthode de {@link BlockBehaviour} dont l'implémentation
 * par défaut est <b>vide ou identité</b> :
 *
 * <pre>
 * protected BlockState updateShape(…) { return state; }                  // identité
 * protected void neighborChanged(…) { }                                  // vide
 * </pre>
 *
 * <p>Et {@link Block} lui-même ne les redéfinit ni l'une ni l'autre — vérifié dans la source 26.2.
 * D'où :
 *
 * <blockquote><b>Si la classe du bloc visé ne redéfinit pas {@code updateShape}, alors
 * {@code executeShapeUpdate} rend {@code newState == currentState}, donc {@code updateOrDestroy} ne
 * fait rien, donc l'appel entier est observationnellement vide.</b> Idem pour
 * {@code neighborChanged}.</blockquote>
 *
 * <p>Ce n'est pas une heuristique et ce n'est pas un pari sur l'ordre : c'est une propriété
 * <b>locale</b>, vraie bloc par bloc, indépendante de la séquence. Rien n'est différé, rien n'est
 * fusionné, rien n'est réordonné. On ne supprime que des appels dont le corps est prouvé vide.
 *
 * <p>C'est délibérément plus modeste que l'idée de « différer et fusionner sur le périmètre réel ».
 * Cette idée-là a été écrite, puis refusée : une torche posée à l'intérieur de la zone tombe et
 * <b>lâche son objet</b> quand {@code /fill} détruit son support avant de l'atteindre — c'est
 * {@code Block.updateOrDestroy} → {@code destroyBlock(pos, (flags &amp; 32) == 0, …)}, et le drapeau 32
 * n'est pas posé. Différer jusqu'à la fin ferait disparaître cet objet. L'ordre est observable ; il
 * n'est donc pas négociable.
 *
 * <h2>Les trois interventions</h2>
 *
 * <ol>
 *   <li>{@code RemblaiFormeMixin} — {@code Level.neighborShapeChanged} : on n'entre pas dans le
 *       notificateur quand le bloc visé ne réagit pas aux formes. Économie : deux objets et un
 *       aller-retour de pile, six fois par bloc posé.</li>
 *   <li>{@code RemblaiVoisinsMixin} — {@code ServerLevel.updateNeighborsAt} : on n'entre pas non
 *       plus quand <b>aucun</b> des six voisins ne réagit. Économie : sept objets et six répartitions
 *       virtuelles, une fois par bloc modifié.</li>
 *   <li>{@code RemblaiNavigationMixin} — {@code ServerLevel.sendBlockUpdated} : la comparaison des
 *       deux formes de collision ne sert qu'à décider s'il faut relire les chemins des bêtes ; quand
 *       aucune bête ne navigue, elle ne décide rien. On l'évite.</li>
 * </ol>
 *
 * <h2>Le coût de l'oracle, et pourquoi il ne s'ajoute pas</h2>
 *
 * <p>Répondre « ce bloc réagit-il ? » demande de lire l'état du bloc visé. <b>Vanilla le lit de
 * toute façon</b>, à l'intérieur de {@code executeShapeUpdate} et de {@code MultiNeighborUpdate} —
 * simplement plus tard, après les allocations. On ne fait donc pas une lecture de plus : on la fait
 * plus tôt, et on s'arrête si elle suffit.
 *
 * <p>Reste le classement du bloc lui-même. Il passe par la réflexion, ce qui serait inacceptable sur
 * un chemin parcouru des millions de fois — {@code lab/Boom} a payé cette leçon : « sur un chemin
 * très chaud, l'interception EST le coût ». Il est donc fait <b>une seule fois par bloc du jeu</b>
 * et rangé dans un champ ajouté à {@link Block} par {@code RemblaiBlockMixin}. Une lecture de champ,
 * pas une table de hachage, pas un {@code ClassValue}.
 *
 * <h2>Ce qui pourrait casser, dit sans détour</h2>
 *
 * <ul>
 *   <li><b>Un autre mod qui injecte dans {@code BlockBehaviour.updateShape} ou
 *       {@code BlockBehaviour.neighborChanged}</b> — les méthodes vides héritées par tous les blocs.
 *       L'oracle les croirait toujours vides et le mod tiers serait muselé. C'est la seule façon
 *       connue de rendre ce module faux, et c'est pour cela qu'il est débrayable
 *       ({@code Config.REMBLAI}). Injecter dans ces deux méthodes-là est en pratique intenable —
 *       elles sont appelées pour chaque bloc du monde.</li>
 *   <li><b>Le compteur de chaînes.</b> {@code CollectingNeighborUpdater} abandonne au-delà de
 *       {@code maxChainedNeighborUpdates} mises à jour enchaînées. Ne plus compter celles qui ne
 *       faisaient rien recule ce plafond : une chaîne pathologique ira un peu plus loin avant d'être
 *       coupée. Le plafond est un état de renoncement, pas un comportement de jeu.</li>
 *   <li><b>L'observateur de débogage.</b> {@code CollectingNeighborUpdater.setDebugListener}, alimenté
 *       par la vue de débogage des mises à jour de voisinage, verra moins de positions. Il verra
 *       exactement celles qui font quelque chose.</li>
 * </ul>
 *
 * <p>Ce qui <b>ne</b> change pas, et qui a été vérifié ligne à ligne : l'état final des blocs, la
 * lumière (rien ici ne touche {@code checkBlock} ni {@code skyLightSources}), la carte des hauteurs,
 * les ticks programmés, les paquets envoyés au client, et l'événement {@code NeighborNotifyEvent} de
 * NeoForge — il est levé par la surcharge à deux arguments de {@code updateNeighborsAt}, en amont du
 * point qu'on intercepte.
 *
 * <h2>La borne, annoncée avant la mesure</h2>
 *
 * <p>Ce module ne touche ni au moteur de lumière, ni à la carte des hauteurs, ni à l'écriture dans
 * la palette. Sur un {@code /fill} d'air en pierre, ces trois postes restent entiers. L'inventaire
 * des allocations par bloc posé donne l'ordre de grandeur de ce qu'on peut espérer :
 *
 * <pre>
 * enlevé    12 objets  recalculs de forme (6 × ShapeUpdate + 6 × BlockPos)
 * enlevé     7 objets  notification de voisinage (MultiNeighborUpdate + 6 × BlockPos)
 * enlevé     1 objet   la liste des navigations, quand aucune bête ne navigue
 * gardé      —         palette, 4 cartes de hauteur, file de lumière, paquet client, POI
 * </pre>
 *
 * <p>Vingt objets sur vingt-trois, mais <b>pas</b> vingt postes de calcul sur vingt-trois : c'est un
 * facteur deux à deux et demi qu'on peut défendre, pas un facteur six. {@code lab/Terrassement}
 * tranchera, et publiera le chiffre qu'il trouve, pas celui qu'on espère.
 */
public final class Remblai {
    private Remblai() {}

    /** Le bloc a été classé. Sans ce bit, l'octet vaut zéro et le classement reste à faire. */
    public static final byte CLASSE = 1;

    /** Le bloc redéfinit {@code updateShape} : un recalcul de forme peut le changer. */
    public static final byte FORMES = 2;

    /** Le bloc redéfinit {@code neighborChanged} : une notification de voisinage peut l'atteindre. */
    public static final byte VOISINS = 4;

    /** Un bloc dont on ne sait rien de bon : on le suppose sensible à tout. */
    private static final byte PRUDENT = CLASSE | FORMES | VOISINS;

    /**
     * Les six directions, retenues une fois.
     *
     * <p>{@code Direction.values()} <b>recopie</b> son tableau interne à chaque appel — c'est le
     * contrat de {@code Enum.values()}. Sur un chemin parcouru une fois par bloc modifié, cela ferait
     * un tableau neuf par bloc, soit exactement le genre d'objet que ce module est écrit pour
     * supprimer. Vanilla fait de même avec {@code NeighborUpdater.UPDATE_ORDER}.
     */
    private static final Direction[] SIX = Direction.values();

    /**
     * Le porteur du classement, ajouté à {@link Block} par {@code RemblaiBlockMixin}.
     *
     * <p>Un champ sur le bloc, et non une table dans ce fichier. La différence se mesure : l'oracle
     * est consulté douze fois par bloc posé, soit deux cent mille fois pour un {@code /fill} de
     * seize mille blocs. Une lecture de champ y coûte une nanoseconde, une table de hachage cinq à
     * dix — un demi pour cent du remplissage entier, pour rien.
     *
     * <p>Le classement est écrit sans verrou. Deux fils qui classent le même bloc en même temps
     * calculent la même valeur et écrivent le même octet : la course est bénigne par construction,
     * puisque la réponse ne dépend que de la classe du bloc, qui ne change jamais.
     */
    public interface Inertie {
        /** L'octet de classement, ou zéro tant qu'il n'a pas été calculé. */
        byte lanterne$inertie();

        /** Range l'octet de classement. */
        void lanterne$inertie(byte valeur);
    }

    /**
     * L'oracle est-il crédible ?
     *
     * <p>Tout ce module repose sur une hypothèse de nommage : que {@code BlockBehaviour} déclare
     * bien, sous ces noms-là, les deux méthodes dont on prouve la vacuité. Si un jour la
     * correspondance des noms change — renommage amont, cartographie différente — la recherche par
     * réflexion ne trouverait rien, conclurait « aucune redéfinition » pour <b>tous</b> les blocs, et
     * le mod muselerait le jeu entier en silence.
     *
     * <p>On préfère donc vérifier l'hypothèse une fois, au premier classement, et rendre l'oracle
     * inutile s'il est faux : chaque bloc est alors déclaré sensible à tout, et les trois mixins
     * laissent passer. Un module qui s'annule coûte une comparaison ; un module qui se trompe casse
     * la redstone.
     */
    private static final boolean ORACLE_CREDIBLE =
            declare(BlockBehaviour.class, "updateShape") && declare(BlockBehaviour.class, "neighborChanged");

    /** Un seul avertissement, et pas un par bloc. */
    private static boolean avertissementDonne;

    /** Le banc a-t-il mis le module en sommeil ? Voir {@link #suspendre(boolean)}. */
    private static boolean suspendu;

    /** Recalculs de forme évités parce que le bloc visé ne réagit pas. */
    private static long formesEvitees;

    /** Notifications de voisinage évitées parce qu'aucun des six voisins ne réagit. */
    private static long voisinagesEvites;

    /** Relectures de chemin évitées faute de bête qui navigue. */
    private static long navigationsEvitees;

    /**
     * Le module agit-il ?
     *
     * <p>Deux conditions : le réglage, et l'interrupteur du banc. {@code Settings.remblai()} n'est
     * pas filtré par l'interrupteur général du mod — c'est pourquoi {@link #suspendre(boolean)}
     * existe et pourquoi {@code lab/Terrassement} s'en sert plutôt que de {@code Settings.setEnabled}.
     */
    public static boolean actif() {
        return !suspendu && Settings.remblai();
    }

    /**
     * Met le module en sommeil, ou le réveille.
     *
     * <p><b>Réservé au banc.</b> Une épreuve honnête mesure deux fois le même monde, la seule
     * différence étant ce module ; il lui faut donc un interrupteur qui n'éteigne rien d'autre.
     */
    public static void suspendre(boolean valeur) {
        suspendu = valeur;
    }

    /**
     * Le recalcul de forme visant {@code pos} peut-il changer quoi que ce soit ?
     *
     * <p>Rendue {@code false}, la réponse autorise à ne pas entrer dans le notificateur. Elle lit
     * l'état du bloc visé — exactement la lecture que {@code NeighborUpdater.executeShapeUpdate}
     * ferait quelques allocations plus loin.
     */
    public static boolean formePeutAgir(BlockGetter monde, BlockPos pos) {
        return (inertie(monde.getBlockState(pos).getBlock()) & FORMES) != 0;
    }

    /** Compte un recalcul de forme évité. */
    public static void noteForme() {
        formesEvitees++;
    }

    /**
     * L'un des six voisins de {@code pos} réagit-il à une notification ?
     *
     * <p>Rendue {@code false}, la réponse autorise à ne pas construire la mise à jour multiple du
     * tout. La boucle s'arrête au premier voisin sensible : dans un champ de redstone, le coût de
     * l'oracle est celui d'une ou deux lectures, pas de six.
     *
     * <p>Le {@code curseur} est fourni par l'appelant et jamais conservé. Il serait absurde d'allouer
     * ici la position qu'on écrit ce module pour ne plus allouer ; le mixin en tient une par monde,
     * ce qui est sûr parce que la boucle ci-dessous n'appelle que {@code getBlockState} — rien qui
     * puisse rentrer à nouveau dans cette méthode avant qu'elle ait fini d'en lire la valeur.
     */
    public static boolean voisinagePeutAgir(BlockGetter monde, BlockPos pos,
                                            BlockPos.MutableBlockPos curseur) {
        for (Direction direction : SIX) {
            curseur.setWithOffset(pos, direction);
            if ((inertie(monde.getBlockState(curseur).getBlock()) & VOISINS) != 0) {
                return true;
            }
        }
        return false;
    }

    /** Compte une notification de voisinage évitée. */
    public static void noteVoisinage() {
        voisinagesEvites++;
    }

    /** Compte une relecture de chemin évitée. */
    public static void noteNavigation() {
        navigationsEvitees++;
    }

    /** Recalculs de forme évités depuis le démarrage. */
    public static long formesEvitees() {
        return formesEvitees;
    }

    /** Notifications de voisinage évitées depuis le démarrage. */
    public static long voisinagesEvites() {
        return voisinagesEvites;
    }

    /** Relectures de chemin évitées depuis le démarrage. */
    public static long navigationsEvitees() {
        return navigationsEvitees;
    }

    /** Remet les compteurs à zéro, pour que le banc mesure une moitié à la fois. */
    public static void oublier() {
        formesEvitees = 0;
        voisinagesEvites = 0;
        navigationsEvitees = 0;
    }

    /**
     * L'octet de classement d'un bloc, calculé au premier passage.
     *
     * <p>Le transtypage vers {@link Inertie} est sûr : {@code RemblaiBlockMixin} vise {@link Block},
     * dont tous les blocs du jeu et des mods héritent. S'il échouait, c'est que le mixin n'a pas été
     * appliqué — et {@code lanterne.mixins.json} déclare {@code "required": true}, donc le jeu
     * n'aurait pas démarré.
     */
    private static byte inertie(Block bloc) {
        Inertie porteur = (Inertie) bloc;
        byte connu = porteur.lanterne$inertie();
        if (connu != 0) {
            return connu;
        }
        byte calcule = classer(bloc);
        porteur.lanterne$inertie(calcule);
        return calcule;
    }

    /**
     * Classe un bloc, une fois pour toutes.
     *
     * <p>La recherche remonte la hiérarchie depuis la classe réelle du bloc et s'arrête à
     * {@link BlockBehaviour}, où les deux méthodes sont déclarées vides. Toute déclaration
     * rencontrée en chemin — quelle que soit sa signature, surcharge comprise — vaut redéfinition :
     * on préfère une réponse trop prudente à une réponse trop fine.
     *
     * <p>Une hiérarchie qui n'atteindrait jamais {@code BlockBehaviour} est une anomalie, et une
     * anomalie se traite en supposant le pire.
     */
    private static byte classer(Block bloc) {
        if (!ORACLE_CREDIBLE) {
            if (!avertissementDonne) {
                avertissementDonne = true;
                Lanterne.LOG.warn(
                        "[REMBLAI] BlockBehaviour ne déclare plus « updateShape » et « neighborChanged » "
                                + "sous ces noms : le module se neutralise, aucun appel ne sera évité.");
            }
            return PRUDENT;
        }
        byte valeur = CLASSE;
        Class<?> depart = bloc.getClass();
        if (redefinit(depart, "updateShape")) {
            valeur |= FORMES;
        }
        if (redefinit(depart, "neighborChanged")) {
            valeur |= VOISINS;
        }
        return valeur;
    }

    /** {@code depart} ou l'un de ses parents, sous {@link BlockBehaviour}, déclare-t-il {@code nom} ? */
    private static boolean redefinit(Class<?> depart, String nom) {
        for (Class<?> classe = depart; classe != null; classe = classe.getSuperclass()) {
            if (classe == BlockBehaviour.class) {
                return false;
            }
            if (declare(classe, nom)) {
                return true;
            }
        }
        return true;
    }

    /** {@code classe} déclare-t-elle une méthode de ce nom, quelle qu'en soit la signature ? */
    private static boolean declare(Class<?> classe, String nom) {
        try {
            for (Method methode : classe.getDeclaredMethods()) {
                if (methode.getName().equals(nom)) {
                    return true;
                }
            }
        } catch (Throwable refus) {
            // Un environnement qui refuse la réflexion rend l'oracle inutilisable : on le dit en
            // répondant « oui », ce qui fait garder l'appel.
            return true;
        }
        return false;
    }
}
