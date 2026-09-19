package fr.clubcitrouille.lanterne.mixin;

import com.llamalad7.mixinextras.sugar.Local;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import fr.clubcitrouille.lanterne.core.Leurre;

/**
 * Les deux prises de l'anti x-ray, posées sur la sérialisation réelle d'un chunk.
 *
 * <p>Tout le raisonnement — le principe, la mesure, la tension avec AutoMiner — vit dans {@link
 * Leurre}. Ce fichier ne fait que substituer, aux deux endroits où {@code
 * ClientboundLevelChunkPacketData} lit une section, celle que {@link Leurre#voile} décide d'envoyer.
 *
 * <h2>Pourquoi deux prises, et pourquoi elles doivent rendre le même résultat</h2>
 *
 * <p>Vérifié par {@code javap -p -c -constants} sur {@code ClientboundLevelChunkPacketData.class} du
 * jar patché 26.3 : le constructeur {@code ClientboundLevelChunkPacketData(LevelChunk)} appelle
 * d'abord {@code calculateChunkSize(LevelChunk)} — qui additionne {@code
 * LevelChunkSection.getSerializedSize()} pour dimensionner le tampon — <b>puis</b> {@code
 * extractChunkData(FriendlyByteBuf, LevelChunk)} — qui appelle {@code LevelChunkSection.write(buf)}
 * pour le remplir. {@code extractChunkData} vérifie ensuite que {@code writerIndex() ==
 * capacity()} et lève une {@code IllegalStateException} sinon (même désassemblage). Servir la vraie
 * section à l'une et une doublure à l'autre romprait cette égalité pour toute section réellement
 * obfusquée. {@link Leurre#voile} met donc son résultat en cache par section : les deux prises
 * voient toujours exactement le même objet pour le même chunk.
 *
 * <h2>Sur {@code chunk}, capturé sans ambiguïté</h2>
 *
 * <p>Les deux méthodes visées ont chacune UN SEUL paramètre de type {@code LevelChunk} — {@code
 * calculateChunkSize} n'en a qu'un, {@code extractChunkData} en a deux dont un seul de ce type.
 * {@code @Local(argsOnly = true)} le capture donc par type sans ordinal, même principe que {@code
 * LoterieMixin} (voir sa Javadoc pour pourquoi capturer par ordinal parmi plusieurs locales du même
 * type serait risqué sans table de variables locales — pas le cas ici, un seul candidat existe).
 */
@Mixin(ClientboundLevelChunkPacketData.class)
public abstract class LeurreEmballageMixin {

    @Redirect(method = "calculateChunkSize",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/chunk/LevelChunkSection;getSerializedSize()I"))
    private static int lanterne$sizeOfVeiled(LevelChunkSection section,
            @Local(argsOnly = true) LevelChunk chunk) {
        return Leurre.voile(chunk, section).getSerializedSize();
    }

    @Redirect(method = "extractChunkData",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/chunk/LevelChunkSection;"
                            + "write(Lnet/minecraft/network/FriendlyByteBuf;)V"))
    private static void lanterne$writeVeiled(LevelChunkSection section, FriendlyByteBuf buffer,
            @Local(argsOnly = true) LevelChunk chunk) {
        Leurre.voile(chunk, section).write(buffer);
    }
}
