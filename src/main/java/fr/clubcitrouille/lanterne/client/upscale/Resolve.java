package fr.clubcitrouille.lanterne.client.upscale;

import java.util.Set;

import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.resource.ResourceHandle;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.resources.Identifier;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * La remontée : la toile réduite redevient une image à la taille de l'écran.
 *
 * <h2>Pourquoi passer par le système de post-traitement du jeu</h2>
 *
 * <p>Le premier réflexe serait de bâtir un {@code RenderPipeline} à la main : un triangle plein
 * écran, deux nuanceurs, un tampon d'uniformes. C'est faisable, et c'est trois fois plus de code —
 * plus la compilation des nuanceurs, plus leur recompilation à chaque rechargement de ressources,
 * plus la gestion du tampon circulaire d'uniformes.
 *
 * <p>Or {@code PostChain} fait déjà tout cela, il est public, il est piloté par des fichiers JSON, et
 * c'est <b>le seul point d'extension de fait</b> qu'offre le rendu de 26.2 — la reconnaissance
 * technique du projet l'avait identifié comme tel avant qu'une ligne ne soit écrite. Il fournit même
 * gratuitement le bloc {@code SamplerInfo}, qui porte la taille de la source et celle de la
 * destination : c'est exactement ce dont EASU a besoin pour connaître son facteur d'agrandissement,
 * et cela évite d'avoir à le lui dire.
 *
 * <h2>Rien n'est mis en cache, et c'est voulu</h2>
 *
 * <p>La chaîne est redemandée à chaque image. L'appel coûte une recherche dans une table de hachage,
 * et il est la seule façon correcte de survivre à un rechargement de ressources : {@code ShaderManager}
 * <b>ferme</b> les chaînes qu'il a compilées et repart d'un cache vide. Une référence gardée entre
 * deux rechargements pointerait sur des nuanceurs détruits, ce qui se verrait à l'écran avant de se
 * voir dans le journal. Vanilla procède exactement ainsi pour le contour des créatures lumineuses.
 */
final class Resolve {
    /**
     * Le nom sous lequel la toile réduite est présentée aux fichiers JSON.
     *
     * <p>Une cible « externe » au sens de {@code PostChain} : la chaîne ne la crée pas, elle la
     * reçoit. C'est ce mécanisme, prévu par vanilla pour le contour des créatures et pour les
     * couches de transparence, qui permet d'y brancher une cible qui n'appartient pas au jeu.
     */
    static final Identifier SCENE = Identifier.fromNamespaceAndPath(Lanterne.ID, "scene");

    /**
     * La cible intermédiaire de l'anticrénelage, elle aussi à la taille réduite.
     *
     * <p>Externe pour la même raison que {@link #SCENE} : une cible interne déclarée dans le JSON
     * prendrait la taille de l'écran, et l'anticrénelage doit travailler <b>avant</b> la remontée.
     */
    static final Identifier AA = Identifier.fromNamespaceAndPath(Lanterne.ID, "aa");

    /**
     * Le G-buffer de normales, produit par {@link Gbuffer} — RGB la normale vue (signée), A la
     * profondeur brute de la même image. Voir le Javadoc de {@link Nuancier} pour le contrat
     * complet à l'attention d'un nuancier tiers, et celui de {@code gbuffer_normal.fsh} pour
     * pourquoi la profondeur voyage dans ce canal plutôt que via {@code use_depth_buffer} sur
     * {@link #SCENE} (qui n'en a pas toujours).
     */
    static final Identifier NORMAL = Identifier.fromNamespaceAndPath(Lanterne.ID, "normal");

    private static final Set<Identifier> ALLOWED = Set.of(PostChain.MAIN_TARGET_ID, SCENE, AA, NORMAL);

    private static final Resolve.Bundle BUNDLE = new Resolve.Bundle();

    private Resolve() {}

    /** La chaîne correspondant à la netteté choisie, ou {@code null} si elle n'a pas pu se charger. */
    static PostChain chain() {
        // La variante anticrénelée n'est demandée que si sa cible existe vraiment : une allocation
        // refusée fait redescendre sur la chaîne sans anticrénelage, et non échouer la remontée.
        boolean aa = Upscale.antialias() && Scene.antialiasTarget() != null;
        Identifier id = Identifier.fromNamespaceAndPath(Lanterne.ID,
                aa ? Upscale.chainPath() : Upscale.edge().path());
        PostChain chain;
        try {
            chain = Minecraft.getInstance().getShaderManager().getPostChain(id, ALLOWED);
        } catch (Throwable problem) {
            Lanterne.LOG.warn("[ÉCHELLE] La chaîne {} a levé une exception au chargement.", id, problem);
            Upscale.fail("la chaîne " + id + " n'a pas pu être compilée");
            return null;
        }
        if (chain == null) {
            Upscale.fail("la chaîne " + id + " est introuvable ou refuse de compiler");
        }
        return chain;
    }

    /**
     * Étale la toile réduite sur la cible principale.
     *
     * @return vrai si la cible principale contient bien l'image du monde
     */
    static boolean run(RenderTarget scene, RenderTarget screen) {
        PostChain chain = chain();
        if (chain == null) {
            return false;
        }
        try {
            FrameGraphBuilder frame = new FrameGraphBuilder();
            BUNDLE.screen = frame.importExternal("main", screen);
            BUNDLE.scene = frame.importExternal("lanterne scène", scene);
            RenderTarget aa = Scene.antialiasTarget();
            if (aa != null) {
                BUNDLE.aa = frame.importExternal("lanterne anticrénelage", aa);
            }
            RenderTarget normal = Scene.normalTarget();
            if (normal != null) {
                BUNDLE.normal = frame.importExternal("lanterne normales", normal);
            }
            chain.addToFrame(frame, screen.width, screen.height, BUNDLE);
            // UNPOOLED et non le bassin du jeu : les cibles intermédiaires des chaînes sont
            // déclarées « persistent » dans les JSON, donc gardées par PostChain lui-même. Il ne
            // reste rien à allouer, et l'allocateur n'est ici que parce que la signature l'exige.
            frame.execute(GraphicsResourceAllocator.UNPOOLED);
            return true;
        } catch (Throwable problem) {
            Lanterne.LOG.warn("[ÉCHELLE] La remontée d'échelle a échoué en cours d'image.", problem);
            Upscale.fail("la remontée a levé une exception à l'exécution");
            return false;
        } finally {
            BUNDLE.screen = null;
            BUNDLE.scene = null;
            BUNDLE.aa = null;
            BUNDLE.normal = null;
        }
    }

    /**
     * Le panier de cibles : ce que la chaîne réclame par son nom, et ce qu'on lui tend.
     *
     * <p>Un seul exemplaire réutilisé d'une image à l'autre, vidé dans le {@code finally} de
     * {@link #run}. Quatre champs et quatre comparaisons de nom : l'équivalent de
     * {@code LevelTargetBundle} de vanilla, bien plus court parce qu'on n'a que quatre cibles
     * possibles au lieu de sept.
     */
    private static final class Bundle implements PostChain.TargetBundle {
        private ResourceHandle<RenderTarget> screen;
        private ResourceHandle<RenderTarget> scene;
        private ResourceHandle<RenderTarget> aa;
        private ResourceHandle<RenderTarget> normal;

        @Override
        public void replace(Identifier id, ResourceHandle<RenderTarget> handle) {
            if (PostChain.MAIN_TARGET_ID.equals(id)) {
                this.screen = handle;
            } else if (SCENE.equals(id)) {
                this.scene = handle;
            } else if (AA.equals(id)) {
                this.aa = handle;
            } else if (NORMAL.equals(id)) {
                this.normal = handle;
            } else {
                throw new IllegalArgumentException("Cible inconnue de la remontée : " + id);
            }
        }

        @Override
        public ResourceHandle<RenderTarget> get(Identifier id) {
            if (PostChain.MAIN_TARGET_ID.equals(id)) {
                return this.screen;
            }
            if (SCENE.equals(id)) {
                return this.scene;
            }
            if (AA.equals(id)) {
                return this.aa;
            }
            return NORMAL.equals(id) ? this.normal : null;
        }
    }
}
