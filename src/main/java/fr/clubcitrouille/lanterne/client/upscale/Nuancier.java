package fr.clubcitrouille.lanterne.client.upscale;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import com.mojang.brigadier.arguments.StringArgumentType;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.event.AddPackFindersEvent;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;

import fr.clubcitrouille.lanterne.Lanterne;
import fr.clubcitrouille.lanterne.core.ClientConfig;
import fr.clubcitrouille.lanterne.core.Settings;

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
 * <p>Un second nuancier de démonstration, {@code club_citrouille/}, est écrit automatiquement à
 * côté de {@code exemple_teinte/} par {@link #creerClubCitrouille} — même schéma minimal que
 * ci-dessus, sous l'identifiant {@code lanterne:club_citrouille}. Ni l'un ni l'autre n'est demandé
 * par défaut par {@link Upscale} — même prudence que {@code exemple_teinte/}, pour qu'un joueur qui
 * n'a rien demandé ne voie jamais son rendu changer au premier lancement —, mais {@code
 * club_citrouille} n'est plus <em>structurellement</em> inerte comme il l'était sous son ancien nom
 * {@code demo_ao_normales} : {@link ClientConfig#LENS_NUANCIER} (réglage {@code
 * lentille_nuancier_actif} de {@code lanterne-client.toml}) choisit désormais un nuancier déposé
 * <b>par nom</b>, lu par {@link Resolve#chain()}. Avant ce réglage, la seule façon de rendre un
 * nuancier actif était de faire coïncider, par hasard de nommage, le nom de son JSON avec
 * l'identifiant que {@link Upscale#chainPath()} calcule depuis la netteté et l'anticrénelage —
 * un mécanisme réel (voir la vérification ci-dessous) mais jamais un vrai sélecteur.
 *
 * <p><b>Vérification réellement effectuée, la première fois sous l'ancien nom</b> (pas seulement en
 * théorie) : le même algorithme d'AO, avec les mêmes noms d'échantillonneur, a été branché en
 * quatrième passe d'une copie de {@code upscale_aa_moyenne.json} (la chaîne réellement demandée
 * par défaut — netteté {@code MOYENNE}, anticrénelage actif), déposée dans
 * {@code config/lanterne/shaderpacks/} d'une instance de jeu réelle. Capture {@code Snap} à
 * l'appui : {@code [ÉCHELLE] Pipeline de reconstruction des normales compilé.} au chargement, puis
 * chaîne à quatre passes ({@code aa_edge} → {@code fsr_easu} → {@code fsr_rcas} → la passe AO
 * ci-dessus) exécutée sans exception pendant plus de vingt images consécutives, image finale
 * valide (pas d'écran noir, pas de {@code NaN} rose).
 *
 * <p>{@link #creerClubCitrouille} écrit désormais <b>exactement</b> cette chaîne à quatre passes
 * vérifiée — plus besoin de la reconstituer à la main pour la reproduire : {@code
 * lentille_nuancier_actif = "club_citrouille"} dans {@code lanterne-client.toml}, avec
 * {@code lentille = true}, suffit. Voir l'historique de {@code Nuancier.java} pour la vérification
 * en jeu réel refaite sous le nom {@code club_citrouille} avec ce sélecteur, capture {@code Snap}
 * à l'appui elle aussi.
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
     * Les noms des sous-dossiers valables de {@code shaderpacks/} — même filtre que
     * {@link #onAddPackFinders} (un {@code pack.mcmeta} présent), pas la liste brute des dossiers.
     * Triée, pour un affichage et une autocomplétion stables. Lue par {@code /lanterne nuancier}
     * ci-dessous ; ni mise en cache ni écoutée sur rechargement, une commande valant bien une
     * lecture de dossier à chaque appel.
     */
    public static List<String> listNames() {
        Path racine = dossier();
        if (!Files.isDirectory(racine)) {
            return List.of();
        }
        try (var entrees = Files.list(racine)) {
            return entrees.filter(Files::isDirectory)
                    .filter(candidat -> Files.isRegularFile(candidat.resolve("pack.mcmeta")))
                    .map(candidat -> candidat.getFileName().toString())
                    .sorted()
                    .toList();
        } catch (IOException problem) {
            Lanterne.LOG.warn("[NUANCIER] lecture de {} impossible", racine, problem);
            return List.of();
        }
    }

    /**
     * Enregistre chaque sous-dossier de {@code shaderpacks/} comme pack de ressources séparé.
     *
     * <p>Plusieurs nuanciers peuvent cohabiter — celui qui définit effectivement
     * {@code lanterne:<chemin demandé>} (sous {@code assets/lanterne/post_effect/}, voir la
     * section « Format attendu » ci-dessus) l'emporte, les autres n'ont simplement aucun effet
     * tant que leur JSON n'est pas référencé. Cette méthode-ci ne choisit rien : elle ne fait que
     * charger chaque dossier comme un pack de ressources de plus. Le choix du chemin demandé
     * — donc, indirectement, du nuancier actif — appartient à {@link Resolve#chain()}, qui lit
     * {@link ClientConfig#LENS_NUANCIER} pour ça (voir le Javadoc de classe, section format
     * étendu).
     */
    @SubscribeEvent
    public static void onAddPackFinders(AddPackFindersEvent event) {
        if (event.getPackType() != PackType.CLIENT_RESOURCES) {
            return;
        }
        Path racine = dossier();
        if (!Files.isDirectory(racine)) {
            creerExemple(racine);
            creerClubCitrouille(racine);
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
     * Écrit le nuancier « Club Citrouille », sous {@code lanterne:club_citrouille} — le rebranding
     * de l'ancien {@code demo_ao_normales} (voir l'historique de ce fichier pour la version
     * d'origine), avec le même mécanisme technique exact : {@link Gbuffer}/{@link CameraUniforms}
     * ne changent pas d'une ligne, seul l'habillage change.
     *
     * <p>Contrairement à l'ancien nom, celui-ci n'est <b>plus</b> inerte par construction : voir
     * {@link ClientConfig#LENS_NUANCIER}. Il reste néanmoins inactif tant qu'un joueur — ou
     * l'administrateur qui prépare une instance — ne pose pas {@code lentille_nuancier_actif =
     * "club_citrouille"} (avec {@code lentille = true}) dans {@code lanterne-client.toml} :
     * l'écrire sur le disque au premier lancement ne suffit toujours pas à l'activer, exactement
     * la même prudence que {@link #creerExemple}.
     *
     * <h2>La chaîne écrite est la variante à quatre passes, pas la version à une seule</h2>
     *
     * <p>La toute première vérification en jeu réel de cet algorithme d'AO (voir le Javadoc de
     * classe) l'avait branché comme <b>quatrième passe</b> d'une copie de
     * {@code upscale_aa_moyenne.json} — {@code aa_edge} → {@code fsr_easu} → {@code fsr_rcas} → AO
     * — précisément pour l'observer sur un rendu qui ressemble à ce qu'un joueur voit vraiment,
     * anticrénelage et remontée FSR compris, plutôt que sur une image brute. Cette méthode écrit
     * désormais cette même chaîne à quatre passes telle quelle, au lieu d'une seule passe qu'il
     * fallait auparavant recombiner à la main pour la reproduire : les trois premières passes
     * référencent les nuanceurs {@code aa_edge}/{@code fsr_easu}/{@code fsr_rcas} déjà présents
     * dans le jar principal du mod (résolus par la pile de packs de ressources — voir le Javadoc de
     * classe, {@link #onAddPackFinders} — sans qu'il faille les recopier ici), et seule la
     * quatrième référence un nuanceur propre à ce dossier.
     */
    private static void creerClubCitrouille(Path racine) {
        try {
            Path exemple = racine.resolve("club_citrouille");
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
                        "description": "Lanterne -- Club Citrouille : AO ecran-espace a partir de lanterne:normal, activable via lentille_nuancier_actif"
                      }
                    }
                    """);

            // Meme algorithme que la version d'origine (demo_ao_normales) : occlusion par
            // comparaison de profondeur/normale entre le fragment courant et huit voisins fixes.
            // Volontairement simple -- "un effet simple, genre AO ecran-espace basique" -- pas une
            // reconstruction en vraie distance 3D, qui demanderait les matrices camera qu'un
            // nuancier PostChain ne peut justement pas recevoir (voir le Javadoc de classe, section
            // "ce que ce format n'offre pas encore"). Seule addition sur ce rebranding : une teinte
            // chaude sur l'ombrage de contact plutot qu'un simple assombrissement neutre -- signature
            // visuelle discrete de Club Citrouille, qui ne touche a aucune ligne du calcul d'AO
            // lui-meme.
            Files.writeString(shaders.resolve("club_citrouille_ao.fsh"), """
                    #version 410

                    uniform sampler2D ColorSampler;
                    uniform sampler2D NormalSampler;

                    layout(location = 0) in vec2 texCoord;

                    layout(location = 0) out vec4 fragColor;

                    // La teinte du contact-shadow : legerement orange plutot que grise, pour que
                    // l'AO de ce nuancier se reconnaisse a l'oeil. Le calcul d'occlusion lui-meme,
                    // en dessous, est identique a celui de la toute premiere version.
                    const vec3 TEINTE_CITROUILLE = vec3(0.55, 0.32, 0.10);

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
                        vec3 shaded = mix(color, color * TEINTE_CITROUILLE, occlusion * strength);
                        fragColor = vec4(shaded, 1.0);
                    }
                    """);

            // La chaine a quatre passes reellement verifiee en jeu (voir le Javadoc de la methode) :
            // les trois premieres sont EXACTEMENT celles de upscale_aa_moyenne.json (jar principal),
            // rejouees ici pour que club_citrouille se comporte, hors AO, comme la chaine par
            // defaut -- pas de regression de nettete/anticrenelage a activer ce nuancier. La
            // quatrieme ajoute l'AO en lisant lanterne:normal.
            Files.writeString(post.resolve("club_citrouille.json"), """
                    {
                      "targets": {
                        "swap": { "persistent": true },
                        "preao": { "persistent": true }
                      },
                      "passes": [
                        {
                          "vertex_shader": "minecraft:core/screenquad",
                          "fragment_shader": "lanterne:post/aa_edge",
                          "inputs": [
                            { "sampler_name": "In", "target": "lanterne:scene", "bilinear": false }
                          ],
                          "output": "lanterne:aa"
                        },
                        {
                          "vertex_shader": "minecraft:core/screenquad",
                          "fragment_shader": "lanterne:post/fsr_easu",
                          "inputs": [
                            { "sampler_name": "In", "target": "lanterne:aa", "bilinear": false }
                          ],
                          "output": "swap"
                        },
                        {
                          "vertex_shader": "minecraft:core/screenquad",
                          "fragment_shader": "lanterne:post/fsr_rcas",
                          "inputs": [
                            { "sampler_name": "In", "target": "swap", "bilinear": false }
                          ],
                          "output": "preao",
                          "uniforms": {
                            "RcasConfig": [
                              { "name": "Tuning", "type": "vec4", "value": [0.6, 0.0, 0.0, 0.0] }
                            ]
                          }
                        },
                        {
                          "vertex_shader": "minecraft:core/screenquad",
                          "fragment_shader": "lanterne:post/club_citrouille_ao",
                          "inputs": [
                            { "sampler_name": "ColorSampler", "target": "preao" },
                            { "sampler_name": "NormalSampler", "target": "lanterne:normal" }
                          ],
                          "output": "minecraft:main"
                        }
                      ]
                    }
                    """);

            Lanterne.LOG.info("[NUANCIER] dossier {} cree (Club Citrouille -- AO depuis lanterne:normal) -- "
                    + "pose \"lentille_nuancier_actif = 'club_citrouille'\" (et \"lentille = true\") dans "
                    + "lanterne-client.toml pour l'activer", racine);
        } catch (IOException problem) {
            Lanterne.LOG.warn("[NUANCIER] impossible de creer le nuancier Club Citrouille dans {}", racine, problem);
        }
    }

    // --- Commande -------------------------------------------------------------------------

    /**
     * {@code /lanterne nuancier} : un endroit en jeu pour voir et choisir un nuancier, là où
     * jusqu'ici la seule façon de le faire était d'éditer {@code lentille_nuancier_actif} à la
     * main dans {@code lanterne-client.toml} (voir {@link ClientConfig#LENS_NUANCIER}).
     *
     * <ul>
     *   <li>{@code /lanterne nuancier} ou {@code /lanterne nuancier liste} — énumère ce que
     *       {@link #listNames()} trouve sous {@code config/lanterne/shaderpacks/}, et dit lequel
     *       est actif.</li>
     *   <li>{@code /lanterne nuancier <nom>} — active le nuancier nommé, avec autocomplétion des
     *       noms trouvés ({@code SuggestionProvider}, même geste que {@code Theatre.subjects()}
     *       dans {@code client.ponder.Usher}).</li>
     *   <li>{@code /lanterne nuancier off} — revient à la chaîne intégrée au mod.</li>
     * </ul>
     *
     * <h2>Commande CLIENTE, comme {@code client.profil.Loupe} et {@code client.ponder.Usher}</h2>
     *
     * <p>Ce réglage ne regarde que l'écran de CE joueur — {@link ClientConfig} est du type
     * {@code CLIENT}, pas {@code SERVER}. L'enregistrer sur le dispatcheur serveur (comme
     * {@code report.LanterneCommand}) exécuterait le code sur la machine qui HÉBERGE la partie :
     * en multijoueur, cela écrirait le fichier de configuration client du serveur, pas celui du
     * joueur qui tape la commande — jamais ce qu'on veut pour un réglage que « le serveur ne peut
     * pas imposer ». {@link RegisterClientCommandsEvent} fournit un dispatcheur distinct qui ne
     * quitte jamais le client ; le littéral {@code "lanterne"} s'y fusionne avec celui de Loupe et
     * Usher (Brigadier fusionne les enregistrements successifs d'un même nom), donc
     * {@code /lanterne nuancier} cohabite avec {@code /lanterne profil} et {@code /lanterne guide}
     * sans rien savoir l'un de l'autre.
     *
     * <h2>Pourquoi les messages ne passent pas par {@code CommandSourceStack.sendSuccess}</h2>
     *
     * <p>Même choix que {@code client.profil.Loupe} : une source de commande cliente n'a pas de
     * joueur serveur derrière elle pour porter la réponse. {@link #tell} écrit donc directement
     * dans la discussion du joueur local.
     *
     * <h2>Le changement s'applique sans redémarrage</h2>
     *
     * <p>{@link Resolve#chain()} appelle {@link Upscale#nuancierActif()} à chaque image — voir son
     * Javadoc. {@link Upscale#setNuancier} pose ce champ ET le fichier client dans le même geste
     * que {@link Upscale#cycleEdge}/{@link Upscale#toggleSwell} ; aucun rechargement de ressources
     * n'est nécessaire, la prochaine image dessinée lit déjà la nouvelle valeur. Le nuancier
     * lui-même (son JSON, son GLSL) doit en revanche déjà être chargé comme pack de ressources par
     * {@link #onAddPackFinders} — vrai dès le démarrage du jeu, puisque cette méthode-ci ne
     * choisit qu'un nom parmi ceux qu'{@link #onAddPackFinders} a déjà enregistrés.
     */
    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("lanterne")
                .then(Commands.literal("nuancier")
                        .executes(context -> {
                            liste();
                            return 1;
                        })
                        .then(Commands.literal("liste").executes(context -> {
                            liste();
                            return 1;
                        }))
                        .then(Commands.literal("off").executes(context -> {
                            choisir("");
                            return 1;
                        }))
                        .then(Commands.argument("nom", StringArgumentType.word())
                                .suggests((context, builder) ->
                                        SharedSuggestionProvider.suggest(listNames(), builder))
                                .executes(context -> {
                                    choisir(StringArgumentType.getString(context, "nom"));
                                    return 1;
                                }))));
    }

    private static void liste() {
        List<String> noms = listNames();
        String actif = Upscale.nuancierActif();

        tell(Component.literal("── Nuanciers (" + noms.size() + ") ──").withStyle(ChatFormatting.GOLD));
        if (noms.isEmpty()) {
            tell(Component.literal("Aucun trouvé sous config/lanterne/shaderpacks/. Dépose un "
                    + "sous-dossier avec un pack.mcmeta pour qu'il apparaisse ici — voir "
                    + "exemple_teinte/ et club_citrouille/, écrits automatiquement au premier "
                    + "lancement.").withStyle(ChatFormatting.GRAY));
            return;
        }
        for (String nom : noms) {
            boolean estActif = nom.equals(actif);
            tell(Component.literal((estActif ? "  » " : "    ") + nom + (estActif ? "  (actif)" : ""))
                    .withStyle(estActif ? ChatFormatting.GREEN : ChatFormatting.GRAY));
        }
        if (actif.isBlank()) {
            tell(Component.literal("Aucun nuancier actif — la chaîne intégrée au mod (netteté et "
                    + "anticrénelage réglés ailleurs) est utilisée.").withStyle(ChatFormatting.GRAY));
        } else if (!noms.contains(actif)) {
            // Le fichier de config nomme un nuancier qui n'est plus sur le disque (dossier
            // renomme ou efface entre deux lancements) : Resolve#chain() echouera silencieusement
            // a charger sa chaine et retombera sur le rendu natif — mieux vaut le dire ici que
            // laisser le joueur chercher un ecran fige.
            tell(Component.literal("« " + actif + " » est choisi dans la configuration mais absent "
                    + "de ce dossier — la chaîne ne pourra pas se charger.").withStyle(ChatFormatting.RED));
        }
        if (!Settings.lens()) {
            tell(Component.literal("La lentille (réglage \"lentille\") est éteinte : un nuancier "
                    + "choisi ici ne s'appliquera qu'une fois la lentille allumée.")
                    .withStyle(ChatFormatting.YELLOW));
        }
        tell(Component.literal("« /lanterne nuancier <nom> » pour activer, « /lanterne nuancier "
                + "off » pour revenir à la chaîne intégrée.").withStyle(ChatFormatting.DARK_GRAY));
    }

    private static void choisir(String nom) {
        String propre = nom == null ? "" : nom.trim();
        if (!propre.isEmpty() && !listNames().contains(propre)) {
            tell(Component.literal("Aucun nuancier nommé « " + propre + " » sous "
                    + "config/lanterne/shaderpacks/. « /lanterne nuancier liste » pour voir ceux "
                    + "trouvés.").withStyle(ChatFormatting.RED));
            return;
        }
        Upscale.setNuancier(propre);
        if (propre.isEmpty()) {
            tell(Component.literal("Nuancier désactivé — la chaîne intégrée au mod reprend la main.")
                    .withStyle(ChatFormatting.GOLD));
            return;
        }
        tell(Component.literal("Nuancier actif : " + propre + ".").withStyle(ChatFormatting.GOLD));
        if (!Settings.lens()) {
            tell(Component.literal("La lentille est éteinte : ce nuancier ne s'appliquera qu'une "
                    + "fois \"lentille\" allumée (lanterne-client.toml, ou l'écran Options > "
                    + "Graphismes > Lanterne).").withStyle(ChatFormatting.YELLOW));
        }
    }

    /** Écrit dans la discussion du joueur local. Voir la note de méthode plus haut sur pourquoi. */
    private static void tell(Component message) {
        var player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        player.sendSystemMessage(message);
    }
}
