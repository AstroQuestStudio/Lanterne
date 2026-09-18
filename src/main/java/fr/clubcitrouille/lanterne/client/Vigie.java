package fr.clubcitrouille.lanterne.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.jspecify.annotations.Nullable;

import com.mojang.renderpearl.api.device.BackendCreationException;
import com.mojang.renderpearl.api.device.DeviceInfo;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanBackend;
import com.mojang.blaze3d.systems.RenderSystem;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * La vigie : rend visible ce que vanilla décide en silence sur le choix Vulkan / OpenGL.
 *
 * <h2>Le repli qu'on ne voyait jamais</h2>
 *
 * <p>Trois sessions de suite, journalisées, ont montré la même séquence :
 * {@code VulkanBackend} repère la vraie carte dédiée (« Preferring discrete GPU: NVIDIA GeForce
 * RTX 3080 Laptop GPU »), puis {@code Minecraft.<init>} annonce « Using graphics backend OpenGL »
 * sur l'iGPU AMD — sans la moindre erreur, sans la moindre ligne d'avertissement entre les deux.
 *
 * <p>L'hypothèse de départ était le garde-fou vanilla autour de {@code lastStartWasClean}
 * (« Detected unexpected shutdown during last game startup: forcing preferred graphics API to
 * OpenGL », vérifié en {@code Minecraft.java} ~451-484) : un arrêt sale forcerait OpenGL. Elle ne
 * tenait pas — vérifiée contre les trois journaux exacts qui ont motivé cette classe : cette phrase
 * n'y apparaît JAMAIS, et {@code options.txt} y portait déjà {@code startedCleanly:true} avant le
 * lancement suivant. Le vrai mécanisme, lu directement dans
 * {@code net.minecraft.client.PreferredGraphicsApi#getBackendsToTry} :
 *
 * <pre>{@code
 * public GpuBackend[] getBackendsToTry() {
 *     GlBackend gl = new GlBackend();
 *     VulkanBackend vulkan = new VulkanBackend();
 *     return this == VULKAN ? new GpuBackend[]{vulkan, gl} : new GpuBackend[]{gl, vulkan};
 * }
 * }</pre>
 *
 * <p><b>{@code DEFAULT} essaie TOUJOURS OpenGL en premier</b>, arrêt précédent propre ou non. La
 * ligne « Preferring discrete GPU » vient d'un tout autre appel — {@code VulkanBackend.
 * checkBackendAvailable()}, une sonde jetable que {@code Minecraft.<init>} lance uniquement pour
 * peupler {@code backendCreationException} quand la préférence vaut {@code DEFAULT} — elle crée une
 * instance Vulkan temporaire, choisit un GPU, log ce choix, puis la referme aussitôt sans jamais
 * rendre une image. La boucle réelle de sélection, elle, tente {@code GlBackend} d'abord, réussit
 * presque toujours, et s'arrête là : {@code VulkanBackend.createDevice} n'est jamais atteint.
 *
 * <p>Le garde-fou {@code lastStartWasClean} existe bien et reste légitime — il protège
 * spécifiquement le joueur qui a choisi {@code "vulkan"} explicitement et dont la session a planté,
 * en le ramenant à {@code DEFAULT}. Mais il n'explique pas pourquoi une installation neuve, jamais
 * touchée, ne voit jamais Vulkan : ça, c'est l'ordre de {@code getBackendsToTry}, point final.
 *
 * <h2>Ce que cette classe fait — et ne fait pas</h2>
 *
 * <p>Elle ne supprime ni ne contourne le garde-fou vanilla : un vrai plantage lié au rendu doit
 * toujours pouvoir ramener un joueur vers OpenGL. Elle ne force jamais Vulkan à chaque lancement
 * sans condition. Elle fait deux choses, honnêtes et vérifiables :
 *
 * <ul>
 *   <li>{@link #capture()} lit {@code options.txt} tel qu'il existe juste avant que
 *       {@code Minecraft.<init>} ne le charge (même fenêtre temporelle que {@link Reveil}, voir sa
 *       Javadoc pour la preuve tirée du journal) — sans jamais le modifier, uniquement pour
 *       connaître l'état de départ réel de cette session.</li>
 *   <li>{@link #rapporte(ClientTickEvent.Post)}, une fois la fenêtre ouverte, compare ce qui était
 *       demandé à ce qui tourne réellement ({@code RenderSystem.getDevice().getDeviceInfo()}, la
 *       même source que les lignes « Using graphics backend/device » de vanilla) et journalise un
 *       avertissement {@code [LANTERNE]} explicite dès que les deux divergent — pour que ce repli
 *       ne redevienne plus jamais silencieux, quelle qu'en soit la cause exacte.</li>
 * </ul>
 *
 * <p>Le choix qui corrige effectivement le cas courant (installation neuve, {@code DEFAULT} qui
 * n'essaie jamais Vulkan) vit dans {@link Reveil} : au tout premier lancement, seulement si
 * {@code options.txt} n'existe pas encore, il pose {@code preferredGraphicsBackend:"vulkan"} — le
 * réglage explicite que {@code getBackendsToTry} fait passer en tête de liste, ce qui contourne
 * complètement cette histoire d'ordre par défaut. Un fichier déjà présent n'est, comme toujours,
 * jamais réécrit : voir la Javadoc de {@link Reveil} pour pourquoi.
 */
@EventBusSubscriber(modid = Lanterne.ID, value = Dist.CLIENT)
public final class Vigie {
    private Vigie() {}

    private static final String OPTIONS_FILE = "options.txt";

    /** Ce que {@code options.txt} portait pour {@code preferredGraphicsBackend}, juste avant que
     * {@code Minecraft.<init>} ne le charge et ne le mute éventuellement en mémoire. {@code null} si
     * la clé était absente (défaut vanilla : {@code DEFAULT}) ou le fichier introuvable. */
    private static @Nullable String preferenceAvantMinecraft;

    /** Ce que {@code options.txt} portait pour {@code startedCleanly} au même instant. {@code null}
     * si absent (défaut vanilla : {@code true}, voir {@code Options.startedCleanly = true}). */
    private static @Nullable Boolean demarragePrecedentPropre;

    private static final AtomicBoolean RAPPORTE = new AtomicBoolean(false);

    /**
     * À appeler une seule fois, tôt dans la construction du mod, côté client — juste après
     * {@link Reveil#install()} pour lire l'état final qu'il a éventuellement posé. Ne modifie jamais
     * le fichier ; une panne de lecture laisse simplement les deux champs à {@code null}, et
     * {@link #rapporte} s'en accommode en traitant l'inconnu comme le défaut vanilla.
     */
    public static void capture() {
        Path options = FMLPaths.GAMEDIR.get().resolve(OPTIONS_FILE);
        if (!Files.exists(options)) {
            return;
        }
        try {
            List<String> lignes = Files.readAllLines(options, StandardCharsets.UTF_8);
            for (String ligne : lignes) {
                if (ligne.startsWith("preferredGraphicsBackend:")) {
                    preferenceAvantMinecraft = ligne.substring("preferredGraphicsBackend:".length())
                            .trim().replace("\"", "");
                } else if (ligne.startsWith("startedCleanly:")) {
                    demarragePrecedentPropre = Boolean.parseBoolean(
                            ligne.substring("startedCleanly:".length()).trim());
                }
            }
        } catch (IOException problem) {
            Lanterne.LOG.debug("[VIGIE] Lecture de {} impossible avant Minecraft.<init> — "
                    + "diagnostic Vulkan/OpenGL désactivé pour cette session.", options, problem);
        }
    }

    /**
     * Premier tick client, une seule fois ({@link #RAPPORTE} garde contre les rappels suivants) : à
     * ce stade {@code Minecraft.<init>} a fini, la fenêtre et le backend existent, et
     * {@code RenderSystem.getDevice()} répond.
     */
    @SubscribeEvent
    static void rapporte(ClientTickEvent.Post event) {
        if (!RAPPORTE.compareAndSet(false, true)) {
            return;
        }
        GpuDevice device = RenderSystem.tryGetDevice();
        if (device == null) {
            return; // Rien à comparer — ne devrait pas arriver si le jeu a fini de démarrer.
        }
        DeviceInfo info = device.getDeviceInfo();
        boolean actifEstVulkan = "Vulkan".equalsIgnoreCase(info.backendName());

        boolean preferenceEtaitVulkan = "vulkan".equals(preferenceAvantMinecraft);
        boolean preferenceEtaitDefault = preferenceAvantMinecraft == null
                || "default".equals(preferenceAvantMinecraft);
        boolean demarragePrecedentInconnuOuPropre = demarragePrecedentPropre == null
                || demarragePrecedentPropre;

        if (actifEstVulkan) {
            Lanterne.LOG.info("[LANTERNE][GPU] Vulkan actif et confirmé : {} sur {} ({}). "
                    + "Extensions du pilote : {}", info.backendName(), info.name(), info.vendorName(),
                    String.join(", ", info.underlyingExtensions()));
            return;
        }

        if (preferenceEtaitVulkan && !demarragePrecedentInconnuOuPropre) {
            Lanterne.LOG.warn("[LANTERNE][GPU] preferredGraphicsBackend valait \"vulkan\" dans {}, "
                    + "mais le backend actif est {} sur {} ({}) : le lancement précédent a été "
                    + "détecté comme non propre (startedCleanly:false), et le garde-fou vanilla a "
                    + "donc rétrogradé la préférence vers DEFAULT pour cette session — voir "
                    + "Minecraft.<init> ~469-478. Ce n'est pas persisté : le prochain lancement "
                    + "retentera Vulkan si {} est remis à \"vulkan\".",
                    OPTIONS_FILE, info.backendName(), info.name(), info.vendorName(), OPTIONS_FILE);
            return;
        }

        if (preferenceEtaitVulkan) {
            BackendCreationException echec = VulkanBackend.checkBackendAvailable();
            Lanterne.LOG.warn("[LANTERNE][GPU] preferredGraphicsBackend valait \"vulkan\" dans {}, "
                    + "le dernier arrêt était propre, et pourtant le backend actif est {} sur {} "
                    + "({}) — la création du backend Vulkan a probablement échoué silencieusement "
                    + "pour cette session (voir 'Failed to create backend Vulkan' juste au-dessus "
                    + "dans ce journal). Sonde de disponibilité : {}.",
                    OPTIONS_FILE, info.backendName(), info.name(), info.vendorName(),
                    echec == null ? "Vulkan semble pourtant disponible maintenant" : echec.getMessage());
            return;
        }

        if (preferenceEtaitDefault) {
            BackendCreationException indispo = VulkanBackend.checkBackendAvailable();
            if (indispo == null) {
                Lanterne.LOG.warn("[LANTERNE][GPU] Backend actif : {} sur {} ({}). Un GPU Vulkan "
                        + "capable a été détecté sur cette machine, mais n'est PAS utilisé : "
                        + "preferredGraphicsBackend=\"default\" essaie TOUJOURS OpenGL en premier "
                        + "sur ce moteur (PreferredGraphicsApi.getBackendsToTry met GlBackend avant "
                        + "VulkanBackend pour toute valeur autre que \"vulkan\"), qu'un précédent "
                        + "lancement ait planté ou non. Pour utiliser réellement Vulkan, règle "
                        + "preferredGraphicsBackend:\"vulkan\" explicitement dans {} (Réglages vidéo, "
                        + "ou premier lancement : voir fr.clubcitrouille.lanterne.client.Reveil).",
                        info.backendName(), info.name(), info.vendorName(), OPTIONS_FILE);
            } else {
                Lanterne.LOG.info("[LANTERNE][GPU] Backend actif : {} sur {} ({}) — Vulkan n'a pas "
                        + "été tenté en premier (préférence \"default\") et ne serait de toute façon "
                        + "pas disponible sur cette machine actuellement : {}.",
                        info.backendName(), info.name(), info.vendorName(), indispo.getMessage());
            }
        }
    }
}
