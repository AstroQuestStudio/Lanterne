package fr.clubcitrouille.lanterne.client.ponder;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;

import fr.clubcitrouille.lanterne.Lanterne;
import fr.clubcitrouille.lanterne.content.Contents;
import fr.clubcitrouille.lanterne.content.disc.Groove;
import fr.clubcitrouille.lanterne.content.painting.Easel;
import fr.clubcitrouille.lanterne.content.waypoint.Gates;

/**
 * Le catalogue : les scénarios, écrits en données.
 *
 * <h2>Ce fichier est la preuve de l'architecture</h2>
 *
 * <p>Un scénario tient ici en une vingtaine de lignes qu'on lit de haut en bas comme on lirait la
 * marche à suivre. Ajouter une animation, c'est ajouter une méthode <b>dans ce fichier et nulle part
 * ailleurs</b> — pas une classe, pas un enregistrement, pas un fichier de ressources, pas une ligne
 * dans un registre.
 *
 * <p>C'était le critère : si décrire une scène demandait de toucher cinq fichiers, l'architecture
 * était ratée. Elle demande d'en toucher deux, celui-ci et celui des langues.
 *
 * <h2>Ces animations disent la vérité</h2>
 *
 * <p>Chacune a été écrite en relisant le code qu'elle montre, et pas de mémoire. Le cœur est posé sur
 * la rangée du bas et jamais dans un coin parce que {@code content/waypoint/Frame.java} ne trouve pas
 * un cœur logé dans un coin. Le cadre fait deux cases de large sur trois de haut parce que c'est le
 * plus petit que vanilla accepte. Le briquet vole vers le cœur parce que c'est là que
 * {@code Heart.useItemOn} l'attend.
 *
 * <p>Un guide qui ment coûte plus cher que pas de guide du tout : celui qui ne sait pas cherche
 * ailleurs, celui qui a cru le guide s'acharne.
 *
 * <h2>Ce qui n'est pas ici, et pourquoi</h2>
 *
 * <p><b>La boutique n'a pas de scène.</b> Elle n'a ni bloc, ni objet : c'est une touche et un écran
 * — voir {@code client/shop/Bell.java}. Il n'y a rigoureusement rien à poser sur un plateau, et une
 * animation qui montrerait un décor inventé pour avoir quelque chose à montrer serait exactement le
 * mensonge décrit ci-dessus. Une page de texte lui rendra mieux service.
 */
public final class Guide {
    private Guide() {}

    private static final int AMBER = 0xFFC857;
    private static final int VIOLET = 0x9B7EDE;
    private static final int GREEN = 0x6FCF97;
    private static final int PAPER = 0xE8E8E8;

    private static List<Scene> scenes = List.of();
    /**
     * L'objet vers sa scène.
     *
     * <p>Une table et non un parcours : {@link Hint} l'interroge à <b>chaque infobulle affichée</b>,
     * y compris pour les milliers d'objets qui n'ont pas de guide. Un parcours de listes imbriquées
     * n'aurait rien coûté de mesurable, mais écrire un parcours là où une table s'écrit en trois
     * lignes est le genre de négligence dont ce mod fait profession de se moquer.
     */
    private static Map<Item, Scene> byItem = Map.of();

    /** Le monde pour lequel ces scènes ont été bâties. Voir {@link #all()}. */
    private static Level bound;
    /** Après un échec, l'instant à partir duquel on a le droit de réessayer. */
    private static long retryAt;
    private static boolean complained;

    /**
     * Les scènes, ou une liste vide quand aucun monde n'est chargé.
     *
     * <h2>Un guide ne peut pas exister hors d'une partie, et ce n'est pas un choix</h2>
     *
     * <p>Une première version bâtissait les scènes au premier besoin, en notant qu'il fallait
     * attendre la fin de l'enregistrement des registres. C'était juste, et insuffisant — au point
     * d'avoir fait <b>planter le jeu</b> d'un joueur qui a ouvert le guide depuis le menu principal.
     *
     * <p>La condition réelle est plus forte : en 26.2, {@code new ItemStack(ItemLike)} passe par
     * {@code item.builtInRegistryHolder()} puis {@code new PatchedDataComponentMap(item.components())}
     * — {@code ItemStack.java} lignes 249-255 — et {@code Holder.Reference.components()} lève
     * {@code NullPointerException("Components not bound yet")} tant que le champ est nul
     * ({@code Holder.java} ligne 277). Or ces composants sont liés par
     * {@code ReloadableServerResources}, c'est-à-dire <b>au chargement d'un monde ou d'un paquet de
     * données</b>.
     *
     * <p>Donc : <b>on ne construit pas d'{@code ItemStack} hors d'un monde chargé.</b> C'est une
     * rupture de la 26.2, elle ne se voit à la lecture d'aucune signature, et elle ne se manifeste
     * que depuis le menu principal — là où l'on n'essaie jamais rien en développement.
     *
     * <p>Le cache est lié au <em>monde</em> et non posé une fois pour toutes, pour la même raison :
     * les composants viennent d'un paquet de données, donc une scène bâtie dans un monde pourrait
     * mentir dans un autre.
     */
    public static List<Scene> all() {
        Level level = Minecraft.getInstance().level;
        if (level == null) {
            forget();
            return List.of();
        }
        if (bound == level) {
            return scenes;
        }
        if (System.currentTimeMillis() < retryAt) {
            return scenes;
        }
        build(level);
        return scenes;
    }

    /** Un monde est-il chargé, et le guide a-t-il quelque chose à montrer ? */
    public static boolean ready() {
        return !all().isEmpty();
    }

    /**
     * Bâtit les scènes, et ne laisse jamais rien s'échapper.
     *
     * <p>Au pire de ce qui peut arriver, le guide est vide et une ligne part au journal. Un écran de
     * plantage pour un <em>guide</em> serait le comble : le seul écran du mod dont le propos est
     * d'éviter qu'on se perde.
     */
    private static void build(Level level) {
        try {
            List<Scene> built = new ArrayList<>();
            built.add(gate());
            built.add(burner());
            built.add(canvas());
            built.add(sorter());
            Map<Item, Scene> table = new IdentityHashMap<>();
            for (Scene s : built) {
                // La première scène qui revendique un objet le garde : deux guides pour un même
                // objet voudraient dire deux réponses à une seule question, et le maintien de la
                // touche n'a pas de quoi en proposer un choix.
                s.subjects().forEach(item -> table.putIfAbsent(item, s));
            }
            scenes = List.copyOf(built);
            byItem = table;
            bound = level;
            complained = false;
        } catch (RuntimeException failure) {
            forget();
            // Une seconde avant de réessayer : la liaison des composants peut n'être pas tout à fait
            // finie à l'instant où le monde paraît, et réessayer à chaque image remplirait le journal
            // d'une exception par image pendant que le problème se résout tout seul.
            retryAt = System.currentTimeMillis() + 1000L;
            if (!complained) {
                complained = true;
                Lanterne.LOG.warn("Guide illustré indisponible pour l'instant : {}", failure.toString());
            }
        }
    }

    private static void forget() {
        bound = null;
        scenes = List.of();
        byItem = Map.of();
    }

    /** La scène qui répond à cet objet, ou {@code null} — le cas de l'immense majorité des objets. */
    public static Scene sceneFor(Item item) {
        all();
        return byItem.get(item);
    }

    // -------------------------------------------------------------------------

    /**
     * Le portail de repère, de la première pierre à la traversée.
     *
     * <p>La scène la plus longue des trois, et celle qui justifie tout le paquet : un portail de
     * repère se bâtit en quatre gestes dont trois sont invisibles à qui ne les connaît pas déjà.
     */
    private static Scene gate() {
        ItemStack floor = new ItemStack(Blocks.SMOOTH_STONE);
        ItemStack rock = new ItemStack(Blocks.OBSIDIAN);
        ItemStack heart = new ItemStack(Gates.HEART_ITEM.get());
        ItemStack steel = new ItemStack(Items.FLINT_AND_STEEL);
        ItemStack walker = new ItemStack(Items.PLAYER_HEAD);

        // Le cœur, et lui seul. L'obsidienne serait tentante — c'est par elle qu'on commence — mais
        // ajouter une ligne de Lanterne à l'infobulle d'un bloc de vanilla, c'est parler par-dessus
        // un objet qui ne nous appartient pas, chez tous les joueurs et pour tous les usages.
        Scene s = Scene.named("portail", heart.copy()).about(Gates.HEART_ITEM.get());
        // Les trois plans larges partagent le même cadrage. Ce n'est pas de la paresse : revenir
        // exactement au même plan après chaque gros plan est ce qui permet de ne pas se perdre.
        s.aim(0.5f, 1.5f, 0f, 2.2f);

        s.say("sol");
        s.raise(Scene.box(-2, -1, -1, 3, -1, 1), floor, 1);
        s.hold(12);

        // Deux de large sur trois de haut : le plus petit cadre que vanilla accepte, et celui que le
        // joueur a déjà bâti cent fois pour le Nether. Montrer le plus petit, c'est dire qu'il suffit.
        s.say("cadre");
        s.raise(Scene.ring(-1, 0, 2, 4, 0), rock, 3);
        s.hold(16);

        s.say("coeur");
        s.pan(0.2f, 0.3f, 0f, 3.4f, 24);
        s.hold(20);
        s.swap(0, 0, 0, heart);
        s.spark(0f, 0.2f, 0f, VIOLET);
        s.halo(0, 0, 0, AMBER, 96);
        s.tag(0f, 0.4f, 0f, "coeur.note", 96);
        s.hold(88);

        s.say("allumer");
        s.pan(0.5f, 1.5f, 0f, 2.2f, 26);
        s.hold(18);
        s.fly(steel, 11.4f, 9.5f, -0.5f, 0.4f, 0.3f, -0.5f, 28);
        s.spark(0f, 0.3f, 0f, AMBER);
        s.hold(6);
        // La nappe monte rangée par rangée : un portail qui paraîtrait d'un bloc ressemblerait à un
        // mur qu'on vient de poser, pas à quelque chose qui s'allume.
        for (int y = 1; y <= 3; y++) {
            s.sheet(0f, y, 0f, true, VIOLET);
            s.sheet(1f, y, 0f, true, VIOLET);
            s.hold(4);
        }
        s.hold(44);

        s.say("relier");
        s.pan(0.1f, 0.2f, 0f, 3.6f, 24);
        s.halo(0, 0, 0, GREEN, 80);
        s.tag(0f, 0.4f, 0f, "relier.note", 80);
        s.hold(80);

        s.say("traverser");
        s.pan(0.5f, 1.5f, 0f, 2.2f, 26);
        s.hold(12);
        s.fly(walker, 0.5f, 1.1f, 3.6f, 0.5f, 1.1f, 0.2f, 34);
        s.spark(0.5f, 1.4f, 0f, VIOLET);
        s.hold(26);

        return s.seal();
    }

    /**
     * Le graveur de disques.
     *
     * <p>Le geste que l'écran n'explique pas tout seul, c'est le premier : <b>le disque vierge entre
     * dans le bloc avant qu'aucune fenêtre ne s'ouvre</b>. On le montre donc, et on ne montre que ça
     * — la fenêtre où l'on colle un lien, elle, se comprend seule.
     */
    private static Scene burner() {
        ItemStack floor = new ItemStack(Blocks.POLISHED_ANDESITE);
        ItemStack machine = new ItemStack(Groove.BURNER_ITEM.get());
        ItemStack blank = new ItemStack(Groove.BLANK.get());
        ItemStack disc = new ItemStack(Groove.DISC.get());

        Scene s = Scene.named("graveur", machine.copy())
                .about(Groove.BURNER_ITEM.get(), Groove.BLANK.get(), Groove.DISC.get());
        s.aim(0f, -0.3f, 0f, 3.0f);

        s.say("poser");
        s.raise(Scene.box(-2, -1, -1, 2, -1, 1), floor, 1);
        s.hold(8);
        s.put(0, 0, 0, machine);
        s.hold(26);

        s.say("charger");
        s.pan(0f, 0.1f, 0f, 4.0f, 22);
        s.hold(14);
        s.fly(blank, 6.1f, 4.6f, -0.4f, 0.1f, 0.3f, -0.4f, 26);
        s.hold(18);

        s.say("graver");
        s.halo(0, 0, 0, AMBER, 130);
        s.tag(0f, 0.9f, 0f, "graver.note", 130);
        // Six secondes de travail, et six gerbes pour qu'on voie qu'il se passe quelque chose : c'est
        // exactement la durée que le bloc met, elle n'est pas arrondie pour l'animation.
        for (int i = 0; i < 6; i++) {
            s.spark(0f, 0.5f, 0f, AMBER);
            s.hold(20);
        }

        s.say("prendre");
        s.hold(10);
        s.fly(disc, 0.1f, 0.4f, -0.4f, -10.8f, -5.7f, -0.4f, 26);
        s.hold(24);

        return s.seal();
    }

    /**
     * Le tableau.
     *
     * <p>La toile n'a pas d'objet et n'est pas un bloc — c'est une entité. Elle est donc dessinée en
     * surfaces plates, ce qui se trouve être exactement ce qu'elle est : un rectangle sur un mur.
     */
    private static Scene canvas() {
        ItemStack floor = new ItemStack(Blocks.POLISHED_ANDESITE);
        ItemStack wall = new ItemStack(Blocks.STONE_BRICKS);
        ItemStack brush = new ItemStack(Easel.BRUSH.get());

        Scene s = Scene.named("tableau", brush.copy())
                .about(Easel.BRUSH.get());
        s.aim(0f, 1.2f, 0.5f, 2.2f);

        s.say("mur");
        s.raise(Scene.box(-2, -1, -1, 2, -1, 1), floor, 1);
        s.raise(Scene.box(-2, 0, 1, 2, 3, 1), wall, 1);
        s.hold(14);

        s.say("pinceau");
        s.pan(0f, 1.3f, 0.4f, 2.4f, 24);
        s.hold(12);
        s.fly(brush, 9.7f, 8.4f, 0.2f, 0.2f, 1.8f, 0.2f, 26);
        // La toile paraît case par case, de bas en haut : on voit son emprise se dessiner sur le mur,
        // ce qui est le seul retour qui compte à la pose.
        for (int y = 1; y <= 3; y++) {
            for (int x = -1; x <= 1; x++) {
                s.sheet(x, y, 0.5f, true, PAPER);
            }
            s.hold(4);
        }
        s.halo(0, 2, 1, AMBER, 90);
        s.tag(0f, 2.6f, 0.5f, "toile.note", 90);
        s.hold(86);

        s.say("image");
        s.pan(0f, 2.0f, 0.4f, 3.2f, 24);
        s.hold(10);
        // La toile se teinte : c'est l'image qui arrive. On ne peut pas montrer une vraie image —
        // elle vient du dossier du joueur — et prétendre le contraire serait inventer un décor.
        for (int y = 1; y <= 3; y++) {
            for (int x = -1; x <= 1; x++) {
                s.sheet(x, y, 0.5f, true, x < 0 ? GREEN : x == 0 ? AMBER : VIOLET);
            }
            s.hold(3);
        }
        s.hold(40);

        return s.seal();
    }

    /**
     * Le Trieur : un entonnoir qui filtre, sans jamais toucher au hopper vanilla.
     *
     * <p>Ce qu'il fallait montrer n'est pas « pose-le au-dessus d'un coffre » — un hopper vanilla le
     * dit déjà tout seul par sa forme — mais le geste propre à ce bloc : <b>glisser un objet dans une
     * case de filtre</b>, et ce que ça change au tri. Deux objets suffisent à le dire : un qui passe,
     * un qui reste, sur le même entonnoir, sans rien inventer sur son mécanisme.
     */
    private static Scene sorter() {
        ItemStack floor = new ItemStack(Blocks.POLISHED_ANDESITE);
        ItemStack chest = new ItemStack(Blocks.CHEST);
        ItemStack trieur = new ItemStack(Contents.TRIEUR_ITEM.get());
        ItemStack coal = new ItemStack(Items.COAL);
        ItemStack iron = new ItemStack(Items.IRON_INGOT);

        Scene s = Scene.named("trieur", trieur.copy()).about(Contents.TRIEUR_ITEM.get());
        s.aim(0f, 0.8f, 0f, 2.6f);

        s.say("poser");
        s.raise(Scene.box(-1, -1, -1, 1, -1, 1), floor, 1);
        s.hold(6);
        s.put(0, -1, 0, chest);
        s.put(0, 0, 0, trieur);
        s.hold(18);

        s.say("filtre");
        s.pan(0f, 0.6f, 0f, 1.8f, 20);
        s.halo(0, 0, 0, AMBER, 90);
        s.tag(0f, 0.4f, 0f, "filtre.note", 90);
        s.fly(coal, 5.4f, 4.2f, -0.3f, 0.2f, 0.2f, -0.2f, 26);
        s.spark(0f, 0.1f, 0f, AMBER);
        s.hold(64);

        s.say("passe");
        s.pan(0f, 0.4f, 0f, 2.2f, 20);
        s.fly(coal, -5.6f, 3.8f, 0.3f, 0f, 0.7f, 0f, 18);
        s.hold(6);
        s.halo(0, -1, 0, GREEN, 40);
        s.hold(40);

        s.say("reste");
        s.fly(iron, 5.6f, 3.8f, -0.3f, 0f, 0.7f, 0f, 18);
        s.hold(6);
        s.halo(0, 0, 0, VIOLET, 50);
        s.tag(0f, 0.4f, 0f, "reste.note", 50);
        s.hold(50);

        return s.seal();
    }
}
