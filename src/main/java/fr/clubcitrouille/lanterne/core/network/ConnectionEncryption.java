package fr.clubcitrouille.lanterne.core.network;

import java.security.GeneralSecurityException;

import javax.crypto.SecretKey;

/**
 * Installe le chiffrement Krypton sur une {@code net.minecraft.network.Connection}.
 *
 * <p>Vanilla construit ses deux {@code javax.crypto.Cipher} avant d'appeler
 * {@code Connection.setEncryptionKey(Cipher, Cipher)} — et un {@code Cipher} JCE déjà initialisé ne
 * rend plus la clé qui l'a construit, condition pourtant nécessaire à {@link
 * com.velocitypowered.natives.util.Natives#cipher}. Il faut donc intercepter <em>avant</em>, tant
 * que la {@link SecretKey} brute est encore en main — voir {@code mixin/ConnectionMixin} pour
 * l'implémentation, et {@code mixin/ServerLoginPacketListenerImplMixin} pour le point d'appel côté
 * serveur, seul câblé pour l'instant (voir sa javadoc pour pourquoi le client attend).
 */
public interface ConnectionEncryption {
    void setupEncryption(SecretKey key) throws GeneralSecurityException;
}
