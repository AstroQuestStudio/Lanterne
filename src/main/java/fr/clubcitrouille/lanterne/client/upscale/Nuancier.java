package fr.clubcitrouille.lanterne.client.upscale;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.AddPackFindersEvent;

import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * Des nuanciers de shaders déposés à la main, chargés comme un pack de ressources.
 *
 * <h2>Pourquoi ce n'est pas Iris, et pourquoi ça n'a pas besoin de l'être</h2>
 *
 * <p>Iris ne fonctionne pas sous ce backend : il s'accroche à des extensions OpenGL absentes en
 * Vulkan, et le rendu de cette version passe par {@code com.mojang.renderpearl}, un pipeline
 * entièrement différent (vérifié par lecture du vrai jar client, pas des sources décompilées
 * périmées). Réécrire Iris est hors de portée — mais le vrai besoin du joueur, « déposer un fichier
 * de shader et le voir marcher sans recompiler le mod », ne demande pas de réécrire Iris : il
 * demande un point d'entrée dans le système de ressources du jeu, et vanilla en fournit déjà un.
 *
 * <h2>Le mécanisme : un pack de ressources de plus, pas un chargeur maison</h2>
 *
 * <p>{@link Resolve#chain()} charge ses chaînes via
 * {@code Minecraft.getInstance().getShaderManager().getPostChain(id, ...)}, qui résout {@code id}
 * à travers le {@code ResourceManager} du jeu — la même pile qui lit les packs de ressources et de
 * données. {@link PathPackResources} sait exposer un dossier du disque comme un pack ordinaire ; il
 * suffit de l'enregistrer via {@link AddPackFindersEvent}, l'extension NeoForge prévue pour ça,
 * pour que tout fichier sous {@code config/lanterne/shaderpacks/<nom>/assets/lanterne/...} devienne
 * une ressource comme une autre — y compris pour {@code lanterne:post/xxx}, le même identifiant que
 * FSR et l'anticrénelage utilisent déjà.
 *
 * <p>Aucune mixin : {@code AddPackFindersEvent} est un point d'extension public de NeoForge, pas un
 * détail interne à intercepter.
 *
 * <h2>Format attendu d'un nuancier déposé</h2>
 *
 * <p>Un sous-dossier de {@code config/lanterne/shaderpacks/} avec :
 * <ul>
 *   <li>{@code pack.mcmeta} — obligatoire, {@code Pack.readMetaAndCreate} refuse sans lui. Un
 *       gabarit minimal est écrit automatiquement dans le dossier de démonstration livré avec ce
 *       module ({@code exemple_teinte/}) ; copier ce fichier suffit pour un nouveau nuancier.</li>
 *   <li>{@code assets/lanterne/post/<nom>.json} — la chaîne de post-traitement, même format que
 *       {@code lanterne:post/upscale_aucune} déjà dans le jar (voir {@link Upscale}).</li>
 *   <li>{@code assets/lanterne/shaders/post/<nom>.fsh} (et {@code .vsh} si besoin) — le GLSL.
 *       <b>Chaque variable {@code in}/{@code out} du fragment shader doit porter
 *       {@code layout(location = N)}</b> : ce pipeline compile en SPIR-V, qui l'exige, contrairement
 *       à OpenGL classique où c'était optionnel. Un fichier qui l'omet reproduit exactement le bug
 *       corrigé cette session sur {@code fsr_easu.fsh}/{@code fsr_rcas.fsh}/{@code aa_edge.fsh} —
 *       {@link #verifierAvertissement} détecte ce cas précis et journalise un message explicite
 *       plutôt que de laisser {@code ShaderManager} échouer avec une pile d'appels opaque.</li>
 * </ul>
 */
@EventBusSubscriber(modid = Lanterne.ID, value = Dist.CLIENT)
public final class Nuancier {
    private Nuancier() {}

    private static Path dossier() {
        return Path.of("").toAbsolutePath()
                .resolve("config").resolve("lanterne").resolve("shaderpacks");
    }

    /**
     * Enregistre chaque sous-dossier de {@code shaderpacks/} comme pack de ressources séparé.
     *
     * <p>Plusieurs nuanciers peuvent cohabiter — celui qui définit effectivement
     * {@code lanterne:post/<chemin demandé par Upscale>} l'emporte, les autres n'ont simplement
     * aucun effet tant que leur JSON n'est pas référencé. Pas de sélecteur « actif » à ce stade :
     * le premier besoin réel est que le chargement fonctionne du tout, pas un panneau de choix.
     */
    @SubscribeEvent
    public static void onAddPackFinders(AddPackFindersEvent event) {
        if (event.getPackType() != PackType.CLIENT_RESOURCES) {
            return;
        }
        Path racine = dossier();
        if (!Files.isDirectory(racine)) {
            creerExemple(racine);
            return;
        }
        try (var entrees = Files.list(racine)) {
            entrees.filter(Files::isDirectory).forEach(nuancierDir -> {
                if (!Files.isRegularFile(nuancierDir.resolve("pack.mcmeta"))) {
                    Lanterne.LOG.warn("[NUANCIER] {} ignore : pack.mcmeta manquant (copie celui de exemple_teinte/)",
                            nuancierDir.getFileName());
                    return;
                }
                verifierAvertissement(nuancierDir);
                registerPack(event, nuancierDir);
            });
        } catch (IOException problem) {
            Lanterne.LOG.warn("[NUANCIER] lecture de {} impossible", racine, problem);
        }
    }

    private static void registerPack(AddPackFindersEvent event, Path nuancierDir) {
        String nom = "lanterne_nuancier_" + nuancierDir.getFileName();
        event.addRepositorySource(consumer -> {
            PackLocationInfo info = new PackLocationInfo(nom,
                    Component.literal("Lanterne — " + nuancierDir.getFileName()),
                    PackSource.DEFAULT, Optional.empty());
            Pack pack = Pack.readMetaAndCreate(info,
                    new PathPackResources.PathResourcesSupplier(nuancierDir),
                    PackType.CLIENT_RESOURCES,
                    new PackSelectionConfig(false, Pack.Position.TOP, false));
            if (pack != null) {
                consumer.accept(pack);
                Lanterne.LOG.info("[NUANCIER] {} charge comme pack de ressources", nuancierDir.getFileName());
            } else {
                Lanterne.LOG.warn("[NUANCIER] {} n'a pas pu se charger (pack.mcmeta invalide ?)", nuancierDir.getFileName());
            }
        });
    }

    /**
     * Avertit tôt, dans un langage clair, du défaut précis qui a coûté une session de débogage sur
     * les shaders livrés avec le mod — plutôt que de laisser un joueur retrouver seul une exception
     * {@code ShaderManager$CompilationException} sans rapport évident avec sa cause.
     */
    private static void verifierAvertissement(Path nuancierDir) {
        Path shaders = nuancierDir.resolve("assets").resolve(Lanterne.ID).resolve("shaders").resolve("post");
        if (!Files.isDirectory(shaders)) {
            return;
        }
        try (var fichiers = Files.list(shaders)) {
            fichiers.filter(p -> p.toString().endsWith(".fsh") || p.toString().endsWith(".vsh")).forEach(fichier -> {
                try {
                    String source = Files.readString(fichier);
                    boolean aDesEntreesSorties = source.contains("\nin ") || source.contains("\nout ")
                            || source.startsWith("in ") || source.startsWith("out ");
                    boolean aLayoutLocation = source.contains("layout(location");
                    if (aDesEntreesSorties && !aLayoutLocation) {
                        Lanterne.LOG.warn(
                                "[NUANCIER] {} : variable(s) in/out sans 'layout(location = N)' — "
                                + "ce pipeline compile en SPIR-V (Vulkan) et l'exige, la compilation va probablement echouer",
                                fichier.getFileName());
                    }
                } catch (IOException ignored) {
                    // Simple avertissement préventif : une erreur de lecture ici ne doit pas empêcher
                    // le chargement réel, qui a son propre traitement d'erreur dans registerPack.
                }
            });
        } catch (IOException ignored) {
            // idem
        }
    }

    /**
     * Écrit un nuancier de démonstration minimal (teinte sépia) au premier lancement, pour que la
     * mécanique de chargement soit vérifiable sans attendre qu'un joueur dépose quoi que ce soit.
     */
    private static void creerExemple(Path racine) {
        try {
            Path exemple = racine.resolve("exemple_teinte");
            Path shaders = exemple.resolve("assets").resolve(Lanterne.ID).resolve("shaders").resolve("post");
            Path post = exemple.resolve("assets").resolve(Lanterne.ID).resolve("post");
            Files.createDirectories(shaders);
            Files.createDirectories(post);

            // 97 = resource_major reel de cette version, lu dans version.json du jar client
            // (pas suppose) : un pack_format errone fait apparaitre un avertissement
            // d'incompatibilite, voire un refus de charger selon la config du joueur.
            Files.writeString(exemple.resolve("pack.mcmeta"), """
                    {
                      "pack": {
                        "pack_format": 97,
                        "description": "Nuancier d'exemple Lanterne — teinte sepia"
                      }
                    }
                    """);

            Files.writeString(shaders.resolve("teinte.fsh"), """
                    #version 330

                    uniform sampler2D InSampler;

                    layout(location = 0) in vec2 texCoord;

                    layout(location = 0) out vec4 fragColor;

                    void main() {
                        vec3 c = texture(InSampler, texCoord).rgb;
                        float gris = dot(c, vec3(0.299, 0.587, 0.114));
                        vec3 sepia = vec3(gris * 1.07, gris * 0.87, gris * 0.63);
                        fragColor = vec4(sepia, 1.0);
                    }
                    """);

            Files.writeString(post.resolve("teinte.json"), """
                    {
                      "passes": [
                        {
                          "name": "lanterne:post/teinte",
                          "input": [
                            { "sampler_name": "InSampler", "target": "lanterne:scene" }
                          ],
                          "output": { "target": "minecraft:main" }
                        }
                      ]
                    }
                    """);

            Lanterne.LOG.info("[NUANCIER] dossier {} cree avec un exemple (teinte sepia) — depose d'autres "
                    + "sous-dossiers a cote pour tes propres nuanciers", racine);
        } catch (IOException problem) {
            Lanterne.LOG.warn("[NUANCIER] impossible de creer {}", racine, problem);
        }
    }
}
