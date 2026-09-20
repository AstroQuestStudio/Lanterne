package fr.clubcitrouille.lanterne.core;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * La grêle : sous une averse de particules identiques, on ne compte plus chaque grêlon.
 *
 * <h2>Ce que le jeu envoie, vérifié au bytecode sur ce moteur</h2>
 *
 * <p>{@code javap -p -c -constants} sur {@code Player.class} du jar 26.3 patché montre que
 * {@code damageStatsAndHearts(Entity, float)} — appelée par {@code attack(Entity)} à chaque coup
 * porté — construit et envoie ainsi la particule d'indicateur de dégâts :
 *
 * <pre>
 * if (level() instanceof ServerLevel server &amp;&amp; damage &gt; 2.0f) {
 *     int count = (int) (damage * 0.5f);
 *     server.sendParticles(ParticleTypes.DAMAGE_INDICATOR, x, y, z, count, 0.1, 0, 0.1, 0.2);
 * }
 * </pre>
 *
 * <p>{@code ServerLevel.sendParticles} ne se contente pas de compter : elle construit et envoie un
 * paquet à chaque joueur proche, PAR APPEL. Le nombre de particules n'est donc pas seulement un coût
 * de rendu chez celui qui regarde — c'est un coût de construction de paquet côté serveur,
 * proportionnel aux dégâts infligés, à chaque coup, pour chaque joueur spectateur.
 *
 * <h2>Où ça compte : le combat de masse, pas le duel</h2>
 *
 * <p>Un coup isolé à 8 points de dégâts envoie 4 particules — rien. Une arme de zone qui touche
 * vingt cibles d'un coup, ou un dégât amplifié par une potion/enchantement qui porte le calcul à
 * cent points, envoie cent particules identiques massées au même endroit — et vanilla ne plafonne
 * jamais ce nombre. Aucun ticket Mojira ne couvre ce point précis, mais c'est exactement le constat
 * du mod de référence sur ce sujet (FOOLSIX/ldip, GPL-3.0, jamais chargé ni copié — reconstruit
 * depuis le raisonnement, ce moteur ayant renommé et restructuré la méthode qui le porte).
 *
 * <h2>Pourquoi un plafond, et pas une suppression</h2>
 *
 * <p>Au-delà de quelques dizaines de particules superposées au même point, chacune de plus est
 * invisible dans le nuage qu'elle rejoint — mais le paquet réseau qui la porte, lui, ne l'est pas.
 * Plafonner rend donc le même résultat à l'œil pour un coût borné, au lieu d'un coût qui grandit
 * sans fin avec les dégâts. Le plancher que vanilla pose déjà — jamais de particule sous 2 points
 * de dégâts — n'est pas touché : ce module change un volume, jamais la présence ou l'absence du
 * retour visuel qu'un joueur attend d'un coup qui porte.
 *
 * <h2>Appelant unique : le fil du serveur</h2>
 *
 * <p>Le point d'accroche est DANS la branche {@code instanceof ServerLevel} déjà présente dans le
 * bytecode du jeu : ce module ne s'exécute donc, par construction, jamais côté client, sans qu'il
 * soit nécessaire de le vérifier une seconde fois ici.
 */
public final class Grele {
    private Grele() {}

    /** Particules envoyées par appel, au-delà duquel on plafonne. */
    private static int cap = 40;

    private static final AtomicLong TRIMMED_CALLS = new AtomicLong();
    private static final AtomicLong PARTICLES_SAVED = new AtomicLong();

    /**
     * Le nombre de particules à envoyer réellement, pour un appel qui en demandait {@code requested}.
     *
     * <p>Jamais nul si {@code requested} ne l'était pas : voir la note de classe sur le plancher.
     */
    public static int cap(int requested) {
        if (requested <= cap) {
            return requested;
        }
        TRIMMED_CALLS.incrementAndGet();
        PARTICLES_SAVED.addAndGet(requested - cap);
        return cap;
    }

    public static void setCap(int value) {
        cap = Math.max(1, value);
    }

    /**
     * Un résumé lisible — même statut que {@link Jam#held()} : accessible pour un banc ou une
     * commande de diagnostic, sans être encore câblé dans un rapport périodique existant.
     */
    public static String report() {
        return String.format(Locale.ROOT,
                "grele : %d appel(s) plafonne(s), %d particule(s) de dommage economisees (cap=%d)",
                TRIMMED_CALLS.get(), PARTICLES_SAVED.get(), cap);
    }

    public static void resetCounters() {
        TRIMMED_CALLS.set(0L);
        PARTICLES_SAVED.set(0L);
    }
}
