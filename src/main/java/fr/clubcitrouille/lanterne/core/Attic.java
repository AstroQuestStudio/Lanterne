package fr.clubcitrouille.lanterne.core;

import net.minecraft.world.level.chunk.storage.RegionFileVersion;

/**
 * La compression des sauvegardes, choisie pour la vitesse.
 *
 * <h2>Un compromis mesuré, puis tranché</h2>
 *
 * <p>Le banc de sauvegarde a chiffré les deux compressions natives sur la même machine, le même monde
 * et des grilles neuves :
 *
 * <pre>
 * deflate : 153,33 ms pour 64 chunks   ·   24,3 Ko par chunk
 * lz4     :  41,75 ms pour 64 chunks   ·   30,0 Ko par chunk
 * </pre>
 *
 * <p><b>Trois fois et demie plus rapide, vingt-trois pour cent plus volumineux.</b> Aucune ne gagne
 * sur les deux tableaux, et le chiffre seul ne décide pas : un serveur au disque saturé choisira
 * autrement qu'un serveur qui bégaie à chaque sauvegarde automatique.
 *
 * <p>Ce mod tranche pour la vitesse, parce que c'est ce qu'un joueur ressent. Une sauvegarde
 * automatique qui prend cent cinquante millisecondes au lieu de quarante, c'est un à-coup toutes les
 * cinq minutes ; vingt-trois pour cent d'un monde, c'est quelques gigaoctets qui ne gênent personne.
 *
 * <h2>Ce qu'il a fallu renoncer à faire, et pourquoi</h2>
 *
 * <p>Le format stocke l'identifiant de compression <b>par chunk</b>, pas par fichier. Un même fichier
 * peut donc mélanger les deux, et l'idée s'imposait : écrire en compact ce qu'on n'écrit qu'une fois
 * — les chunks traversés puis abandonnés, l'immense majorité d'un monde — et en rapide ce qu'on
 * réécrit sans cesse, c'est-à-dire les chunks habités que la sauvegarde périodique visite.
 *
 * <p>Elle a été écrite, puis abandonnée pour un danger précis. Le champ de compression est lu
 * <b>deux fois</b> par écriture de chunk, à deux endroits :
 *
 * <pre>
 * new DataOutputStream(this.version.wrap(new ChunkBuffer(pos)))   // la compression appliquée
 * super.write(RegionFile.this.version.getId());                   // l'identifiant annoncé
 * </pre>
 *
 * <p>N'en dévier qu'une produirait un chunk dont l'identifiant ne correspond pas à son contenu :
 * <b>illisible</b>, c'est-à-dire une perte de données sur le monde d'un joueur. Les tenir d'accord
 * demandait une variable de fil et deux mixins dont l'un ne devait jamais décider seul.
 *
 * <p>Forcer une compression unique ne touche qu'<b>un seul point</b>, et les deux lectures restent
 * naturellement d'accord puisqu'elles lisent le même champ. La solution simple n'est pas ici un pis-
 * aller : c'est la seule qui ne puisse pas corrompre un monde.
 *
 * <h2>Un monde déjà écrit reste lisible</h2>
 *
 * <p>Changer ce réglage ne réécrit rien et ne casse rien. La lecture n'utilise pas ce choix — elle lit
 * l'identifiant que porte chaque chunk :
 *
 * <pre>
 * byte versionId = buffer.get();
 * RegionFileVersion version = RegionFileVersion.fromId(versionId);
 * </pre>
 *
 * <p>Un monde écrit en deflate pendant des mois continue donc de se relire, chunk par chunk, pendant
 * que les nouvelles écritures passent en lz4. La bascule se fait d'elle-même, sans conversion, sans
 * interruption, et elle est réversible : {@code LANTERNE_MODULES} sans {@code save} rend la main au
 * réglage du serveur.
 */
public final class Attic {
    /** Écritures de chunk passées en compression rapide. */
    private static long hastened;

    private Attic() {}

    /** La compression que ce mod impose, quand son module est actif. */
    public static RegionFileVersion preferred() {
        hastened++;
        return RegionFileVersion.VERSION_LZ4;
    }

    public static long hastened() {
        return hastened;
    }

    public static void reset() {
        hastened = 0L;
    }
}
