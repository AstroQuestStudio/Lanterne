package fr.clubcitrouille.lanterne.core.network;

import java.util.List;

import com.google.common.base.Preconditions;
import com.velocitypowered.natives.encryption.VelocityCipher;
import com.velocitypowered.natives.util.MoreByteBufUtils;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageDecoder;

/**
 * Déchiffre le flux réseau avec le meilleur chiffreur que la plateforme sait offrir.
 *
 * <p>Repris quasi tel quel de <a href="https://github.com/astei/krypton">Krypton</a>
 * (Andrew Steinborn, LGPL-3.0-only — voir {@code NOTICE.md}). {@code vanilla.CipherBase} déchiffre
 * octet par octet à travers un {@code javax.crypto.Cipher} JCE générique ; {@link VelocityCipher}
 * choisit à l'exécution entre une implémentation native (OpenSSL, via velocity-native) et un repli
 * Java pur si la plateforme n'a pas de binaire disponible — jamais d'échec, seulement un gain
 * variable. C'est {@code core.network.ConnectionEncryption} qui installe ce décodeur à la place de
 * {@code net.minecraft.network.CipherDecoder}, voir sa javadoc pour le point d'accroche exact.
 */
public final class MinecraftCipherDecoder extends MessageToMessageDecoder<ByteBuf> {
    private final VelocityCipher cipher;

    public MinecraftCipherDecoder(VelocityCipher cipher) {
        this.cipher = Preconditions.checkNotNull(cipher, "cipher");
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        ByteBuf compatible = MoreByteBufUtils.ensureCompatible(ctx.alloc(), cipher, in).slice();
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
