package fr.clubcitrouille.lanterne.core.network;

import java.util.List;

import com.google.common.base.Preconditions;
import com.velocitypowered.natives.encryption.VelocityCipher;
import com.velocitypowered.natives.util.MoreByteBufUtils;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageEncoder;

/**
 * Chiffre le flux réseau — le pendant de {@link MinecraftCipherDecoder}, même origine, même
 * raison d'être. Voir sa javadoc.
 */
public final class MinecraftCipherEncoder extends MessageToMessageEncoder<ByteBuf> {
    private final VelocityCipher cipher;

    public MinecraftCipherEncoder(VelocityCipher cipher) {
        this.cipher = Preconditions.checkNotNull(cipher, "cipher");
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, ByteBuf msg, List<Object> out) throws Exception {
        ByteBuf compatible = MoreByteBufUtils.ensureCompatible(ctx.alloc(), cipher, msg);
        try {
            cipher.process(compatible);
            out.add(compatible);
        } catch (Exception e) {
            compatible.release(); // jamais utilisé si l'exception remonte
            throw e;
        }
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        cipher.close();
    }
}
