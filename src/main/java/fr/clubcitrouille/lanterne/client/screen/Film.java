package fr.clubcitrouille.lanterne.client.screen;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;

import java.nio.ByteBuffer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * La pellicule : une texture de carte graphique, par écran qui joue.
 *
 * <h2>Une par écran, alors que les tableaux en partagent une seule</h2>
 *
 * <p>{@code content.painting.Mosaic} range toutes les images des tableaux dans <b>une</b> texture, et
 * sa note de classe explique longuement pourquoi : un seul type de rendu, donc un seul lot de dessin,
 * donc un seul changement d'état pour deux cents tableaux. C'est le bon choix là-bas, et le mauvais
 * ici. La raison tient en un mot : <b>le mouvement</b>.
 *
 * <p>Un tableau est écrit une fois dans sa vie. Une vidéo réécrit sa zone trente fois par seconde.
 * Dans une mosaïque partagée, chaque écriture toucherait la texture que tous les autres écrans sont
 * en train de lire — le pilote sérialiserait, et huit écrans se bloqueraient mutuellement au lieu de
 * travailler en parallèle. Le gain de lot serait payé plusieurs fois en attente.
 *
 * <p>Une texture par écran coûte un changement d'état par écran. Avec un plafond de quatre écrans
 * actifs — voir {@link Consent#activeCap()} — c'est quatre changements par image, c'est-à-dire rien.
 *
 * <h2>Ce que coûte vraiment un écran, en mémoire vidéo</h2>
 *
 * <p>Une pellicule de 1280 × 720 en RGBA occupe <b>3,5 mébioctets</b>. Quatre écrans actifs en
 * occupent quatorze. C'est peu, et c'est surtout <b>borné</b> : un mur de deux cent cinquante-six
 * blocs coûte la même chose qu'un mur d'un seul, parce que la résolution de la vidéo ne dépend pas de
 * la taille du mur. Elle n'est agrandie qu'à l'affichage, par le matériel, gratuitement.
 *
 * <p>Il n'y a volontairement <b>pas de chaîne de réductions</b>. Elle devrait être recalculée à chaque
 * image — soit un tiers de travail en plus, trente fois par seconde, pour améliorer un cas qui
 * n'existe pas : on ne regarde pas un film de si loin que le filtrage compte, et au-delà de
 * {@link Consent#distance()} l'écran cesse de décoder de toute façon.
 *
 * <h2>Le fil de rendu, et pas un autre</h2>
 *
 * <p>Tout ce fichier doit être appelé depuis le fil de rendu. Un téléversement depuis un fil de fond
 * produit un plantage rare, sans trace utile, et introuvable — c'est écrit noir sur blanc dans
 * {@code Mosaic.lay} et cela s'applique mot pour mot ici. Le <b>décodage</b>, lui, appartient à un
 * fil de fond : c'est au moteur de faire traverser la frontière à ses images, pas à cette classe.
 */
public final class Film extends AbstractTexture {
    private final Identifier id;
    private final int width;
    private final int height;

    private Film(Identifier id, int width, int height) {
        this.id = id;
        this.width = width;
        this.height = height;

        GpuDevice device = RenderSystem.getDevice();
        this.texture = device.createTexture(() -> "lanterne:" + id.getPath(),
                GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
                GpuFormat.RGBA8_UNORM, width, height, 1, 1);
        this.textureView = device.createTextureView(this.texture);
        // Filtrage linéaire, bords rabattus, et le dernier paramètre à faux : pas de chaîne de
        // réductions, donc pas de borne de niveau de détail à lever. Voir l'en-tête pour pourquoi
        // il n'y en a pas.
        this.sampler = RenderSystem.getSamplerCache().getSampler(
                AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
                FilterMode.LINEAR, FilterMode.LINEAR, false);
        Minecraft.getInstance().getTextureManager().register(id, this);
    }

    /**
     * Alloue une pellicule pour l'écran de cette position.
     *
     * <p>L'identifiant dérive de la position du bloc : deux écrans ne peuvent pas se le disputer, et
     * un écran rechargé retrouve le sien. Un compteur incrémental aurait donné des identifiants
     * différents à chaque chargement de chunk, et le gestionnaire de textures aurait accumulé des
     * entrées mortes jusqu'à la fin de la partie.
     *
     * @return la pellicule, ou {@code null} si la carte graphique l'a refusée. L'appelant doit
     *     traiter ce cas — c'est de la mémoire vidéo, et elle peut manquer.
     */
    public static Film reserve(BlockPos pos, int width, int height) {
        try {
            Identifier id = Identifier.fromNamespaceAndPath(Lanterne.ID,
                    "pellicule/" + pos.getX() + "_" + pos.getY() + "_" + pos.getZ());
            return new Film(id, Math.max(1, width), Math.max(1, height));
        } catch (Throwable refused) {
            Lanterne.LOG.warn("[PROJECTION] pellicule {}×{} refusée par la carte graphique.",
                    width, height, refused);
            return null;
        }
    }

    /** L'identifiant sous lequel le rendu demandera cette texture. */
    public Identifier id() {
        return this.id;
    }

    public int width() {
        return this.width;
    }

    public int height() {
        return this.height;
    }

    /**
     * Écrit une image entière.
     *
     * <p>L'image est <b>consommée</b> : elle est fermée avant le retour, succès ou non. C'est la
     * convention de {@code Mosaic.lay}, et c'est la plus sûre quand le propriétaire d'un tampon natif
     * change de main — aucun chemin d'erreur ne peut alors faire fuir de la mémoire hors du tas.
     *
     * <p>Une image entière et non des rectangles partiels : un décodeur vidéo produit des images
     * complètes, et la notion de « zone sale » n'appartient qu'aux navigateurs, qui repeignent des
     * pages. Le jour où {@link Chrome} sera branché, c'est lui qui devra composer ses rectangles
     * avant d'arriver ici.
     */
    public void write(NativeImage image) {
        try {
            if (this.texture == null || this.texture.isClosed()) {
                return;
            }
            if (image.getWidth() != this.width || image.getHeight() != this.height) {
                // Une image d'une autre taille que la texture déborderait ou laisserait une bande
                // non écrite, c'est-à-dire de la mémoire vidéo non initialisée affichée à l'écran.
                // Le moteur doit réserver une pellicule à la taille de son flux, et la rendre quand
                // le flux change de taille.
                return;
            }
            RenderSystem.getDevice().createCommandEncoder()
                    .writeToTexture(this.texture, image, 0, 0, 0, 0);
        } finally {
            image.close();
        }
    }

    /**
     * Écrit une image depuis un tampon brut.
     *
     * <h2>Pourquoi cette variante et pas celle qui prend une {@code NativeImage}</h2>
     *
     * <p>Le décodeur rend des octets natifs. Les faire passer par une {@code NativeImage} imposerait
     * une recopie de trois mébioctets et demi par image — cent mébioctets par seconde à trente
     * images — pour ne rien gagner : la texture attend exactement la même disposition.
     *
     * <p>En 26.2, {@code writeToTexture} a deux familles. Celle qui prend une {@code NativeImage}
     * écrit l'image entière à un décalage ; celle qui prend un {@code ByteBuffer} prend en plus une
     * largeur et une hauteur explicites — huit arguments — et c'est la seule qui accepte des octets
     * qu'on n'a pas enveloppés. Le tampon doit être <b>direct</b> : {@code Pump} les alloue ainsi.
     */
    public void write(ByteBuffer pixels, int sourceWidth, int sourceHeight) {
        if (this.texture == null || this.texture.isClosed()) {
            return;
        }
        if (sourceWidth != this.width || sourceHeight != this.height) {
            // Le flux a changé de taille — un changement de qualité, ou une source qui bascule de
            // résolution en cours de route. Écrire quand même laisserait une bande de mémoire vidéo
            // non initialisée à l'écran. C'est à l'appelant de rendre cette pellicule et d'en
            // réserver une autre ; voir Gaze.
            return;
        }
        RenderSystem.getDevice().createCommandEncoder()
                .writeToTexture(this.texture, pixels, 0, 0, 0, 0, this.width, this.height);
    }

    /**
     * Rend la pellicule au système.
     *
     * <p>Le retrait du gestionnaire de textures compte autant que la fermeture. Sans lui, une texture
     * fermée resterait inscrite sous notre identifiant, et le premier code qui la demanderait
     * recevrait un objet mort au lieu d'une absence — la leçon vient de {@code Mosaic.dispose}, où
     * elle avait coûté une saturation de mémoire vidéo après quelques allers-retours au menu.
     */
    public void release() {
        Minecraft.getInstance().getTextureManager().release(this.id);
        close();
    }
}
