package fr.clubcitrouille.lanterne.mixin;

import java.security.GeneralSecurityException;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;

import net.minecraft.network.Connection;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;

import fr.clubcitrouille.lanterne.core.network.ConnectionEncryption;

/**
 * Détourne l'installation du chiffrement côté serveur vers Krypton.
 *
 * <p>{@code handleKey} construit encore les deux {@code Cipher} vanilla — {@code Crypt.getCipher}
 * n'est pas touché, {@code CryptException} continue de se déclencher exactement comme avant — seul
 * l'appel final à {@code connection.setEncryptionKey(decryptCipher, encryptCipher)} est court-
 * circuité, pendant que {@code secretKey} est encore une variable locale de cette méthode :
 * {@code ConnectionEncryption} en a besoin brute, un {@code Cipher} déjà construit ne la rend plus.
 * {@code handleKey} n'attrape que {@code CryptException} : {@link GeneralSecurityException} est
 * donc convertie ici même en {@code IllegalStateException}, exactement comme la méthode le fait déjà
 * pour son propre échec de chiffrement, plutôt que de lui faire porter un type qu'elle ne déclare pas.
 *
 * <p>Ne couvre que le serveur — c'est la cible réelle du gain (MineStrator, mono-cœur). Le client
 * appelle {@code setEncryptionKey} depuis une lambda de {@code ClientHandshakePacketListenerImpl},
 * hors de portée de {@code secretKey} sans un relais entre deux méthodes ; laissé de côté plutôt que
 * bâclé, voir NOTICE.md.
 */
@Mixin(ServerLoginPacketListenerImpl.class)
public abstract class ServerLoginPacketListenerImplMixin {
    @WrapOperation(
            method = "handleKey",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/network/Connection;setEncryptionKey(Ljavax/crypto/Cipher;Ljavax/crypto/Cipher;)V"))
    private void lanterne$fastCipher(Connection connection, Cipher decryptCipher, Cipher encryptCipher,
            Operation<Void> original, @Local SecretKey secretKey) {
        try {
            ((ConnectionEncryption) connection).setupEncryption(secretKey);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Protocol error", e);
        }
    }
}
