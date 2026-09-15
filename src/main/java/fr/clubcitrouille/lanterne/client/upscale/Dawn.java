package fr.clubcitrouille.lanterne.client.upscale;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.minecraft.client.Minecraft;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * L'écran de chargement de NeoForge — et pourquoi il empêche Vulkan de démarrer.
 *
 * <h2>Le défaut, et il n'est pas là où on l'a cherché</h2>
 *
 * <p>Un joueur bascule sur Vulkan, relance, et le jeu refuse de s'ouvrir sur :
 *
 * <pre>GLFW error 65540: Vulkan: Window surface creation requires the window
 *                   to have the client API set to GLFW_NO_API</pre>
 *
 * <p>Le journal montrait aussi une couche Vulkan d'Overwolf cassée, et c'était la piste évidente.
 * <b>C'était la mauvaise.</b> Éprouvé hors du jeu — voir {@code tools/EssaiVulkan.java} :
 * {@code vkCreateInstance} rend {@code VK_SUCCESS} avec les onze couches de cette machine, couche
 * Overwolf cassée comprise. Le chargeur Vulkan écrit un avertissement, saute la couche, et
 * continue. Elle n'a jamais été la cause.
 *
 * <h2>La vraie cause, lue dans les sources</h2>
 *
 * <p>{@code Window.createGlfwWindow}, en 26.2, contient un correctif de NeoForge :
 *
 * <pre>backend.setWindowHints();                        // Vulkan y pose GLFW_NO_API
 * var earlyLoadingScreen = EarlyLoadingScreenController.current();
 * if (earlyLoadingScreen != null) {
 *     windowHandle = earlyLoadingScreen.takeOverGlfwWindow();   // ... et on ne crée rien
 * } else
 *     windowHandle = GLFW.glfwCreateWindow(...);                // ... les indices servent ici
 * </pre>
 *
 * <p>Les indices de fenêtre ne s'appliquent qu'à {@code glfwCreateWindow}. Quand l'écran de
 * chargement est actif, cet appel <b>n'a pas lieu</b> : NeoForge cède la fenêtre qu'il avait déjà
 * créée — et il l'a créée en {@code GLFW_OPENGL_API}, ce que confirme le désassemblage de
 * {@code DisplayWindow} ({@code glfwWindowHint(139265, 196609)}, juste avant son
 * {@code glfwCreateWindow}). Minecraft hérite donc d'une fenêtre <b>OpenGL</b>, puis demande à
 * GLFW une surface Vulkan dessus. GLFW refuse, et c'est le message ci-dessus.
 *
 * <p>Autrement dit : <b>sur NeoForge, avec les réglages par défaut, Vulkan ne peut pas
 * fonctionner</b>. Ce n'est ni la carte du joueur, ni son pilote, ni un logiciel tiers.
 *
 * <h2>Le remède, et pourquoi c'est une ligne de texte</h2>
 *
 * <p>{@code ImmediateWindowHandler} interroge {@code FMLConfig.EARLY_WINDOW_CONTROL} et, si elle
 * est fausse, laisse son fournisseur nul — {@code EarlyLoadingScreenController.current()} rend
 * alors {@code null}, la branche {@code else} s'exécute, et {@code glfwCreateWindow} reçoit enfin
 * les indices que le backend avait posés.
 *
 * <p>Il suffit donc d'écrire {@code earlyWindowControl = false} dans {@code config/fml.toml}. Pas
 * de code natif, pas de variable d'environnement, pas de couche à désactiver, pas de mixin : une
 * ligne dans un fichier de configuration, exactement ce qu'un joueur averti ferait à la main. Cela
 * ne peut rien faire planter, se relit d'un coup d'œil, et se défait aussi facilement.
 *
 * <h2>Ce que ça coûte, et qui doit être dit</h2>
 *
 * <p>Le joueur <b>perd la fenêtre de progression</b> pendant le chargement des mods. Sur une
 * grosse instance, cela veut dire plusieurs secondes — parfois beaucoup — pendant lesquelles
 * <b>rien n'apparaît à l'écran</b> et où l'on peut croire que le jeu n'a pas démarré. C'est un
 * vrai recul d'expérience, et c'est le prix de Vulkan sur NeoForge aujourd'hui. Il est écrit dans
 * l'écran de réglages plutôt que découvert au lancement suivant.
 *
 * <h2>Le jour où NeoForge corrigera</h2>
 *
 * <p>Ce défaut leur appartient et sera sans doute réparé — il leur suffit de recréer la fenêtre,
 * ou de poser les bons indices avant de la céder. Ce module ne gênera pas : il n'écrit que sur
 * demande, ne lit que ce qu'il a écrit, et une valeur déjà correcte ne déclenche aucune écriture.
 */
public final class Dawn {
    /**
     * La clé de FML, et son orthographe exacte.
     *
     * <p>Le motif tolère les espaces autour du signe égal parce que le fichier est écrit par
     * night-config aujourd'hui et pourrait l'être à la main demain — par le joueur lui-même, à qui
     * l'on vient justement d'expliquer où regarder.
     */
    private static final Pattern LINE =
            Pattern.compile("^(\\s*earlyWindowControl\\s*=\\s*)(true|false)(\\s*)$");

    /** Ce que le fichier dit, ou ne dit pas. */
    public enum State {
        /** Fichier illisible, absent, ou clé introuvable. */
        INCONNU,
        /** L'écran de chargement est actif — donc Vulkan ne peut pas s'ouvrir. */
        ACTIF,
        /** L'écran de chargement est coupé — la voie est libre pour Vulkan. */
        COUPE
    }

    private Dawn() {}

    /** Le fichier de FML, dans le dossier de jeu. */
    public static Path file() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve("fml.toml");
    }

    /**
     * Que dit le fichier ?
     *
     * <p>Relu à chaque interrogation plutôt que mis en cache : le joueur peut l'avoir édité à la
     * main entre deux ouvertures de l'écran, et un cadran qui afficherait une valeur périmée serait
     * pire qu'un cadran absent.
     */
    public static State state() {
        try {
            Path path = file();
            if (!Files.isRegularFile(path)) {
                return State.INCONNU;
            }
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                Matcher match = LINE.matcher(line);
                if (match.matches()) {
                    return "true".equals(match.group(2)) ? State.ACTIF : State.COUPE;
                }
            }
            return State.INCONNU;
        } catch (Throwable problem) {
            Lanterne.LOG.debug("[ÉCHELLE] Lecture de config/fml.toml impossible.", problem);
            return State.INCONNU;
        }
    }

    /**
     * Écrit la valeur voulue, si et seulement si elle diffère.
     *
     * <h2>Pourquoi on réécrit une ligne et non le fichier</h2>
     *
     * <p>{@code fml.toml} porte un commentaire au-dessus de chaque clé, et d'autres réglages que
     * celui-ci. Le relire avec une bibliothèque TOML puis le réenregistrer le reformaterait —
     * commentaires compris, sur une version de night-config qu'on ne choisit pas. On substitue donc
     * <b>une ligne</b> et on recopie le reste à l'identique : le joueur qui rouvre son fichier y
     * retrouve ce qu'il y avait mis, à un mot près.
     *
     * <p>Rien n'est ajouté si la clé est absente : un fichier où FML n'a pas écrit sa clé est un
     * fichier qu'on ne comprend pas, et écrire dedans à l'aveugle serait le meilleur moyen
     * d'empêcher le jeu de démarrer — c'est-à-dire exactement ce qu'on essaie de réparer.
     *
     * @return vrai si le fichier porte désormais la valeur voulue
     */
    public static boolean set(boolean wanted) {
        State was = state();
        if (was == State.INCONNU) {
            Lanterne.LOG.warn("[ÉCHELLE] config/fml.toml : clé « earlyWindowControl » introuvable, "
                    + "rien n'a été écrit. Vulkan risque d'échouer au prochain lancement.");
            return false;
        }
        if ((was == State.ACTIF) != wanted) {
            return true;
        }
        try {
            Path path = file();
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            boolean touched = false;
            for (int i = 0; i < lines.size(); i++) {
                Matcher match = LINE.matcher(lines.get(i));
                if (match.matches()) {
                    lines.set(i, match.group(1) + wanted + match.group(3));
                    touched = true;
                    break;
                }
            }
            if (!touched) {
                return false;
            }
            Files.write(path, lines, StandardCharsets.UTF_8);
            Lanterne.LOG.info("[ÉCHELLE] config/fml.toml : earlyWindowControl = {}. {}",
                    wanted,
                    wanted ? "L'écran de chargement de NeoForge revient au prochain lancement."
                            : "L'écran de chargement de NeoForge est coupé — sans quoi il cède à "
                                    + "Minecraft une fenêtre OpenGL sur laquelle Vulkan ne peut pas "
                                    + "ouvrir de surface.");
            return true;
        } catch (Throwable problem) {
            // Un fichier en lecture seule, un dossier verrouillé, un antivirus : autant de raisons
            // de ne pas écrire, et aucune de faire tomber l'écran de réglages.
            Lanterne.LOG.warn("[ÉCHELLE] config/fml.toml n'a pas pu être écrit.", problem);
            return false;
        }
    }
}
