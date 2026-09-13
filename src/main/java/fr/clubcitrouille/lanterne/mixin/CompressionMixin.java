package fr.clubcitrouille.lanterne.mixin;

import java.util.zip.Deflater;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.network.CompressionEncoder;

import fr.clubcitrouille.lanterne.core.Settings;
import fr.clubcitrouille.lanterne.core.Wire;

/**
 * Le niveau de compression des paquets, et rien d'autre.
 *
 * <p>Le raisonnement — et pourquoi le niveau plutôt que LZ4 — est dans {@link Wire}.
 *
 * <h2>Le niveau se pose une fois, à la construction</h2>
 *
 * <p>{@code Deflater.setLevel} en cours de flux est une source d'ennuis : la spécification demande de
 * vider le flux avant de changer de niveau, faute de quoi le résultat dépend de l'implémentation. On
 * le pose donc <b>une fois</b>, au moment où l'encodeur naît, c'est-à-dire à l'établissement de la
 * connexion.
 *
 * <p>Conséquence à assumer : changer ce réglage en cours de partie n'affecte que les connexions
 * suivantes. C'est écrit dans le fichier de configuration.
 *
 * <h2>Ce qu'on ne touche pas</h2>
 *
 * <p>Ni le seuil, ni le format, ni l'algorithme. Un flux zlib de niveau un se décompresse par
 * n'importe quel {@code Inflater} — <b>un client vanilla ne voit aucune différence</b>, et ce module
 * est le seul du mod qui n'ait besoin de rien de l'autre côté.
 */
@Mixin(CompressionEncoder.class)
public abstract class CompressionMixin {
    @Shadow
    @Final
    private Deflater deflater;

    @org.spongepowered.asm.mixin.Unique
    private long lanterne$startedAt;

    /**
     * Taille du paquet <b>avant</b> compression, relevée à l'entrée.
     *
     * <p>Elle ne peut pas être lue à la sortie : {@code encode} consomme le tampon d'entrée, et
     * {@code readableBytes()} y rend alors zéro. Le rapport aurait annoncé un taux de compression de
     * un — un chiffre juste sur une grandeur vide.
     */
    @org.spongepowered.asm.mixin.Unique
    private int lanterne$rawSize;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void lanterne$easeOff(int threshold, CallbackInfo callback) {
        if (Settings.wire()) {
            this.deflater.setLevel(Wire.LEVEL);
        }
    }

    /**
     * Le chronomètre, et pourquoi il est indispensable ici.
     *
     * <p>Ce module agit sur les fils de Netty. Le banc de vitesse, qui chronomètre le tick du serveur,
     * ne le verrait jamais et conclurait « aucun effet mesurable » — sur un module qui en a un. Le
     * relevé porte donc sur la chose elle-même.
     */
    @Inject(method = "encode", at = @At("HEAD"))
    private void lanterne$openStopwatch(ChannelHandlerContext context, ByteBuf uncompressed,
                                        ByteBuf out, CallbackInfo callback) {
        lanterne$rawSize = uncompressed.readableBytes();
        lanterne$startedAt = System.nanoTime();
    }

    @Inject(method = "encode", at = @At("RETURN"))
    private void lanterne$closeStopwatch(ChannelHandlerContext context, ByteBuf uncompressed,
                                         ByteBuf out, CallbackInfo callback) {
        // « out » porte aussi le préfixe de longueur : quelques octets de plus, comptés
        // honnêtement plutôt que retranchés au jugé.
        Wire.note(System.nanoTime() - lanterne$startedAt, lanterne$rawSize, out.readableBytes());
    }
}
