package fr.clubcitrouille.lanterne.core;

import java.util.Locale;

/**
 * La compression des paquets, ramenée de six à un.
 *
 * <h2>Ce que vanilla fait à chaque paquet</h2>
 *
 * <pre>
 * public CompressionEncoder(int threshold) {
 *     this.deflater = new Deflater();          // niveau par defaut = -1, soit SIX
 * }
 * </pre>
 *
 * <p>Deflate niveau six sur <b>chaque paquet de chunk</b>. Un joueur qui se connecte avec trente-deux
 * chunks de vue en reçoit quatre cent quarante et un d'affilée, chacun compressé au niveau six. Sur un
 * hébergement à un cœur, les fils réseau ne s'exécutent pas « à côté » du fil du serveur : ils lui
 * disputent le même processeur, et ce gel-là se voit.
 *
 * <h2>Pourquoi le niveau, et non LZ4</h2>
 *
 * <p>Remplacer l'algorithme par LZ4 était le plan, et il était moins bon. Il aurait exigé que les deux
 * bouts s'entendent : une négociation à la poignée de main, un repli si le client ne l'a pas, et un
 * protocole de plus à maintenir. Surtout, il aurait rendu ce serveur <b>inaccessible à un client
 * vanilla</b>.
 *
 * <p>Le niveau de compression, lui, ne change <b>rien au format</b>. Un flux zlib produit au niveau un
 * se décompresse par n'importe quel {@code Inflater}, sans que celui-ci ait à le savoir : le niveau
 * est une décision de l'émetteur, pas une propriété du flux. Aucune négociation, aucun repli, aucun
 * client à modifier — et ce mod peut rester côté serveur seul pour ce module-là.
 *
 * <p>Le prix est connu et modeste : le niveau un produit des paquets d'environ dix à quinze pour cent
 * plus gros, et les compresse trois à cinq fois plus vite. C'est exactement l'échange que ce projet a
 * déjà fait pour la sauvegarde sur disque — <em>« la ROM on s'en fout, la RAM et le CPU c'est la
 * priorité »</em> — et il vaut encore davantage ici : un paquet plus gros coûte de la bande passante,
 * qu'on a ; une compression plus lente coûte du temps de cœur, qu'on n'a pas.
 *
 * <h2>Pourquoi ce module ne se mesure pas au banc de vitesse</h2>
 *
 * <p>La compression a lieu sur les fils de Netty, pas sur le fil du serveur. Le banc, qui chronomètre
 * le tick, ne la verrait donc <b>jamais</b> — et conclurait « aucun effet » sur un module qui en a un.
 *
 * <p>On mesure donc la chose elle-même : le temps passé à compresser, les octets entrés, les octets
 * sortis. C'est la seule grandeur que ce module change, et la seule qu'il ait le droit d'afficher.
 */
public final class Wire {
    /**
     * Le niveau retenu.
     *
     * <p>Un, et non zéro : zéro désactiverait la compression, ce qui multiplierait par cinq le volume
     * d'un paquet de chunk. Un compresse encore très bien des données aussi répétitives qu'un terrain,
     * pour une fraction du temps.
     */
    public static final int LEVEL = 1;

    private static long packets;
    private static long nanos;
    private static long rawBytes;
    private static long sentBytes;

    private Wire() {}

    /** Relève une compression. Appelé depuis un fil de Netty, d'où les compteurs atomiques. */
    public static synchronized void note(long elapsed, int before, int after) {
        packets++;
        nanos += elapsed;
        rawBytes += before;
        sentBytes += after;
    }

    public static long packets() {
        return packets;
    }

    /** Millisecondes passées à compresser depuis le démarrage. */
    public static double millis() {
        return nanos / 1_000_000d;
    }

    /** Ce que la compression a rendu : 4,0 veut dire « quatre fois plus petit ». */
    public static double ratio() {
        return sentBytes == 0L ? 0d : (double) rawBytes / sentBytes;
    }

    public static String describe() {
        if (packets == 0L) {
            return "aucun paquet compressé";
        }
        return String.format(Locale.ROOT,
                "%d paquet(s) · %.1f ms de compression · %.2f Mo -> %.2f Mo (÷%.2f) · %.1f µs/paquet",
                packets, millis(), rawBytes / 1048576d, sentBytes / 1048576d, ratio(),
                nanos / 1000d / packets);
    }

    public static void reset() {
        packets = 0L;
        nanos = 0L;
        rawBytes = 0L;
        sentBytes = 0L;
    }
}
