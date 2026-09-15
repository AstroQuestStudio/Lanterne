package fr.clubcitrouille.lanterne.client.upscale;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.DeviceInfo;
import com.mojang.blaze3d.systems.RenderSystem;

import net.minecraft.client.Minecraft;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * DLSS : le relevé de ce qui manque, et l'endroit exact où le brancher.
 *
 * <h2>Pourquoi ce fichier existe alors qu'il ne fait rien</h2>
 *
 * <p>Ce module <b>ne fait pas de DLSS</b>. Il dit pourquoi, il le dit au joueur autant qu'au
 * journal, et il tient l'échelon de repli. Ce n'est pas une politesse : un mod qui affiche « DLSS »
 * sans rien faire de particulier est indiscernable, à l'œil, d'un mod qui en fait — et c'est
 * précisément ce qui rend invérifiables les trois mods propriétaires qui prétendent l'apporter à
 * Minecraft. Ce projet ne peut pas les critiquer sur ce point puis faire pareil.
 *
 * <h2>Ce qui est établi, et qui a été lu dans les sources de 26.2</h2>
 *
 * <p>Trois obstacles qu'on croyait insurmontables ne le sont pas :
 *
 * <ul>
 *   <li><b>La réflexion n'est plus nécessaire.</b> Le mod de référence sur ce sujet vole le
 *       {@code VkInstance} par réflexion sur {@code VulkanDevice}. En 26.2, {@code VulkanDevice}
 *       est public, {@code instance()} et {@code vkDevice()} aussi, et {@code VulkanInstance}
 *       expose {@code vkInstance()}. Il ne reste qu'un champ privé sur le chemin —
 *       {@code GpuDevice.backend} — qui demande un accesseur de mixin, et rien de plus.</li>
 *   <li><b>Le backend actif se lit sans rien forcer</b> : {@code DeviceInfo.backendName()} vaut
 *       {@code "Vulkan"} ou {@code "OpenGL"}, et {@code vendorName()}, {@code name()} et
 *       {@code type()} donnent de quoi reconnaître une carte. C'est ce que fait {@link #probe()}.</li>
 *   <li><b>Le décalage de projection a une place nette.</b> {@code GameRenderer.renderLevel} fait
 *       une copie locale de la matrice de projection, la modifie déjà pour le tangage et le vertige,
 *       puis appelle {@code ProjectionMatrixBuffer.getBuffer(Matrix4f)} — une surcharge qui, à la
 *       différence de {@code getBuffer(Projection)}, <b>n'a aucun cache de version</b> et réécrit le
 *       tampon à chaque appel. Un décalage d'un sous-pixel par image s'y insère sans rien casser.</li>
 * </ul>
 *
 * <h2>Ce qui manque, et qu'aucune astuce ne remplace</h2>
 *
 * <ol>
 *   <li><b>Le pont natif.</b> DLSS est une bibliothèque C++ (NGX). L'appeler depuis Java demande un
 *       pont JNI compilé contre le SDK de NVIDIA, avec CMake et une chaîne C++, pour Windows et
 *       Linux. <b>Ce pont n'existe pas dans ce dépôt et n'a pas été écrit</b> : il ne peut pas
 *       l'être sans le SDK ni sans pouvoir l'exécuter. C'est le seul obstacle vraiment bloquant,
 *       et il est matériel, pas de conception.</li>
 *   <li><b>{@code nvngx_dlss.dll} ne peut pas être embarqué dans l'archive.</b> Sa licence
 *       l'interdit, et la GPL-3.0 de ce mod l'interdirait de toute façon. La seule voie propre est
 *       le téléchargement à l'exécution, <b>sur consentement explicite</b> du joueur, avec
 *       vérification d'empreinte avant chargement. Le chemin attendu est
 *       {@code config/lanterne/nvngx_dlss.dll} ; {@link #libraryPresent()} se contente de regarder
 *       s'il est là. Le téléchargement n'est pas écrit tant que rien ne consomme le fichier :
 *       un téléchargeur sans utilisateur, c'est une surface réseau pour zéro fonction.</li>
 *   <li><b>Les vecteurs de mouvement.</b> Un remonteur temporel en exige. Minecraft n'en produit
 *       aucun, et le tampon de profondeur — samplable en {@code D32_FLOAT}, en Z <em>inversé</em>
 *       depuis 26.2 — est effacé juste après le monde, dans {@code GameRenderer.render}, avant que
 *       l'interface ne soit peinte. Les reconstruire depuis la profondeur donne des vecteurs
 *       corrects pour la <b>caméra</b> et faux pour tout ce qui bouge par soi-même : créatures,
 *       particules, feuillage animé y laissent une traînée. C'est un défaut connu de la méthode,
 *       pas un défaut d'implémentation, et il doit être annoncé avant d'être découvert.</li>
 *   <li><b>Le décalage de projection.</b> Sans lui, un remonteur temporel reçoit la même
 *       information à chaque image et n'a rien à accumuler : il rend du flou, pas du net. Il n'est
 *       donc pas écrit non plus, parce qu'il <b>dégraderait</b> l'image tant qu'aucun remonteur
 *       temporel ne l'exploite — un tremblement d'un demi-pixel, visible et sans contrepartie.</li>
 * </ol>
 *
 * <h2>Les trois échelons, et pourquoi le dernier est le rendu de vanilla</h2>
 *
 * <p>DLSS, puis FSR 1.0, puis rien. Chaque échelon vérifie ses conditions <b>avant</b> qu'on se soit
 * engagé à dessiner le monde ailleurs que là où il sera présenté. Un joueur qui n'a pas de carte
 * RTX, ou qui est resté sur OpenGL — c'est-à-dire l'immense majorité, puisque Vulkan est marqué
 * « expérimental » en 26.2 — obtient FSR 1.0 sans s'en apercevoir, et jamais un écran noir.
 */
public final class Deep {
    /** Le nom sous lequel la bibliothèque de NVIDIA est cherchée, si elle est un jour utilisée. */
    private static final String LIBRARY = "nvngx_dlss.dll";

    /** Ce qui empêche DLSS de tourner, dans l'ordre où la question se pose. */
    public enum Verdict {
        /** Pas encore relevé. */
        INCONNU("non relevé"),
        /** Le jeu tourne sur OpenGL. DLSS exige un contexte Vulkan. */
        SANS_VULKAN("OpenGL — DLSS exige Vulkan"),
        /** Pas de carte NVIDIA compatible. */
        SANS_RTX("carte non compatible"),
        /** Carte et backend corrects, bibliothèque absente du dossier de configuration. */
        SANS_BIBLIOTHEQUE("bibliothèque absente"),
        /** Tout est réuni côté machine, et le pont natif n'existe pas dans ce mod. */
        SANS_PONT("pont natif non écrit");

        private final String label;

        Verdict(String label) {
            this.label = label;
        }

        public String label() {
            return this.label;
        }
    }

    private static Verdict verdict = Verdict.INCONNU;
    private static boolean announced;

    private Deep() {}

    /**
     * DLSS peut-il servir à cette image ?
     *
     * <p>Rend {@code false} en toutes circonstances tant que le pont natif n'existe pas. Ce n'est
     * pas un bouchon : c'est la seule réponse vraie, et c'est elle qui fait tomber l'appelant sur
     * FSR 1.0.
     */
    public static boolean available() {
        return false;
    }

    /**
     * Le point de branchement.
     *
     * <p>Le jour où le pont natif existe, c'est ici que la toile part vers NGX et que le résultat
     * revient dans la cible principale — la même signature que {@code Resolve.run}, parce que c'est
     * le même contrat : « le monde est dans la première, mets-le dans la seconde ».
     *
     * @return vrai si la cible principale contient bien l'image du monde
     */
    @SuppressWarnings("unused")
    public static boolean resolve(RenderTarget scene, RenderTarget screen) {
        return false;
    }

    /**
     * Relève l'état de la machine et l'écrit une fois dans le journal.
     *
     * <p>Appelée à la première image où la mise à l'échelle s'arme. Une seule fois : répéter la même
     * ligne soixante fois par seconde est la meilleure façon de la rendre illisible, et ce relevé ne
     * change pas en cours de partie — changer de backend graphique demande un redémarrage du jeu.
     */
    public static Verdict probe() {
        if (verdict != Verdict.INCONNU) {
            return verdict;
        }
        try {
            DeviceInfo info = RenderSystem.getDevice().getDeviceInfo();
            if (!"Vulkan".equalsIgnoreCase(info.backendName())) {
                verdict = Verdict.SANS_VULKAN;
            } else if (!looksLikeRtx(info)) {
                verdict = Verdict.SANS_RTX;
            } else if (!libraryPresent()) {
                verdict = Verdict.SANS_BIBLIOTHEQUE;
            } else {
                verdict = Verdict.SANS_PONT;
            }
            announce(info);
        } catch (Throwable problem) {
            // Un relevé qui plante ne doit pas emporter l'image. On conclut au pire des cas, qui
            // est aussi le plus fréquent, et on continue.
            verdict = Verdict.SANS_VULKAN;
            Lanterne.LOG.debug("[ÉCHELLE] Relevé de la carte graphique impossible.", problem);
        }
        return verdict;
    }

    public static Verdict verdict() {
        return verdict;
    }

    /** Une ligne pour l'écran de réglages, qui doit dire la vérité et non « bientôt ». */
    public static String describe() {
        return switch (verdict) {
            case INCONNU -> "non relevé";
            case SANS_VULKAN -> "OpenGL";
            case SANS_RTX -> "carte non RTX";
            case SANS_BIBLIOTHEQUE -> "DLL absente";
            case SANS_PONT -> "pont manquant";
        };
    }

    /**
     * La carte accepte-t-elle DLSS ?
     *
     * <p>Le test porte sur le nom rapporté par le pilote, faute de mieux : ni Vulkan ni OpenGL ne
     * dit « je sais faire tourner NGX ». La famille GTX 16xx, qui accepte DLSS sur certains titres
     * sans cœurs tenseurs, n'est <b>pas</b> reconnue ici — le relevé est volontairement strict,
     * parce qu'un faux positif conduirait à charger une bibliothèque qui échouerait plus tard, à un
     * endroit où l'échec coûte une image.
     */
    private static boolean looksLikeRtx(DeviceInfo info) {
        String vendor = info.vendorName() == null ? "" : info.vendorName().toUpperCase(Locale.ROOT);
        String name = info.name() == null ? "" : info.name().toUpperCase(Locale.ROOT);
        return (vendor.contains("NVIDIA") || name.contains("NVIDIA")) && name.contains("RTX");
    }

    /**
     * La bibliothèque de NVIDIA est-elle déposée par le joueur ?
     *
     * <p>Jamais téléchargée sans qu'on le lui demande, jamais embarquée dans l'archive. Voir
     * l'en-tête de cette classe pour ce que la licence autorise et ce qu'elle interdit.
     */
    public static boolean libraryPresent() {
        try {
            Path path = Minecraft.getInstance().gameDirectory.toPath()
                    .resolve("config").resolve(Lanterne.ID).resolve(LIBRARY);
            return Files.isRegularFile(path);
        } catch (Throwable problem) {
            return false;
        }
    }

    private static void announce(DeviceInfo info) {
        if (announced) {
            return;
        }
        announced = true;
        Lanterne.LOG.info("[ÉCHELLE] DLSS indisponible — {}. Backend {}, carte « {} ». "
                + "La mise à l'échelle se fait par FSR 1.0, qui n'exige ni Vulkan ni carte NVIDIA.",
                verdict.label(), info.backendName(), info.name());
    }
}
