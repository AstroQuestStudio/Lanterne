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
 * <p>ADDENDUM sur la généralisation du sommeil aux blocs-entités de mods, demandée puis écartée.
 *
 * <p>Le sommeil à échéance fonctionne pour le four parce qu.on <b>connaît sa logique</b> : un four qui
 * cuit sait combien de ticks il lui reste, et rien ne peut l.interrompre qu.un changement de son
 * inventaire, qu.on surveille. L.échéance est calculable.
 *
 * <p>Pour un bloc-entité venu d.un mod, elle ne l.est pas. On ignore ce qu.il attend, ce qui le
 * réveille, et s.il a seulement un état stable. Deux voies s.offraient, et aucune ne tient :
 *
 * <ul>
 *   <li><b>Deviner l.échéance</b> par introspection — impossible sans connaître le code de chaque
 *       machine, et faux dès qu.un mod fait autrement ;</li>
 *   <li><b>Détecter l.inactivité</b> en comparant son état d.un tick à l.autre — or la seule façon de
 *       lire cet état est de le sérialiser, ce qui coûte <em>plus</em> que le tick qu.on voulait
 *       éviter. L.outil {@code Ledger} le mesure précisément : quelques centaines d.octets écrits par
 *       machine, là où son tick ne fait souvent qu.une poignée de comparaisons.</li>
 * </ul>
 *
 * <p>La généralisation est donc close sur une limite de principe, et non sur un manque de temps :
 * <b>on ne peut pas endormir ce dont on ne sait pas quand il doit se réveiller</b>.
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
