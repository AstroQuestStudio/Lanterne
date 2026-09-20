package fr.clubcitrouille.lanterne.core.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * « Voici tous les mods que ce serveur fait tourner, et leurs versions. » Envoyé une fois à la
 * connexion, à côté de {@link MecheManifest} — voir {@code core.Meche} pour le mécanisme d'ensemble.
 *
 * <h2>Pourquoi une seule chaîne encodée, pas une liste de paires structurée</h2>
 *
 * <p>Une {@code List<Record>} demanderait un {@code StreamCodec} composite pour un besoin qui ne
 * sert, ce soir, qu'à de l'AFFICHAGE côté client — pas à une décision automatique. Une chaîne
 * {@code "modid=version"} par ligne, jointe par {@code \n}, se décode en trois lignes côté client et
 * reste lisible telle quelle dans un journal si besoin de déboguer. Le jour où ce mécanisme doit
 * vraiment agir (pas seulement informer), il vaudra le coup de revenir à une liste structurée.
 *
 * <h2>Pourquoi ce paquet ne déclenche AUCUN téléchargement</h2>
 *
 * <p>{@link MecheManifest} sait donner à un client de quoi vérifier une empreinte SHA-256 avant
 * d'accepter un jar — parce que ce jar vient de NOUS, Lanterne, et que nous savons exactement ce qu'il
 * doit contenir. Ce paquet-ci parle de mods TIERS, dont ce mod ne connaît ni l'auteur, ni la source
 * de confiance, ni une empreinte de référence à vérifier. Télécharger et écrire dans {@code mods/}
 * un jar dont on ne sait rien reviendrait à faire confiance à un simple nom et un numéro de version —
 * exactement le genre de faille qu'une vérification par empreinte existe pour éviter ailleurs dans ce
 * mécanisme. Ce paquet se contente donc de DIRE au joueur ce qui manque ou diffère ; c'est à lui
 * d'aller chercher le mod, comme il l'a fait pour tous les autres.
 *
 * @param encoded une ligne {@code modid=version} par mod chargé côté serveur, jointes par {@code \n}.
 */
public record MechePeerList(String encoded) implements CustomPacketPayload {

    /**
     * Longueur maximale acceptée, en caractères UTF-8. Un modpack chargé de deux cents mods à des
     * noms longs tiendrait large dans cette marge ; au-delà, quelque chose d'anormal se passe et
     * mieux vaut refuser proprement que d'allouer sans borne côté client.
     */
    public static final int ENCODED_LIMIT = 16384;

    public static final Type<MechePeerList> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(Lanterne.ID, "meche_pairs"));

    public static final StreamCodec<RegistryFriendlyByteBuf, MechePeerList> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.stringUtf8(ENCODED_LIMIT), MechePeerList::encoded,
                    MechePeerList::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
