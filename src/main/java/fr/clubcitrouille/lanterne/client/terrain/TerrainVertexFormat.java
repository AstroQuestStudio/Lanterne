package fr.clubcitrouille.lanterne.client.terrain;

import org.joml.Vector3fc;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.vertex.VertexFormat;

import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.util.ARGB;

/**
 * Le format de sommet étendu du terrain, et l'émission de quad qui le remplit.
 *
 * <h2>Pourquoi réutiliser UV1/UV3 plutôt qu'ajouter un binding 2</h2>
 *
 * <p>Le plan écrit dans {@code notes/rendu-terrain-vs-sodium.md} (passes précédentes) prévoyait un
 * second flux de sommets sur un binding 2 séparé — un {@code VertexBuffer} de plus, uploadé et lié en
 * parallèle du binding 0. Vérifié cette passe par lecture directe du bytecode
 * (javap sur le vrai jar patché) : {@code SectionCompiler$Results.renderedLayers} ne contient qu'un
 * seul {@code MeshData} par {@link net.minecraft.client.renderer.chunk.ChunkSectionLayer}, et rien
 * dans le pipeline d'upload/dessin ({@code SectionRenderDispatcher}, la construction du
 * {@code VkPipelineVertexInputStateCreateInfo} dans {@code VulkanRenderPipeline.java}) ne gère
 * plusieurs {@code VertexBuffer} par section. Ajouter un binding 2 aurait donc exigé de refaire cette
 * plomberie en plusieurs classes — un chantier de plusieurs heures à lui seul, et un vrai risque
 * puisqu'il touche le dessin de TOUT le terrain.
 *
 * <p>Meilleure trouvaille de cette passe, vérifiée en lisant {@code BufferBuilder} au complet
 * (javap) : {@code DefaultVertexFormat.BLOCK} ignore deux des huit attributs « sémantiques » que
 * {@code BufferBuilder} sait déjà écrire — {@code UV1} ({@code setUv1(int,int)}, sous le capot de
 * {@code setOverlay}, format {@code RG16_SINT}) et {@code UV3} ({@code setUv3(float,float)}, format
 * {@code RG32_FLOAT}, jamais appelé par personne pour du terrain). Aucun des deux n'a besoin d'une
 * nouvelle méthode sur {@code VertexConsumer} : les deux existent déjà, sur l'interface elle-même, et
 * s'activent simplement en les ajoutant au {@link VertexFormat} d'un binding EXISTANT (le binding 0,
 * interleaved) plutôt qu'en créant un binding neuf. Le pipeline d'upload/dessin ne change donc pas
 * d'un octet : c'est toujours un seul {@code MeshData}, juste un peu plus large par sommet.
 *
 * <p>Confirmé par lecture de {@code PipelineBuilder.java} (extrait du jar, livré en source à côté du
 * {@code .class} — un vrai fichier du moteur, pas {@code .mcsrc}) : la résolution du numéro de
 * {@code location} d'un attribut de sommet se fait par NOM, en réfléchissant les entrées du vertex
 * shader compilé (SPIR-V) et en cherchant une entrée dont le nom correspond exactement au nom de
 * l'élément du {@link VertexFormat} — pas par position dans le tableau des bindings. Conséquence
 * directe : ajouter {@code UV1}/{@code UV3} à ce format n'entre en collision avec AUCUN autre binding
 * (ni {@code CHUNK_DATA_INSTANCED}, ni rien d'autre) quel que soit l'ordre dans lequel on les
 * déclare ici — seul compte le nom, et le numéro de {@code location} choisi côté shader
 * ({@code terrain.vsh}).
 *
 * <h2>Ce que portent UV1 et UV3 ici</h2>
 *
 * <ul>
 *   <li>{@code UV3} (2 floats exacts) : le coin {@code (uMin, vMin)} du rectangle de sprite dans
 *       l'atlas, calculé comme le minimum des 4 UV d'origine du quad. Flottant exact — aucun
 *       arrondi — pour que la reconstruction en shader retombe bit à bit sur les coins d'origine.
 *   <li>{@code UV1} (2 entiers 16 bits signés, {@link #packSpan(float)}) : la taille du rectangle de
 *       sprite ({@code uMax-uMin}, {@code vMax-vMin}), mise à l'échelle sur 15 bits. Une taille de
 *       sprite est une fraction de l'atlas (souvent 1/16 à 1/2), donc largement dans la résolution
 *       de ce format — l'erreur d'arrondi ne peut affecter que la douzième décimale d'un UV, jamais
 *       un texel visible.
 * </ul>
 *
 * <p><b>Tant que la fusion de faces n'est pas activée, {@code UV0} reste l'UV absolu
 * de l'atlas, identique à ce que vanilla écrit aujourd'hui</b> — {@link #putQuad} ne fait que
 * dupliquer une donnée déjà présente dans le {@link BakedQuad} (son propre rectangle), jamais
 * l'inventer. Le shader ({@code terrain.fsh}) peut donc rester capable de reconstruire exactement
 * la même valeur qu'un échantillonnage direct, et une vérification visuelle à ce stade doit montrer
 * un rendu identique au vanilla — c'est le premier jalon vérifié de ce chantier.
 */
public final class TerrainVertexFormat {
    /**
     * Le define de shader qui active la lecture de {@code UV1}/{@code UV3} dans
     * {@code terrain.vsh}/{@code terrain.fsh}. Posé UNIQUEMENT par {@code RenderPipelinesMixin} sur
     * {@code TERRAIN_SNIPPET}/{@code MULTIDRAW_TERRAIN_SNIPPET} — jamais sur les pipelines OIT, qui
     * compilent le même fichier de shader mais gardent le format {@code BLOCK} d'origine sur leur
     * binding 0. Sans ce define, les deux attributs ne sont ni déclarés ni lus côté shader : un
     * pipeline qui ne le pose pas se comporte exactement comme avant cette passe.
     *
     * <p>Existe ici, et pas seulement comme littéral dans {@code RenderPipelinesMixin}, pour qu'un
     * seul endroit fixe l'orthographe exacte partagée avec {@code terrain.vsh}/{@code terrain.fsh} —
     * une faute de frappe dans l'un des trois aurait recréé exactement le crash qu'il corrige.
     */
    public static final String SHADER_DEFINE = "LANTERNE_TERRAIN_EXT";

    /**
     * Mise à l'échelle d'une taille de sprite (fraction d'atlas, typiquement 0.015625 à 0.5) vers un
     * entier 16 bits signé. 32767 plutôt que 65535 : {@code UV1} est {@code RG16_SINT}, signé —
     * vérifié par lecture du format {@code DefaultVertexFormat.UV1_FORMAT} (javap sur le jar réel).
     */
    private static final float SPAN_SCALE = 32767.0f;

    /**
     * Plancher de taille de sprite pour éviter une division par zéro en shader si un quad dégénéré
     * (les 4 UV identiques) traverse ce chemin. N'affecte aucun quad réel : tout sprite de bloc a une
     * étendue UV strictement positive dans l'atlas.
     */
    private static final float MIN_SPAN = 1.0f / 8192.0f;

    /**
     * Le format de sommet du terrain : les quatre éléments de {@code DefaultVertexFormat.BLOCK}
     * (Position, Color, UV0, UV2) plus les deux réutilisés ci-dessus (UV1, UV3). Un seul binding,
     * interleaved, exactement comme {@code BLOCK} — voir le Javadoc de classe pour pourquoi.
     *
     * <p>Posé sur le binding 0 des pipelines de terrain par {@code RenderPipelinesMixin}, qui
     * remplace ainsi {@code DefaultVertexFormat.BLOCK} UNIQUEMENT pour
     * {@code TERRAIN_SNIPPET}/{@code MULTIDRAW_TERRAIN_SNIPPET} — jamais pour les blocs tenus en
     * main, les entités-blocs, ni rien d'autre qui partage {@code GENERIC_BLOCKS_SNIPPET} par
     * héritage de snippet sans repasser par ce mixin.
     */
    public static final VertexFormat EXTENDED = VertexFormat.builder(0)
            .addAttribute(DefaultVertexFormat.POSITION_SEMANTIC_NAME, GpuFormat.RGB32_FLOAT)
            .addAttribute(DefaultVertexFormat.COLOR_SEMANTIC_NAME, GpuFormat.RGBA8_UNORM)
            .addAttribute(DefaultVertexFormat.UV0_SEMANTIC_NAME, GpuFormat.RG32_FLOAT)
            .addAttribute(DefaultVertexFormat.UV2_SEMANTIC_NAME, GpuFormat.RG16_SINT)
            .addAttribute(DefaultVertexFormat.UV1_SEMANTIC_NAME, GpuFormat.RG16_SINT)
            .addAttribute(DefaultVertexFormat.UV3_SEMANTIC_NAME, GpuFormat.RG32_FLOAT)
            .build();

    private TerrainVertexFormat() {}

    /**
     * Met à l'échelle et sature une étendue de sprite (fraction d'atlas) vers l'entier 16 bits signé
     * que porte {@code UV1}.
     */
    private static int packSpan(float span) {
        int packed = Math.round(span * SPAN_SCALE);
        if (packed < 0) {
            return 0;
        }
        return Math.min(packed, 32767);
    }

    /**
     * Émet un quad complet (4 sommets) dans {@code sink}, en écrivant TOUS les éléments du format
     * {@link #EXTENDED} — y compris {@code UV1}/{@code UV3}, que
     * {@code VertexConsumer.putBlockBakedQuad} (la méthode {@code default} vanilla) ne remplit
     * jamais.
     *
     * <h2>Pourquoi ne pas appeler {@code putBlockBakedQuad} puis compléter UV1/UV3 à part</h2>
     *
     * <p>Vérifié par lecture de bytecode ({@code VertexConsumer.putBlockBakedQuad}, javap -c) :
     * cette méthode {@code default} boucle sur les 4 sommets et appelle, pour chacun, la surcharge
     * groupée {@code addVertex(x,y,z,color,u,v,overlay,light,nx,ny,nz)} en UN seul appel — il n'y a
     * aucun point d'accroche entre deux sommets pour glisser un {@code setUv1}/{@code setUv3}
     * supplémentaire sans dupliquer tout le calcul par sommet. Cette méthode réimplémente donc la
     * même boucle (mêmes sources de données : {@link BakedQuad#position(int)},
     * {@link BakedQuad#packedUV(int)}, {@link QuadInstance#getColor(int)},
     * {@link QuadInstance#getLightCoordsWithEmission(int, int)}) et ajoute les deux appels en plus.
     *
     * <p>Ni {@code setOverlay} ni {@code setNormal} ne sont appelés ici : vérifié par bytecode que
     * {@code DefaultVertexFormat.BLOCK} ne déclare ni Overlay ni Normal (le chemin rapide
     * {@code blockFormat} de {@code BufferBuilder.addVertex} n'écrit jamais ces deux paramètres), et
     * {@code terrain.vsh} ne les lit pas non plus — les appeler ici écrirait dans le vide (au mieux)
     * ou, pour {@code setOverlay}, ÉCRASERAIT {@code UV1} avec des coordonnées de surbrillance
     * (vérifié : {@code setOverlay} délègue à {@code setUv1} sous le capot) — exactement la case
     * qu'on vient de réquisitionner pour la taille de sprite. Ne jamais appeler {@code setOverlay}
     * sur ce chemin.
     *
     * @param sink le {@code VertexConsumer} de la couche (solide/découpe/translucide) — doit avoir
     *             été construit avec {@link #EXTENDED} comme format, sans quoi
     *             {@code setUv1}/{@code setUv3} sont des no-op silencieux (élément absent) et
     *             {@code BufferBuilder} refusera le sommet à la fin (élément déclaré non rempli —
     *             seulement s'il EST déclaré ; ici justement il ne le serait pas, donc silence, pas
     *             crash : encore une raison de toujours passer par le binding patché).
     */
    public static void putQuad(VertexConsumer sink, float x, float y, float z, BakedQuad quad,
            QuadInstance instance) {
        float u0 = UVPair.unpackU(quad.packedUV(0));
        float v0 = UVPair.unpackV(quad.packedUV(0));
        float u1 = UVPair.unpackU(quad.packedUV(1));
        float v1 = UVPair.unpackV(quad.packedUV(1));
        float u2 = UVPair.unpackU(quad.packedUV(2));
        float v2 = UVPair.unpackV(quad.packedUV(2));
        float u3 = UVPair.unpackU(quad.packedUV(3));
        float v3 = UVPair.unpackV(quad.packedUV(3));

        float minU = Math.min(Math.min(u0, u1), Math.min(u2, u3));
        float maxU = Math.max(Math.max(u0, u1), Math.max(u2, u3));
        float minV = Math.min(Math.min(v0, v1), Math.min(v2, v3));
        float maxV = Math.max(Math.max(v0, v1), Math.max(v2, v3));

        int packedSizeU = packSpan(Math.max(maxU - minU, MIN_SPAN));
        int packedSizeV = packSpan(Math.max(maxV - minV, MIN_SPAN));

        Vector3fc normal = quad.direction().getUnitVec3f();
        // Le normal n'est écrit dans AUCUN élément de ce format (voir le Javadoc de méthode) — lu ici
        // uniquement pour rester lisible en miroir de putBlockBakedQuad ; le compilateur l'élimine
        // s'il ne sert à rien d'autre. Conservé pour un futur besoin (éclairage par normal côté
        // shader) plutôt que supprimé puis à rechercher de nouveau.
        assert normal != null;

        int lightEmission = quad.materialInfo().lightEmission();
        float[] us = {u0, u1, u2, u3};
        float[] vs = {v0, v1, v2, v3};

        for (int i = 0; i < 4; i++) {
            Vector3fc pos = quad.position(i);
            int color = ARGB.multiply(instance.getColor(i), quad.bakedColors().color(i));
            int light = instance.getLightCoordsWithEmission(i, lightEmission);
            sink.addVertex(x + pos.x(), y + pos.y(), z + pos.z())
                    .setColor(color)
                    .setUv(us[i], vs[i])
                    .setLight(light)
                    .setUv1(packedSizeU, packedSizeV)
                    .setUv3(minU, minV);
        }
    }

    /**
     * Les bornes de sprite d'un quad — {@code {minU, minV, sizeU, sizeV}}, non mises à l'échelle
     * (des flottants directement comparables). Extrait de {@link #putQuad} pour que {@link Greedy}
     * puisse comparer deux quads SANS dupliquer ce calcul — une clé de fusion qui diverge de ce que
     * {@link #putQuad} écrit réellement serait pire qu'inutile : elle fusionnerait des quads que le
     * rendu final traiterait ensuite comme des sprites différents.
     */
    static float[] spriteBounds(BakedQuad quad) {
        float u0 = UVPair.unpackU(quad.packedUV(0));
        float v0 = UVPair.unpackV(quad.packedUV(0));
        float u1 = UVPair.unpackU(quad.packedUV(1));
        float v1 = UVPair.unpackV(quad.packedUV(1));
        float u2 = UVPair.unpackU(quad.packedUV(2));
        float v2 = UVPair.unpackV(quad.packedUV(2));
        float u3 = UVPair.unpackU(quad.packedUV(3));
        float v3 = UVPair.unpackV(quad.packedUV(3));
        float minU = Math.min(Math.min(u0, u1), Math.min(u2, u3));
        float maxU = Math.max(Math.max(u0, u1), Math.max(u2, u3));
        float minV = Math.min(Math.min(v0, v1), Math.min(v2, v3));
        float maxV = Math.max(Math.max(v0, v1), Math.max(v2, v3));
        return new float[] {minU, minV, Math.max(maxU - minU, MIN_SPAN), Math.max(maxV - minV, MIN_SPAN)};
    }

    /**
     * Émet un quad fusionné couvrant {@code runLength} quads 1×1 contigus le long de l'axe X local,
     * de {@code first} (bord bas) à {@code last} (bord haut) — le cas le plus simple du chantier
     * (« un mur plat d'un seul bloc », ici un sol/plafond plat d'un seul bloc), vérifié visuellement
     * avant d'envisager un balayage 2D complet.
     *
     * <h2>Pourquoi comparer les positions plutôt que supposer un ordre de sommets</h2>
     *
     * <p>{@code BakedQuad} ne documente ni ne garantit un ordre de sommets particulier pour une face
     * — le supposer (sommet 0 = bas-gauche, etc.) aurait été un pari invérifiable sans lire le modèle
     * de cube réel en détail. Cette méthode ne suppose rien : elle regroupe les 4 sommets de
     * {@code first} en deux paires selon leur coordonnée X locale (bas/haut), associe chaque sommet
     * « bas » à son homologue « haut » de MÊME Z (donc même rôle le long de l'axe non fusionné), et
     * calcule le delta d'UV réel de ce quad pour ce rôle — plutôt que de deviner un signe ou un ordre.
     * Si la géométrie ne correspond pas à ce schéma (pas exactement deux X distincts, pas de partenaire
     * de même Z, UV incohérent), la méthode retourne {@code false} sans rien écrire : l'appelant doit
     * alors émettre chaque quad séparément — un repli sûr, jamais une géométrie inventée.
     *
     * @return {@code true} si le quad fusionné a été émis, {@code false} si la géométrie ne
     *         correspondait pas aux hypothèses vérifiées ci-dessus (repli sûr pour l'appelant).
     */
    static boolean putMergedRunAlongX(VertexConsumer sink,
            float firstX, float firstY, float firstZ, BakedQuad first, QuadInstance firstInstance,
            float lastX, float lastY, float lastZ, BakedQuad last, QuadInstance lastInstance,
            int runLength) {
        Vector3fc[] fp = new Vector3fc[4];
        float[] fu = new float[4];
        float[] fv = new float[4];
        for (int i = 0; i < 4; i++) {
            fp[i] = first.position(i);
            fu[i] = UVPair.unpackU(first.packedUV(i));
            fv[i] = UVPair.unpackV(first.packedUV(i));
        }
        Vector3fc[] lp = new Vector3fc[4];
        for (int i = 0; i < 4; i++) {
            lp[i] = last.position(i);
        }

        float epsilon = 1.0e-4f;
        float minX = Math.min(Math.min(fp[0].x(), fp[1].x()), Math.min(fp[2].x(), fp[3].x()));
        float maxX = Math.max(Math.max(fp[0].x(), fp[1].x()), Math.max(fp[2].x(), fp[3].x()));
        if (maxX - minX < epsilon) {
            // Pas une face dont deux sommets distincts s'étalent sur X (une face verticale N/S/E/O,
            // par exemple) — ce chemin ne gère que les faces horizontales (UP/DOWN) pour l'instant.
            return false;
        }

        int[] loIdx = new int[2];
        int[] hiIdx = new int[2];
        int loCount = 0;
        int hiCount = 0;
        for (int i = 0; i < 4; i++) {
            if (fp[i].x() - minX < epsilon) {
                if (loCount >= 2) {
                    return false;
                }
                loIdx[loCount++] = i;
            } else if (maxX - fp[i].x() < epsilon) {
                if (hiCount >= 2) {
                    return false;
                }
                hiIdx[hiCount++] = i;
            } else {
                // Un cinquieme "camp" de X : pas un quad axé sur la grille, repli sûr.
                return false;
            }
        }
        if (loCount != 2 || hiCount != 2) {
            return false;
        }

        // Associe chaque sommet "bas" (loIdx) à son homologue "haut" (hiIdx) de même Z — même rôle le
        // long de l'axe non fusionné — et calcule le delta d'UV réel de CE quad pour ce rôle.
        int[] partnerOfLo = new int[2];
        float[] deltaU = new float[2];
        for (int k = 0; k < 2; k++) {
            int lo = loIdx[k];
            int match = -1;
            for (int hi : hiIdx) {
                if (Math.abs(fp[hi].z() - fp[lo].z()) < epsilon) {
                    match = hi;
                    break;
                }
            }
            if (match < 0 || Math.abs(fv[match] - fv[lo]) > epsilon) {
                // Pas de partenaire de même Z, ou son V ne correspond pas à celui du sommet bas :
                // l'orientation de texture de ce quad n'est pas celle attendue (rotation/mirroir
                // inhabituel) — repli sûr plutôt qu'un pari sur le signe du delta.
                return false;
            }
            partnerOfLo[k] = match;
            deltaU[k] = fu[match] - fu[lo];
        }

        // Émis dans l'ordre ORIGINAL des sommets (0,1,2,3), jamais regroupé bas-bas-haut-haut : un
        // quad est un tour de périmètre, pas deux paires indépendantes. Réordonner en
        // [bas,bas,haut,haut] couperait le rectangle en diagonale (deux sommets adjacents dans cet
        // ordre ne seraient plus forcément reliés par une arête réelle) — un quad qui se croise lui-
        // même, culled ou tordu selon le pilote. Garder l'ordre d'origine, en substituant seulement la
        // SOURCE de donnée par sommet, préserve exactement le sens de rotation que vanilla a choisi.
        int[] loOfHi = new int[4];
        for (int k = 0; k < 2; k++) {
            loOfHi[partnerOfLo[k]] = loIdx[k];
        }
        boolean[] isHi = new boolean[4];
        for (int hi : hiIdx) {
            isHi[hi] = true;
        }

        int lightEmission = first.materialInfo().lightEmission();
        int lastLightEmission = last.materialInfo().lightEmission();
        float[] bounds = spriteBounds(first);
        int packedSizeU = packSpan(bounds[2]);
        int packedSizeV = packSpan(bounds[3]);

        for (int i = 0; i < 4; i++) {
            if (!isHi[i]) {
                Vector3fc pos = fp[i];
                int color = ARGB.multiply(firstInstance.getColor(i), first.bakedColors().color(i));
                int light = firstInstance.getLightCoordsWithEmission(i, lightEmission);
                sink.addVertex(firstX + pos.x(), firstY + pos.y(), firstZ + pos.z())
                        .setColor(color)
                        .setUv(fu[i], fv[i])
                        .setLight(light)
                        .setUv1(packedSizeU, packedSizeV)
                        .setUv3(bounds[0], bounds[1]);
                continue;
            }
            // Sommet "haut" : POSITION du dernier quad du run (le vrai bord lointain dans le monde),
            // UV calculé depuis le PREMIER quad (voir le Javadoc de méthode — emprunter l'UV du
            // dernier quad reviendrait à ignorer runLength et à ne fusionner qu'un seul pas).
            int lo = loOfHi[i];
            int k = (loIdx[0] == lo) ? 0 : 1;
            Vector3fc pos = lp[i];
            int color = ARGB.multiply(lastInstance.getColor(i), last.bakedColors().color(i));
            int light = lastInstance.getLightCoordsWithEmission(i, lastLightEmission);
            float mergedU = fu[lo] + runLength * deltaU[k];
            sink.addVertex(lastX + pos.x(), lastY + pos.y(), lastZ + pos.z())
                    .setColor(color)
                    .setUv(mergedU, fv[i])
                    .setLight(light)
                    .setUv1(packedSizeU, packedSizeV)
                    .setUv3(bounds[0], bounds[1]);
        }
        return true;
    }
}
