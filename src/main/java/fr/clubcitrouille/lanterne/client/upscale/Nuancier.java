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
 *         { "sampler_name": "Color",  "target": "lanterne:scene" },
 *         { "sampler_name": "Normal", "target": "lanterne:normal" }
 *       ],
 *       "output": "minecraft:main"
 *     }
 *   ]
 * }
 * }</pre>
 *
 * <p><b>{@code sampler_name} n'est PAS le nom de l'uniforme GLSL — {@code PostChain} lui ajoute
 * toujours {@code "Sampler"}</b>, vérifié par {@code javap -v} sur le vrai jar patché
 * ({@code PostChain.createPass}, bloc {@code BootstrapMethods} : un {@code invokedynamic
 * makeConcatWithConstants} de gabarit littéral {@code "Sampler"}, appliqué à
 * {@code Input.samplerName()} avant {@code BindGroupLayout.Builder.withUniform(...,
 * COMBINED_IMAGE_SAMPLER)}). {@code "sampler_name": "Color"} exige donc un
 * {@code uniform sampler2D ColorSampler;} côté GLSL — jamais {@code ColorSamplerSampler}, et jamais
 * un uniforme nommé littéralement {@code Color}. C'est la convention déjà suivie par
 * {@code aa_edge}/{@code fsr_easu}/{@code fsr_rcas} du jar principal ({@code "sampler_name": "In"}
 * ↔ {@code InSampler}) — <b>découverte cette passe</b> après un échec réel en jeu
 * ({@code ShaderCompileException: Unable to find shader defined uniform}) sur les nouvelles passes
 * de bloom de {@link #creerClubCitrouille}, qui avaient écrit {@code "sampler_name": "InSampler"}
 * (le nom GLSL complet, déjà suffixé) au lieu du nom de base attendu. Voir l'historique de ce
 * fichier pour la version fautive.</p>
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
 * <p>{@link #creerClubCitrouille} écrivait jusqu'à cette passe <b>exactement</b> cette chaîne à
 * quatre passes vérifiée. Voir l'historique de {@code Nuancier.java} pour la vérification en jeu
 * réel refaite sous le nom {@code club_citrouille} avec ce sélecteur, capture {@code Snap} à
 * l'appui elle aussi.
 *
 * <p><b>Correction apportée cette passe, après un vrai échec en jeu</b> : les deux paragraphes
 * ci-dessus donnent {@code "sampler_name": "ColorSampler"}/{@code "NormalSampler"} comme la
 * convention qui aurait été vérifiée fonctionnelle. Une tentative d'ajouter deux passes de bloom à
 * cette chaîne (voir plus bas) a échoué au chargement, en jeu réel, avec
 * {@code ShaderCompileException: Unable to find shader defined uniform} — et la lecture du
 * bytecode de {@code PostChain.createPass} qui a suivi (voir la note sur {@code sampler_name}
 * au-dessus de l'exemple JSON du Javadoc de classe) montre que {@code sampler_name} n'est
 * <b>jamais</b> le nom d'uniforme GLSL tel quel : {@code "Sampler"} y est toujours concaténé. Sous
 * cette lecture, {@code "sampler_name": "ColorSampler"} demanderait un uniforme
 * {@code ColorSamplerSampler}, que le GLSL de {@code club_citrouille_ao.fsh}/
 * {@code club_citrouille_final.fsh} n'a jamais déclaré. Il n'a pas été possible de retester la
 * chaîne d'origine telle quelle pour trancher si la vérification historique ci-dessus portait sur
 * un jar différent ou était simplement erronée — mais {@link #creerClubCitrouille} utilise
 * désormais {@code "sampler_name": "Color"}/{@code "Normal"}/{@code "Bloom"} (sans le suffixe),
 * seule forme cohérente avec le bytecode lu et avec {@code aa_edge}/{@code fsr_easu}/
 * {@code fsr_rcas}, qui n'ont eux jamais cessé de fonctionner.
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
            // NE PAS "return" ici : c'était la vraie cause (bytecode-vérifiée séparément, voir
            // registerPack) d'un nuancier "introuvable" sur le TOUT PREMIER lancement d'une
            // instance. creerExemple/creerClubCitrouille n'écrivent que des FICHIERS sur le
            // disque -- rien de tout cela n'appelle event.addRepositorySource, la seule chose qui
            // rend un dossier visible à Pack.readMetaAndCreate/ShaderManager POUR CETTE SESSION.
            // Un "return" immédiat après l'écriture laissait donc le nuancier flambant neuf
            // invisible jusqu'au PROCHAIN démarrage du client -- reproduit en jeu réel (run
            // "lambdaform", dossier shaderpacks/ absent au lancement) : "club_citrouille charge
            // comme pack de ressources" n'apparaissait JAMAIS dans ce journal-là, et
            // ShaderManager échouait avec exactement "Attempted to load a non-existent post
            // effect lanterne:club_citrouille". On laisse donc tomber dans la branche
            // d'enregistrement ci-dessous, sur le dossier qu'on vient d'écrire.
            creerExemple(racine);
            creerClubCitrouille(racine);
        }
        try (var entrees = Files.list(racine)) {
            entrees.filter(Files::isDirectory).forEach(nuancierDir -> {
                if (!Files.isRegularFile(nuancierDir.resolve("pack.mcmeta"))) {
                    Lanterne.LOG.warn("[NUANCIER] {} ignore : pack.mcmeta manquant (copie celui de exemple_teinte/)",
                            nuancierDir.getFileName());
                    return;
                }
                repareMetaSiPerime(nuancierDir);
                verifierAvertissement(nuancierDir);
                registerPack(event, nuancierDir);
            });
        } catch (IOException problem) {
            Lanterne.LOG.warn("[NUANCIER] lecture de {} impossible", racine, problem);
        }
    }

    /**
     * Répare un {@code pack.mcmeta} périmé pour les deux nuanciers que ce module écrit lui-même.
     *
     * <p>{@link #creerExemple}/{@link #creerClubCitrouille} n'écrivent leurs fichiers qu'au tout
     * premier lancement, quand {@code shaderpacks/} n'existe pas encore — voir
     * {@link #onAddPackFinders}. Une instance créée avant l'ajout de {@code min_format}/
     * {@code max_format} à ces gabarits garde donc, de lancement en lancement, l'ancien
     * {@code pack.mcmeta} : c'est le cas réellement observé sur {@code exemple_teinte/} de
     * l'instance « test 1 » (journal : {@code Error reading pack metadata, attempting fallback
     * type} à chaque démarrage). Sans conséquence fonctionnelle pour {@code exemple_teinte}
     * lui-même — {@code Pack.readPackMetadata} retombe sur un type minimal et le pack se charge
     * quand même — mais un journal qui crie au premier lancement pour un fichier que ce module a
     * écrit lui-même, avec le mauvais contenu, n'a pas de raison de continuer à le faire : ce n'est
     * réparé QUE pour les deux dossiers que {@link #creerExemple}/{@link #creerClubCitrouille}
     * possèdent, jamais pour un nuancier tiers déposé par un joueur, dont le {@code pack.mcmeta} lui
     * appartient.
     */
    private static void repareMetaSiPerime(Path nuancierDir) {
        String nom = nuancierDir.getFileName().toString();
        if (!nom.equals("exemple_teinte") && !nom.equals("club_citrouille")) {
            return;
        }
        Path meta = nuancierDir.resolve("pack.mcmeta");
        try {
            String contenu = Files.readString(meta);
            if (contenu.contains("min_format")) {
                return; // déjà à jour
            }
            String description = nom.equals("exemple_teinte")
                    ? "Nuancier d'exemple Lanterne — teinte sepia"
                    : "Lanterne -- Club Citrouille : activable via lentille_nuancier_actif";
            Files.writeString(meta, """
                    {
                      "pack": {
                        "pack_format": 97,
                        "min_format": 97,
                        "max_format": 97,
                        "description": "%s"
                      }
                    }
                    """.formatted(description));
            Lanterne.LOG.info("[NUANCIER] {} : pack.mcmeta périmé (min_format/max_format absents) régénéré",
                    nom);
        } catch (IOException problem) {
            Lanterne.LOG.warn("[NUANCIER] {} : lecture/réparation de pack.mcmeta impossible", nom, problem);
        }
    }

    private static void registerPack(AddPackFindersEvent event, Path nuancierDir) {
        String nom = "lanterne_nuancier_" + nuancierDir.getFileName();
        event.addRepositorySource(consumer -> {
            PackLocationInfo info = new PackLocationInfo(nom,
                    Component.literal("Lanterne — " + nuancierDir.getFileName()),
                    PackSource.DEFAULT, Optional.empty());
            // required = true, et non false : la vraie cause du "Attempted to load a non-existent
            // post effect lanterne:<nom>" observe en jeu reel (session du 19/09, instance "test 1",
            // nuancier club_citrouille). Verifie par javap sur le VRAI jar client patche
            // (net/minecraft/server/packs/repository/PackRepository.class, methode
            // rebuildSelected) : la liste "selected" (celle qui alimente vraiment le
            // ResourceManager) part des identifiants deja connus de options.txt
            // ("resourcePacks:[]" sur cette instance -- jamais peuple pour un nuancier depose a la
            // main), PUIS n'ajoute en plus que les packs dont Pack.isRequired() vaut vrai. Un pack
            // non "required" reste donc seulement "available" (visible dans Options > Packs de
            // ressources, d'ou le "... charge comme pack de ressources" au journal, qui ne prouve
            // que la construction de l'objet Pack, jamais sa selection) mais n'est JAMAIS fusionne
            // dans le ResourceManager tant qu'un joueur ne l'active pas a la main dans cet ecran --
            // ce que ni la documentation de ce module ni /lanterne nuancier ne mentionnent, et que
            // rien n'automatise. ShaderManager cherche alors assets/lanterne/post_effect/<nom>.json
            // dans un ResourceManager qui n'a jamais recu ce pack : "non-existent" est donc litteral,
            // pas une erreur de chemin ni de compilation GLSL -- le fichier existe bel et bien sur
            // le disque, verifie cote-a-cote avec cette meme instance. required = true force son
            // inclusion dans "selected" a chaque reload, quel que soit resourcePacks.txt, exactement
            // comme le pack de ressources du mod lui-meme (mod/lanterne, toujours actif sans geste du
            // joueur) -- cohérent avec la conception documentee plus haut : le nuancier ACTIF se
            // choisit par /lanterne nuancier ou lanterne-client.toml, jamais par l'ecran Options >
            // Packs de ressources, qui n'a donc aucune raison de gouverner si ses fichiers sont
            // seulement visibles au jeu.
            Pack pack = Pack.readMetaAndCreate(info,
                    new PathPackResources.PathResourcesSupplier(nuancierDir),
                    PackType.CLIENT_RESOURCES,
                    new PackSelectionConfig(true, Pack.Position.TOP, false));
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
            //
            // "sampler_name": "In", PAS "InSampler" -- PostChain.createPass concatene toujours
            // "Sampler" au nom donne ici pour batir l'uniforme GLSL attendu (verifie par javap -v,
            // BootstrapMethods de PostChain.class : invokedynamic makeConcatWithConstants de gabarit
            // "Sampler"). "InSampler" ici aurait demande un uniforme "InSamplerSampler", absent de
            // teinte.fsh -- exactement le bug trouve et corrige cette passe sur club_citrouille, ici
            // corrige par la meme occasion avant qu'il ne soit exerce. Voir le Javadoc de classe.
            Files.writeString(post.resolve("teinte.json"), """
                    {
                      "passes": [
                        {
                          "vertex_shader": "minecraft:core/screenquad",
                          "fragment_shader": "lanterne:post/teinte",
                          "inputs": [
                            { "sampler_name": "In", "target": "lanterne:scene" }
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
     * <h2>La chaîne écrite est désormais une variante à six passes, pas quatre</h2>
     *
     * <p>La toute première vérification en jeu réel de l'algorithme d'AO seul (voir le Javadoc de
     * classe) l'avait branché comme <b>quatrième passe</b> d'une copie de
     * {@code upscale_aa_moyenne.json} — {@code aa_edge} → {@code fsr_easu} → {@code fsr_rcas} → AO
     * — précisément pour l'observer sur un rendu qui ressemble à ce qu'un joueur voit vraiment.
     * Cette passe-là (renommée {@code club_citrouille_final}) reste la dernière de la chaîne, mais
     * deux passes de plus s'intercalent désormais avant elle : {@code club_citrouille_bloom_h} puis
     * {@code club_citrouille_bloom_v}, un flou gaussien séparable à 5 coefficients (poids
     * {@code 0.227/0.195/0.122/0.054/0.016}, formule générique — voir par ex. learnopengl.com,
     * « Bloom » — pas empruntée à un shaderpack précis) sur un seuil de luminance doux
     * ({@code smoothstep(0.68, 0.92, luma)}), appliqué avec un PAS de deux texels entre échantillons
     * pour porter le flou plus loin sans multiplier les lectures. Ce n'est PAS le flou HDR en
     * pyramide de mip d'un vrai moteur : la toile composée ici est déjà LDR (bornée {@code 0..1}
     * par {@code fsr_rcas}), donc ce halo n'éclate que ce qui est déjà proche de blanc à l'écran
     * (soleil, lave, lanternes, feu) — un « bloom léger », pas un bloom physiquement correct.
     *
     * <h2>Ce qu'ajoute {@code club_citrouille_final} par rapport à la version AO-seule</h2>
     *
     * <p>Inspiré, dans les limites honnêtes de ce pipeline (couleur + normales + profondeur
     * reconstruite, aucune matrice caméra, aucun G-buffer d'albédo/position monde — voir la section
     * « Ce que ce format n'offre PAS encore » du Javadoc de classe), par les techniques les plus
     * visibles de <i>Complementary Reimagined</i> (étudié en lecture seule depuis une copie de
     * {@code ComplementaryReimagined_r5.9.zip}, jamais son code copié — voir {@code NOTICE.md}) :
     * <ul>
     *   <li><b>AO à deux rayons</b> (2,5 et 5,5 texels, 16 échantillons au lieu de 8) — un résultat
     *       plus doux que l'anneau unique d'origine, toujours écran-espace, toujours sans matrices
     *       caméra.</li>
     *   <li><b>Bloom léger</b> — voir ci-dessus.</li>
     *   <li><b>Brume de distance</b> — la profondeur brute déjà portée par {@code lanterne:normal}
     *       (alpha, proche = 1, lointain = 0) mélange une teinte chaude sur la géométrie réelle
     *       lointaine ({@code sky} exclu : le ciel est déjà coloré par vanilla, pas besoin de
     *       brume par-dessus). Aucune distance linéaire réelle n'est disponible dans une passe
     *       {@code PostChain} JSON (voir la section citée) : c'est une courbe {@code smoothstep} sur
     *       la profondeur NON linéaire, calibrée à l'œil via {@code Snap}, pas une brume physique.</li>
     *   <li><b>Étalonnage cinématographique</b> — une courbe filmique bon marché (Hejl/Burgess-Dawson,
     *       {@code (x·(6.2x+0.5))⁄(x·(6.2x+1.7)+0.06)}, formule publique largement diffusée, pas
     *       propre à un shaderpack), une saturation légèrement relevée, et un virage bicolore
     *       (« teal & orange » — ombres tirant vers le froid, hautes lumières vers le chaud), une
     *       technique générique de retouche, écrite ici avec ses propres coefficients.</li>
     *   <li><b>Vignette</b> légère et <b>tramage</b> (dither) pour ne pas faire bander le dégradé
     *       ajouté par l'étalonnage.</li>
     * </ul>
     *
     * <p><b>Ce qui n'est délibérément PAS tenté</b>, pour ne pas sur-vendre : ombres dynamiques
     * (aucun shadow map, ce pipeline n'en a pas), eau animée avancée (aucune passe {@code gbuffers_water}
     * à intercepter), éclairage volumétrique (demanderait une marche à pas dans le view-space depuis
     * une source de lumière connue, hors de portée sans matrices caméra exposées à ce nuancier) —
     * voir le Javadoc de classe pour ce qui manque structurellement.
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
                        "description": "Lanterne -- Club Citrouille : AO deux rayons + bloom leger + brume + etalonnage cinematographique, depuis lanterne:normal, activable via lentille_nuancier_actif"
                      }
                    }
                    """);

            // Seuil de luminance doux + flou gaussien separable 5 coefficients, PREMIERE moitie
            // (horizontale). Poids 0.227027/0.1945946/0.1216216/0.054054/0.016216 : coefficients
            // generiques d'un flou gaussien a 9 echantillons repandu (voir par ex. learnopengl.com,
            // chapitre "Bloom") -- pas empruntes a un shaderpack donne. Le PAS de deux texels entre
            // echantillons (au lieu d'un) porte le flou deux fois plus loin sans ajouter de lectures :
            // c'est ce qui donne un vrai halo visible autour du soleil/de la lave/des citrouilles
            // allumees plutot qu'un flou d'un ou deux pixels, invisible a l'echelle de l'ecran.
            Files.writeString(shaders.resolve("club_citrouille_bloom_h.fsh"), """
                    #version 410

                    uniform sampler2D InSampler;

                    layout(location = 0) in vec2 texCoord;

                    layout(location = 0) out vec4 fragColor;

                    float luma(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }

                    void main() {
                        vec2 texel = 1.0 / vec2(textureSize(InSampler, 0));
                        float weights[5] = float[](0.227027, 0.1945946, 0.1216216, 0.054054, 0.016216);
                        float stride = 2.0;

                        vec3 center = texture(InSampler, texCoord).rgb;
                        vec3 sum = center * smoothstep(0.68, 0.92, luma(center)) * weights[0];

                        for (int i = 1; i < 5; i++) {
                            float off = float(i) * stride * texel.x;
                            vec3 a = texture(InSampler, texCoord + vec2(off, 0.0)).rgb;
                            vec3 b = texture(InSampler, texCoord - vec2(off, 0.0)).rgb;
                            sum += a * smoothstep(0.68, 0.92, luma(a)) * weights[i];
                            sum += b * smoothstep(0.68, 0.92, luma(b)) * weights[i];
                        }

                        fragColor = vec4(sum, 1.0);
                    }
                    """);

            // Seconde moitie (verticale) du meme flou. Le seuil de luminance n'est PAS reapplique
            // ici : la passe horizontale l'a deja fait, la reappliquer assombrirait le bord du halo
            // au lieu de l'etaler proprement.
            Files.writeString(shaders.resolve("club_citrouille_bloom_v.fsh"), """
                    #version 410

                    uniform sampler2D InSampler;

                    layout(location = 0) in vec2 texCoord;

                    layout(location = 0) out vec4 fragColor;

                    void main() {
                        vec2 texel = 1.0 / vec2(textureSize(InSampler, 0));
                        float weights[5] = float[](0.227027, 0.1945946, 0.1216216, 0.054054, 0.016216);
                        float stride = 2.0;

                        vec3 sum = texture(InSampler, texCoord).rgb * weights[0];
                        for (int i = 1; i < 5; i++) {
                            float off = float(i) * stride * texel.y;
                            sum += texture(InSampler, texCoord + vec2(0.0, off)).rgb * weights[i];
                            sum += texture(InSampler, texCoord - vec2(0.0, off)).rgb * weights[i];
                        }

                        fragColor = vec4(sum, 1.0);
                    }
                    """);

            // La passe finale : AO deux rayons (16 echantillons au lieu de 8), brume de distance sur
            // la geometrie reelle, ajout du bloom, puis un etalonnage cinematographique complet
            // (courbe filmique + saturation + virage bicolore + vignette + tramage). Voir le Javadoc
            // de la methode pour le detail et les sources de chaque technique -- rien ici n'est copie
            // de Complementary Reimagined, seulement inspire de ses effets les plus visibles dans les
            // limites reelles de ce pipeline (pas de matrices camera exposees a un nuancier tiers).
            Files.writeString(shaders.resolve("club_citrouille_final.fsh"), """
                    #version 410

                    uniform sampler2D ColorSampler;
                    uniform sampler2D NormalSampler;
                    uniform sampler2D BloomSampler;

                    layout(location = 0) in vec2 texCoord;

                    layout(location = 0) out vec4 fragColor;

                    // La teinte du contact-shadow et de la brume lointaine : chaudes plutot que
                    // grises/bleues, pour que la signature visuelle de Club Citrouille se reconnaisse
                    // a l'oeil aussi bien de pres (AO) que de loin (brume).
                    const vec3 TEINTE_CITROUILLE = vec3(0.55, 0.32, 0.10);
                    const vec3 FOG_TEINTE = vec3(0.58, 0.42, 0.30);

                    float luma(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }

                    // Courbe filmique bon marche (Hejl/Burgess-Dawson) : formule publique largement
                    // diffusee, pas propre a un shaderpack particulier -- comprime les hautes lumieres
                    // sans les ecreter brutalement, ce que fait aussi le tonemap de Complementary,
                    // avec sa propre formule bien plus lourde (voir program/composite5.glsl du
                    // shaderpack, jamais copiee ici).
                    vec3 filmicTonemap(vec3 c) {
                        vec3 x = max(vec3(0.0), c - 0.004);
                        return (x * (6.2 * x + 0.5)) / (x * (6.2 * x + 1.7) + 0.06);
                    }

                    vec3 grade(vec3 c) {
                        c *= 1.05;
                        c = filmicTonemap(c);

                        float l = luma(c);
                        c = mix(vec3(l), c, 1.14);

                        // Virage bicolore "teal & orange" : ombres legerement froides, hautes lumieres
                        // legerement chaudes -- technique generique de retouche cinema, coefficients
                        // propres a ce fichier.
                        vec3 shadowTint = vec3(0.965, 1.0, 1.035);
                        vec3 highTint = vec3(1.06, 1.0, 0.90);
                        c *= mix(shadowTint, highTint, smoothstep(0.05, 0.85, l));

                        c = (c - 0.5) * 1.05 + 0.5;
                        return clamp(c, 0.0, 1.0);
                    }

                    void main() {
                        vec4 gbuf = texture(NormalSampler, texCoord);
                        vec3 n0 = gbuf.rgb;
                        float d0 = gbuf.a;
                        bool sky = d0 < 0.001 || dot(n0, n0) < 0.001;

                        vec3 color = texture(ColorSampler, texCoord).rgb;
                        vec3 bloom = texture(BloomSampler, texCoord).rgb;

                        // --- AO ecran-espace a deux rayons : plus doux que l'unique anneau d'origine,
                        //     toujours sans reconstruction 3D reelle (pas de matrices camera ici). ---
                        float occlusion = 0.0;
                        if (!sky) {
                            vec2 texel = 1.0 / vec2(textureSize(NormalSampler, 0));
                            vec2 kernel[8] = vec2[](
                                vec2( 1.0,  0.0), vec2(-1.0,  0.0), vec2( 0.0,  1.0), vec2( 0.0, -1.0),
                                vec2( 0.7071,  0.7071), vec2(-0.7071,  0.7071),
                                vec2( 0.7071, -0.7071), vec2(-0.7071, -0.7071)
                            );
                            float radii[2] = float[](2.5, 5.5);
                            float ringWeights[2] = float[](0.6, 0.4);
                            for (int ring = 0; ring < 2; ring++) {
                                float ringOcclusion = 0.0;
                                for (int i = 0; i < 8; i++) {
                                    vec2 uv = texCoord + kernel[i] * texel * radii[ring];
                                    vec4 s = texture(NormalSampler, uv);
                                    float depthDelta = s.a - d0;
                                    float agree = max(dot(n0, s.rgb), 0.0);
                                    ringOcclusion += agree * smoothstep(0.0015, 0.02, depthDelta);
                                }
                                occlusion += (ringOcclusion / 8.0) * ringWeights[ring];
                            }
                        }

                        // --- Brume de distance, geometrie reelle seulement (le ciel est deja colore
                        //     par vanilla) : courbe sur la profondeur BRUTE, non lineaire, calibree a
                        //     l'oeil via Snap -- aucune distance 3D vraie n'est disponible ici. ---
                        float fog = sky ? 0.0 : (1.0 - smoothstep(0.0, 0.18, d0));

                        vec3 shaded = mix(color, color * TEINTE_CITROUILLE, occlusion * 0.75);
                        shaded = mix(shaded, FOG_TEINTE * max(luma(shaded), 0.35), fog * 0.32);

                        // Le bloom s'ajoute a TOUS les pixels, ciel compris : c'est lui qui rend le
                        // soleil/la lune/la lave visiblement plus lumineux, sans lui aucun effet de ce
                        // nuancier ne touchait jamais le ciel (l'ancienne version s'arretait net sur
                        // "sky", voir l'historique de ce fichier).
                        shaded += bloom * 0.85;

                        shaded = grade(shaded);

                        float vig = 1.0 - dot(texCoord - 0.5, texCoord - 0.5) * 0.55;
                        shaded *= vig;

                        // Tramage : la courbe filmique et le virage bicolore ci-dessus introduisent un
                        // gradient plus marque qu'une simple teinte d'AO -- sans ce bruit, un ciel de
                        // jour uniforme bande visiblement en bandes de 1-2 niveaux.
                        float dither = fract(sin(dot(texCoord * vec2(1920.0, 1080.0), vec2(12.9898, 78.233))) * 43758.5453);
                        shaded += (dither - 0.5) * (1.0 / 255.0);

                        fragColor = vec4(clamp(shaded, 0.0, 1.0), 1.0);
                    }
                    """);

            // La chaine a six passes : les trois premieres sont EXACTEMENT celles de
            // upscale_aa_moyenne.json (jar principal), rejouees ici pour que club_citrouille se
            // comporte, hors effets propres, comme la chaine par defaut -- pas de regression de
            // nettete/anticrenelage a activer ce nuancier. Les deux suivantes construisent le bloom
            // leger (horizontal puis vertical) a partir de la couleur post-FSR. La sixieme combine
            // tout : AO, brume, bloom, etalonnage.
            //
            // "sampler_name" ci-dessous est TOUJOURS le nom de base ("In", "Color", "Normal",
            // "Bloom"), JAMAIS le nom d'uniforme GLSL complet : PostChain.createPass concatene
            // "Sampler" dessus avant de batir le BindGroupLayout demande au pilote (verifie par
            // javap -v sur PostChain.class -- BootstrapMethods : invokedynamic
            // makeConcatWithConstants, gabarit litteral "Sampler"). Un premier essai de cette passe
            // avait ecrit "InSampler"/"ColorSampler"/"NormalSampler"/"BloomSampler" ici (le nom GLSL
            // complet, deja suffixe) -- plante reel en jeu (ShaderCompileException: Unable to find
            // shader defined uniform) sur la premiere passe de bloom, corrige ici. Voir le Javadoc de
            // classe pour la preuve complete et son historique pour la version fautive.
            Files.writeString(post.resolve("club_citrouille.json"), """
                    {
                      "targets": {
                        "swap": { "persistent": true },
                        "preao": { "persistent": true },
                        "bloomh": { "persistent": true },
                        "bloomv": { "persistent": true }
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
                          "fragment_shader": "lanterne:post/club_citrouille_bloom_h",
                          "inputs": [
                            { "sampler_name": "In", "target": "preao", "bilinear": true }
                          ],
                          "output": "bloomh"
                        },
                        {
                          "vertex_shader": "minecraft:core/screenquad",
                          "fragment_shader": "lanterne:post/club_citrouille_bloom_v",
                          "inputs": [
                            { "sampler_name": "In", "target": "bloomh", "bilinear": true }
                          ],
                          "output": "bloomv"
                        },
                        {
                          "vertex_shader": "minecraft:core/screenquad",
                          "fragment_shader": "lanterne:post/club_citrouille_final",
                          "inputs": [
                            { "sampler_name": "Color", "target": "preao" },
                            { "sampler_name": "Normal", "target": "lanterne:normal" },
                            { "sampler_name": "Bloom", "target": "bloomv" }
                          ],
                          "output": "minecraft:main"
                        }
                      ]
                    }
                    """);

            Lanterne.LOG.info("[NUANCIER] dossier {} cree (Club Citrouille -- AO deux rayons + bloom leger + "
                    + "brume + etalonnage cinematographique) -- pose \"lentille_nuancier_actif = "
                    + "'club_citrouille'\" (et \"lentille = true\") dans lanterne-client.toml pour l'activer", racine);
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
