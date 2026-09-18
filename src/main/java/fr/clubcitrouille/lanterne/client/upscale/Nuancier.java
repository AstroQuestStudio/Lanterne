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
 *       module ({@code exemple_teinte/}) ; copier ce fichier suffit pour un nouveau nuancier.
 *       <b>{@code min_format} et {@code max_format} sont obligatoires en plus de
 *       {@code pack_format}</b> dès que celui-ci dépasse 64 — vérifié en jeu réel cette passe,
 *       {@code Pack.readPackMetadata} rejette sinon le fichier avec {@code "declares support for
 *       version newer than 64, but is missing mandatory fields min_format and max_format"} (repli
 *       silencieux sur un type de métadonnées minimal, pas un échec de chargement du pack — mais
 *       jamais un vrai succès de lecture du {@code pack_format} annoncé).</li>
 *   <li>{@code assets/lanterne/post_effect/<nom>.json} — la chaîne de post-traitement, même
 *       convention de dossier que {@code upscale_aucune} déjà dans le jar (voir {@link Upscale}) :
 *       {@code ShaderManager} résout un identifiant {@code lanterne:<nom>} vers
 *       {@code assets/lanterne/post_effect/<nom>.json} via un {@code FileToIdConverter("post_effect",
 *       ".json")} câblé en dur — vérifié par lecture du bytecode de {@code ShaderManager.class}
 *       (le littéral {@code "post_effect"} y figure dans le bloc d'initialisation statique), pas
 *       supposé. <b>{@code post/}, sans le suffixe {@code _effect}, ne sera jamais lu</b> pour ce
 *       fichier — cette confusion (le dossier des nuanciers de post-traitement avec celui des
 *       nuanceurs GLSL individuels, {@code assets/lanterne/shaders/post/}, qui lui commence
 *       vraiment par {@code post/}) a longtemps été présente dans ce Javadoc et dans
 *       {@link #creerExemple}, corrigée cette passe : voir l'historique de {@code Nuancier.java}
 *       si le besoin d'une trace s'en fait sentir.</li>
 *   <li>{@code assets/lanterne/shaders/post/<nom>.fsh} (et {@code .vsh} si besoin) — le GLSL.
 *       <b>Chaque variable {@code in}/{@code out} du fragment shader doit porter
 *       {@code layout(location = N)}</b> : ce pipeline compile en SPIR-V, qui l'exige, contrairement
 *       à OpenGL classique où c'était optionnel. Un fichier qui l'omet reproduit exactement le bug
 *       corrigé cette session sur {@code fsr_easu.fsh}/{@code fsr_rcas.fsh}/{@code aa_edge.fsh} —
 *       {@link #verifierAvertissement} détecte ce cas précis et journalise un message explicite
 *       plutôt que de laisser {@code ShaderManager} échouer avec une pile d'appels opaque.</li>
 * </ul>
 *
 * <h2>Format étendu — un G-buffer de normales, adossé à des matrices caméra vivantes</h2>
 *
 * <p>Jusqu'à cette passe, un nuancier ne pouvait lire que de la couleur ({@code lanterne:scene})
 * et, via {@code "use_depth_buffer": true}, une profondeur — l'un et l'autre déjà aplatis par le
 * moment où {@link Nuancier} intervient (voir le premier paragraphe de ce Javadoc). Ni normale, ni
 * position monde, ni albédo séparé n'existaient nulle part qu'un JSON de nuancier puisse
 * référencer : structurellement absent, pas seulement non câblé.
 *
 * <p>{@link Gbuffer} comble la première de ces cases. Chaque image, avant que
 * {@link Resolve#run} ne lance la chaîne du nuancier actif, il reconstruit — depuis la seule
 * profondeur de la toile — une normale par fragment par dérivées d'écran ({@code dFdx}/
 * {@code dFdy} sur la position vue, elle-même dépliée de la profondeur via l'inverse de la
 * <b>vraie</b> matrice de projection de l'image, lue chaque frame par {@link CameraUniforms} plutôt
 * que figée dans un JSON). Le résultat est publié sous une quatrième cible externe,
 * {@code lanterne:normal} (voir {@link Resolve#NORMAL}), désormais dans la liste blanche de
 * {@link Resolve} au même titre que {@code lanterne:scene} et {@code lanterne:aa} :
 *
 * <ul>
 *   <li><b>RGB</b> — la normale vue (espace caméra), en composantes <b>signées</b> ({@code -1..1},
 *       aucun décodage à faire côté nuancier — la cible est {@code RGBA16_FLOAT}, pas un format
 *       normalisé).</li>
 *   <li><b>A</b> — la profondeur brute de la même image, la même convention inversée que partout
 *       ailleurs dans ce module (proche = 1,0, lointain = 0,0 ; voir {@code Reproject.matrix()}).
 *       Un nuancier qui veut la profondeur n'a <b>jamais</b> besoin de
 *       {@code "use_depth_buffer": true} sur {@code lanterne:scene} pour ça — utile parce que cette
 *       cible-là n'a pas toujours de profondeur (quand l'accumulation temporelle est active, le
 *       réglage par défaut, {@code lanterne:scene} désigne alors le résultat de l'accumulation, qui
 *       n'en porte pas). {@code lanterne:normal}, lui, vient toujours de la vraie toile, donc son
 *       canal alpha est fiable quel que soit le chemin emprunté en aval.</li>
 * </ul>
 *
 * <p>Exemple minimal — un fragment shader d'occlusion ambiante écran-espace, à partir de
 * {@code lanterne:normal} seul :
 *
 * <pre>{@code
 * // assets/lanterne/post_effect/mon_nuancier.json
 * {
 *   "passes": [
 *     {
 *       "vertex_shader": "minecraft:core/screenquad",
 *       "fragment_shader": "lanterne:post/mon_ao",
 *       "inputs": [
 *         { "sampler_name": "ColorSampler",  "target": "lanterne:scene" },
 *         { "sampler_name": "NormalSampler", "target": "lanterne:normal" }
 *       ],
 *       "output": "minecraft:main"
 *     }
 *   ]
 * }
 * }</pre>
 *
 * <p>Un second nuancier de démonstration, {@code demo_ao_normales/}, est écrit automatiquement à
 * côté de {@code exemple_teinte/} par {@link #creerExempleGbuffer} — même schéma minimal que
 * ci-dessus, sous l'identifiant inerte {@code lanterne:demo_ao_normales} (jamais demandé par
 * {@link Upscale}, donc <b>sans aucun effet tant qu'on ne le renomme pas</b> — même prudence que
 * {@code exemple_teinte/}, pour qu'un joueur qui n'a rien demandé ne voie jamais son rendu changer
 * au premier lancement).
 *
 * <p><b>Vérification réellement effectuée cette passe</b> (pas seulement en théorie) : le même
 * algorithme d'AO, avec les mêmes noms d'échantillonneur, a été branché en quatrième passe d'une
 * copie de {@code upscale_aa_moyenne.json} (la chaîne réellement demandée par défaut — netteté
 * {@code MOYENNE}, anticrénelage actif), déposée dans {@code config/lanterne/shaderpacks/} d'une
 * instance de jeu réelle. Capture {@code Snap} à l'appui : {@code [ÉCHELLE] Pipeline de
 * reconstruction des normales compilé.} au chargement, puis chaîne à quatre passes (
 * {@code aa_edge} → {@code fsr_easu} → {@code fsr_rcas} → la passe AO ci-dessus) exécutée sans
 * exception pendant plus de vingt images consécutives, image finale valide (pas d'écran noir, pas
 * de {@code NaN} rose). Pour reproduire : renommer une copie de {@code demo_ao_normales.json} en
 * {@code post_effect/upscale_aa_moyenne.json} dans un nuancier déposé (en adaptant ses noms de
 * cible d'entrée à ceux du vrai fichier, voir le jar principal), activer la lentille
 * ({@code lentille = true} dans la configuration client), lancer avec
 * {@code LANTERNE_AUTO_SCREENSHOT=1}.
 *
 * <h2>Ce que ce format N'offre PAS encore, honnêtement</h2>
 *
 * <p>{@link CameraUniforms} — le mécanisme qui rend {@code lanterne:normal} correcte — reste un
 * détail d'implémentation interne au mod, pas un point d'extension pour un nuancier tiers : un
 * fragment shader chargé via {@code PostChain} reste un nuanceur vanilla, sans aucun moyen de lier
 * un {@code BindGroupLayout} choisi par le mod. Une matrice caméra publiée <b>directement</b> à
 * l'attention d'un nuancier tiers (pour des reflets ou une AO en vraie distance 3D plutôt qu'en
 * simple corrélation d'écran) demanderait de répéter, pour les matrices elles-mêmes, l'astuce déjà
 * employée pour {@code lanterne:normal} : les encoder dans une texture dédiée (quelques texels,
 * lus par {@code texelFetch} côté GLSL) plutôt que tenter — en vain, voir le Javadoc de
 * {@link Accumulate} — de les faire passer par {@code UniformValue}. Ni cette cible-là, ni
 * position-monde, ni albédo séparé ne sont livrés cette passe : {@code lanterne:normal} est le
 * strict minimum utile (AO/reflets écran-espace basiques), pas l'ensemble du chantier G-buffer.
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
     * {@code lanterne:<chemin demandé par Upscale>} (sous {@code assets/lanterne/post_effect/},
     * voir la section « Format attendu » ci-dessus) l'emporte, les autres n'ont simplement aucun
     * effet tant que leur JSON n'est pas référencé. Pas de sélecteur « actif » à ce stade : le
     * premier besoin réel est que le chargement fonctionne du tout, pas un panneau de choix.
     */
    @SubscribeEvent
    public static void onAddPackFinders(AddPackFindersEvent event) {
        if (event.getPackType() != PackType.CLIENT_RESOURCES) {
            return;
        }
        Path racine = dossier();
        if (!Files.isDirectory(racine)) {
            creerExemple(racine);
            creerExempleGbuffer(racine);
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
            // "post_effect", PAS "post" : voir le Javadoc de classe, section format étendu, pour
            // la preuve par bytecode. Ecrire dans "post" produirait un nuancier silencieusement
            // invisible pour ShaderManager -- exactement le bug que cette passe corrige ici.
            Path post = exemple.resolve("assets").resolve(Lanterne.ID).resolve("post_effect");
            Files.createDirectories(shaders);
            Files.createDirectories(post);

            // 97 = resource_major reel de cette version, lu dans version.json du jar client
            // (pas suppose) : un pack_format errone fait apparaitre un avertissement
            // d'incompatibilite, voire un refus de charger selon la config du joueur.
            //
            // min_format/max_format sont OBLIGATOIRES des que pack_format depasse 64 (verifie par
            // lecture de PackFormat.IntermediaryFormat.validate, qui refuse sinon avec exactement
            // le message observe en jeu reel cette passe : "declares support for version newer
            // than 64, but is missing mandatory fields min_format and max_format"). Ce fichier
            // n'avait que pack_format avant cette passe -- Pack.readMetaAndCreate s'en remettait a
            // un repli silencieux, jamais a un vrai succes de parsing.
            Files.writeString(exemple.resolve("pack.mcmeta"), """
                    {
                      "pack": {
                        "pack_format": 97,
                        "min_format": 97,
                        "max_format": 97,
                        "description": "Nuancier d'exemple Lanterne — teinte sepia"
                      }
                    }
                    """);

            // 410, pas 330 : layout(location = N) sur une entree/sortie de fragment shader n'est
            // standardise qu'a partir de GLSL 410 -- ce pipeline compile en SPIR-V (Vulkan), qui
            // l'exige. Voir fsr_easu.fsh (jar principal) pour la preuve en conditions reelles de
            // ce que 330 refuse ; ce fichier d'exemple portait encore 330 jusqu'a cette passe,
            // silencieusement jamais exerce (voir aussi le bug de repertoire corrige a cote).
            Files.writeString(shaders.resolve("teinte.fsh"), """
                    #version 410

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

            // Schema reel de PostChainConfig.Pass (verifie par javap sur PostChainConfig.class,
            // pas suppose) : "vertex_shader"/"fragment_shader" separes, "inputs" (pluriel) et non
            // "input", "output" un simple identifiant et non un objet {"target": ...}. La version
            // precedente de ce fichier utilisait "name"/"input"/{"target":...} -- un schema qui
            // n'a jamais correspondu au Codec reel, donc un JSON que ShaderManager n'aurait jamais
            // pu charger. Corrige cette passe, en meme temps que le repertoire post/ -> post_effect/.
            Files.writeString(post.resolve("teinte.json"), """
                    {
                      "passes": [
                        {
                          "vertex_shader": "minecraft:core/screenquad",
                          "fragment_shader": "lanterne:post/teinte",
                          "inputs": [
                            { "sampler_name": "InSampler", "target": "lanterne:scene" }
                          ],
                          "output": "minecraft:main"
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

    /**
     * Écrit un second nuancier de démonstration, sous {@code lanterne:demo_ao_normales} — un
     * identifiant qu'{@link Upscale} ne demande jamais, donc sans aucun effet sur le rendu d'un
     * joueur qui n'y touche pas, exactement comme {@link #creerExemple}. Preuve, committée avec le
     * reste du module, que {@code lanterne:normal} (voir le Javadoc de classe) fonctionne pour un
     * nuancier qui n'écrit que du JSON + GLSL — aucune ligne de Java spécifique à ce fichier.
     *
     * <h2>Pourquoi ce n'est PAS le fichier réellement vérifié via {@code Snap}</h2>
     *
     * <p>La vérification en jeu réel de cette passe a branché ce même algorithme comme
     * <b>quatrième passe</b> d'une copie de {@code upscale_aa_moyenne.json} — la chaîne réellement
     * demandée par défaut — pour l'observer sur le rendu véritablement à l'écran plutôt que sur un
     * identifiant que personne ne charge. Reproduire cette variante précise exigerait de recopier
     * ici les trois passes {@code aa_edge}/{@code fsr_easu}/{@code fsr_rcas} du jar principal, pour
     * un gain pédagogique nul : le contrat que ce fichier illustre (les noms d'échantillonneur, le
     * décodage RGB+A) est identique dans les deux cas, et une seule passe le montre aussi bien que
     * quatre. La différence entre les deux est documentée dans le Javadoc de classe, pas cachée.
     */
    private static void creerExempleGbuffer(Path racine) {
        try {
            Path exemple = racine.resolve("demo_ao_normales");
            Path shaders = exemple.resolve("assets").resolve(Lanterne.ID).resolve("shaders").resolve("post");
            Path post = exemple.resolve("assets").resolve(Lanterne.ID).resolve("post_effect");
            Files.createDirectories(shaders);
            Files.createDirectories(post);

            Files.writeString(exemple.resolve("pack.mcmeta"), """
                    {
                      "pack": {
                        "pack_format": 97,
                        "min_format": 97,
                        "max_format": 97,
                        "description": "Lanterne -- demo G-buffer : AO ecran-espace a partir de lanterne:normal"
                      }
                    }
                    """);

            // Meme algorithme que la variante a quatre passes reellement executee en jeu pour
            // cette verification (voir le Javadoc de la methode) : occlusion par comparaison de
            // profondeur/normale entre le fragment courant et huit voisins fixes. Volontairement
            // simple -- "un effet simple, genre AO ecran-espace basique" -- pas une reconstruction
            // en vraie distance 3D, qui demanderait les matrices camera qu'un nuancier PostChain
            // ne peut justement pas recevoir (voir le Javadoc de classe, section "ce que ce format
            // n'offre pas encore").
            Files.writeString(shaders.resolve("demo_ao.fsh"), """
                    #version 410

                    uniform sampler2D ColorSampler;
                    uniform sampler2D NormalSampler;

                    layout(location = 0) in vec2 texCoord;

                    layout(location = 0) out vec4 fragColor;

                    void main() {
                        vec4 gbuf = texture(NormalSampler, texCoord);
                        vec3 n0 = gbuf.rgb;
                        float d0 = gbuf.a;

                        vec3 color = texture(ColorSampler, texCoord).rgb;

                        if (d0 < 0.001 || dot(n0, n0) < 0.001) {
                            fragColor = vec4(color, 1.0);
                            return;
                        }

                        vec2 texel = 1.0 / vec2(textureSize(NormalSampler, 0));
                        const int SAMPLES = 8;
                        vec2 kernel[8] = vec2[](
                            vec2( 1.0,  0.0), vec2(-1.0,  0.0), vec2( 0.0,  1.0), vec2( 0.0, -1.0),
                            vec2( 0.7071,  0.7071), vec2(-0.7071,  0.7071),
                            vec2( 0.7071, -0.7071), vec2(-0.7071, -0.7071)
                        );

                        float radiusTexels = 3.0;
                        float occlusion = 0.0;
                        for (int i = 0; i < SAMPLES; i++) {
                            vec2 uv = texCoord + kernel[i] * texel * radiusTexels;
                            vec4 s = texture(NormalSampler, uv);
                            float depthDelta = s.a - d0;
                            float agree = max(dot(n0, s.rgb), 0.0);
                            occlusion += agree * smoothstep(0.0015, 0.02, depthDelta);
                        }
                        occlusion /= float(SAMPLES);

                        float strength = 0.9;
                        fragColor = vec4(color * (1.0 - occlusion * strength), 1.0);
                    }
                    """);

            Files.writeString(post.resolve("demo_ao_normales.json"), """
                    {
                      "passes": [
                        {
                          "vertex_shader": "minecraft:core/screenquad",
                          "fragment_shader": "lanterne:post/demo_ao",
                          "inputs": [
                            { "sampler_name": "ColorSampler", "target": "lanterne:scene" },
                            { "sampler_name": "NormalSampler", "target": "lanterne:normal" }
                          ],
                          "output": "minecraft:main"
                        }
                      ]
                    }
                    """);

            Lanterne.LOG.info("[NUANCIER] dossier {} cree avec un second exemple (AO depuis lanterne:normal) — "
                    + "inerte tant que son JSON n'est pas renomme pour remplacer une chaine active, voir le "
                    + "Javadoc de Nuancier pour reproduire la verification", racine);
        } catch (IOException problem) {
            Lanterne.LOG.warn("[NUANCIER] impossible de creer l'exemple de G-buffer dans {}", racine, problem);
        }
    }
}
