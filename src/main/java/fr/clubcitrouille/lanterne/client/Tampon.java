package fr.clubcitrouille.lanterne.client;

import java.util.Locale;

import com.mojang.blaze3d.opengl.GlStateManager;

import fr.clubcitrouille.lanterne.Lanterne;
import fr.clubcitrouille.lanterne.core.Settings;

/**
 * Le tampon : immuable chez Mojang, mutable chez NVIDIA.
 *
 * <h2>Le défaut, en une phrase</h2>
 *
 * <p>{@code GL_ARB_buffer_storage} alloue un tampon <b>immuable</b> : sa taille et son emplacement
 * ne bougent plus. Quand le jeu réécrit dedans alors que la carte s'en sert encore, le pilote n'a
 * pas le droit de lui donner une autre zone mémoire — il n'a que le choix d'<b>attendre</b>. Sur
 * pilote NVIDIA et sur les Intel de septième génération, cette attente se voit : l'image se pose,
 * puis saute.
 *
 * <p>Avec un tampon mutable, le pilote fait de l'<em>orphaning</em> — il abandonne l'ancienne zone,
 * en prend une neuve, et la carte finit tranquillement ce qu'elle faisait.
 *
 * <h2>Ce n'est pas une idée de ce laboratoire : c'est un correctif de Mojang</h2>
 *
 * <p>Mojang a fait exactement cette correction en 26.3 — {@code GlHeuristics} y gagne
 * {@code isNvidia()} et {@code couldBeIntelGen7()}. La 26.2 ne l'a pas : sa classe
 * {@code com.mojang.blaze3d.opengl.GlHeuristics}, lue en entier, n'a que {@code isGlOnDx12()} et
 * {@code isAmd()}. Et l'interrupteur existe déjà dans le jeu :
 *
 * <pre>
 * GlDevice.java, ligne 55 :      protected static boolean USE_GL_ARB_buffer_storage = true;
 * BufferStorage.java, ligne 13 : if (capabilities.GL_ARB_buffer_storage &amp;&amp; GlDevice.USE_GL_ARB_buffer_storage)
 * </pre>
 *
 * <p>Ce module est donc un <b>rétroportage</b>, pas une trouvaille. C'est ce qui justifie de le
 * livrer allumé malgré la règle ci-dessous.
 *
 * <h2>Et il n'est PAS mesuré — il faut le dire en premier, pas en note de bas de page</h2>
 *
 * <p>La règle de ce dépôt est qu'un module non mesuré n'existe pas. Celui-ci est la seule exception,
 * et elle est assumée pour deux raisons qu'aucun effort ne lève :
 *
 * <ul>
 *   <li>l'effet ne se voit que sur un <b>pilote graphique particulier</b>, et seulement en cadence
 *       d'images — il n'y a rien à chronométrer sur un serveur ;</li>
 *   <li>ce laboratoire ne lance pas de client, par consigne.</li>
 * </ul>
 *
 * <p>Ce qui est vérifié, en revanche, et qui n'est pas rien : la classe visée existe, le champ visé
 * existe, la 26.2 n'a pas le correctif, et la 26.3 l'a. Le module journalise sa décision au
 * démarrage — c'est le seul relevé qu'on puisse en tirer ici, et il dit au moins si la reconnaissance
 * de pilote tombe juste sur la machine du joueur.
 *
 * <p><b>Sans effet sous Vulkan</b> : tout {@code com.mojang.blaze3d.opengl.*} est inerte quand le
 * joueur choisit le moteur Vulkan de la 26.2.
 */
public final class Tampon {
    private static Boolean decided;
    private static String why = "pas encore décidé";

    private Tampon() {}

    /**
     * Faut-il forcer le tampon mutable ?
     *
     * <p>Décidé une seule fois, et tard : {@code GlStateManager._getString} demande à la carte
     * graphique, ce qui exige un contexte OpenGL. L'appeler au chargement du mod planterait.
     */
    public static boolean forceMutable() {
        if (!Settings.tampon()) {
            return false;
        }
        if (decided == null) {
            decided = decide();
            Lanterne.LOG.info("[TAMPON] {} — {}",
                    decided ? "tampon mutable forcé" : "tampon immuable conservé (vanilla)", why);
        }
        return decided;
    }

    private static boolean decide() {
        String renderer = GlStateManager._getString(7937).toLowerCase(Locale.ROOT);
        String vendor = GlStateManager._getString(7936).toLowerCase(Locale.ROOT);
        if (renderer.contains("nvidia") || vendor.contains("nvidia")) {
            why = "pilote NVIDIA (« " + renderer + " ») : le stockage immuable y bloque à l'écriture";
            return true;
        }
        if (intelGen7(renderer, vendor)) {
            why = "Intel de septième génération (« " + renderer + " ») : même symptôme";
            return true;
        }
        why = "pilote « " + renderer + " » non concerné par MC-307596";
        return false;
    }

    /**
     * La reconnaissance des Intel de septième génération.
     *
     * <p>Reprise telle quelle de la 26.3 : il n'y a pas de numéro de génération dans les chaînes
     * OpenGL, seulement des noms commerciaux. « HD Graphics 2500 », « HD Graphics 4000 »,
     * « HD Graphics (BYT) » et le « HD Graphics » nu sont les quatre formes que prend cette famille.
     */
    private static boolean intelGen7(String renderer, String vendor) {
        if (!vendor.contains("intel") && !renderer.contains("intel")) {
            return false;
        }
        return renderer.contains("2500")
                || renderer.contains("4000")
                || renderer.contains("hd graphics (byt)")
                || renderer.endsWith("hd graphics");
    }
}
