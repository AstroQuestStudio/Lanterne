package fr.clubcitrouille.lanterne.mixin;

import java.security.GeneralSecurityException;

import javax.crypto.SecretKey;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import com.velocitypowered.natives.encryption.VelocityCipher;
import com.velocitypowered.natives.util.Natives;

import io.netty.channel.Channel;
import net.minecraft.network.Connection;

import fr.clubcitrouille.lanterne.core.network.ConnectionEncryption;
import fr.clubcitrouille.lanterne.core.network.MinecraftCipherDecoder;
import fr.clubcitrouille.lanterne.core.network.MinecraftCipherEncoder;

/**
 * Le même pipeline que vanilla — mêmes noms de maillon, même ordre — avec le chiffreur de Krypton
 * à la place de {@code javax.crypto.Cipher}.
 *
 * <p>{@code Connection.setEncryptionKey} installe {@code CipherDecoder}/{@code CipherEncoder} par
 * {@code pipeline().addBefore("splitter", "decrypt", ...)} et {@code addBefore("prepender",
 * "encrypt", ...)} — vérifié dans les sources décompilées de la 26.3. On garde exactement ces noms
 * de maillon : rien d'autre dans le pipeline Netty n'a de raison de savoir qu'on a changé de
 * chiffreur.
 */
@Mixin(Connection.class)
public abstract class ConnectionMixin implements ConnectionEncryption {
    @Shadow
    private Channel channel;

    @Unique
    private boolean lanterne$encryptionInstalled;

    @Override
    public void setupEncryption(SecretKey key) throws GeneralSecurityException {
        if (this.lanterne$encryptionInstalled) {
            return; // deux appels reviendraient à chiffrer deux fois le même flux
        }
        VelocityCipher decryption = Natives.cipher.get().forDecryption(key);
        VelocityCipher encryption = Natives.cipher.get().forEncryption(key);
        this.channel.pipeline().addBefore("splitter", "decrypt", new MinecraftCipherDecoder(decryption));
        this.channel.pipeline().addBefore("prepender", "encrypt", new MinecraftCipherEncoder(encryption));
        this.lanterne$encryptionInstalled = true;
    }
}
