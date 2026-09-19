package fr.clubcitrouille.lanterne.core.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * « Voici le jar que je fais tourner, et son empreinte. » Envoyé une fois à la connexion, si
 * {@code core.Meche} est actif côté serveur. Voir {@code core.Meche} pour le mécanisme complet.
 *
 * <h2>Pourquoi l'empreinte décide, pas le numéro de version</h2>
 *
 * <p>Deux jars peuvent porter le même numéro de version et différer d'un octet — un serveur qui
 * reconstruit sans changer {@code gradle.properties}, par exemple. Comparer les nombres dirait « à
 * jour » à tort. L'empreinte SHA-256 ne se trompe jamais dans ce sens : elle ne dit « à jour » que si
 * les deux fichiers sont, bit à bit, le même fichier. Le numéro de version ne sert plus qu'à l'affichage
 * — au joueur, au journal — jamais à la décision.
 *
 * <h2>SHA-256 entière, pas tronquée</h2>
 *
 * <p>{@code content.painting.Mill.fingerprint} tronque à seize octets : suffisant pour dédupliquer des
 * images, où une collision ne ferait au pire réafficher la mauvaise image. Ici, l'empreinte protège un
 * fichier qui va s'exécuter avec la pleine confiance du joueur — la résistance aux collisions de
 * l'empreinte entière est le bon choix, et son coût (seize octets de plus sur le réseau) est sans
 * rapport avec ce qu'elle protège.
 *
 * <h2>Un protocole qui doit rester lisible par un client ancien</h2>
 *
 * <p>C'est le seul paquet de ce mod dont la raison d'être est justement de parler à un client PAS À
 * JOUR. Si un jour ce format change de façon incompatible, un client resté à une vieille version ne
 * saura plus le décoder — et perdra le seul canal qui pouvait le sortir de là. Ce paquet doit donc
 * rester d'une stabilité au-dessus de celle du reste du protocole réseau de ce mod.
 *
 * @param modId le mod concerné — {@code Lanterne.ID} pour cette version du mécanisme.
 * @param version la chaîne de version, pour l'affichage seul.
 * @param sha256 l'empreinte SHA-256 du jar de référence, trente-deux octets bruts.
 * @param size la taille du jar de référence, en octets.
 */
public record MecheManifest(String modId, String version, byte[] sha256, int size)
        implements CustomPacketPayload {

    /** Longueur maximale acceptée pour un identifiant de mod. */
    public static final int MOD_ID_LIMIT = 32;

    /** Longueur maximale acceptée pour une chaîne de version. */
    public static final int VERSION_LIMIT = 32;

    /** SHA-256 : toujours trente-deux octets. */
    public static final int HASH_LENGTH = 32;

    public static final Type<MecheManifest> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(Lanterne.ID, "meche_manifeste"));

    public static final StreamCodec<RegistryFriendlyByteBuf, MecheManifest> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.stringUtf8(MOD_ID_LIMIT), MecheManifest::modId,
                    ByteBufCodecs.stringUtf8(VERSION_LIMIT), MecheManifest::version,
                    ByteBufCodecs.byteArray(HASH_LENGTH), MecheManifest::sha256,
                    ByteBufCodecs.VAR_INT, MecheManifest::size,
                    MecheManifest::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
