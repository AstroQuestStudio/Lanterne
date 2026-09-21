package fr.clubcitrouille.lanterne.client.bilan;

/**
 * Distribution de durées d'image, sans aucune allocation à l'enregistrement.
 *
 * <p>Histogramme à pas fixe de 50 µs jusqu'à 100 ms, plus un compartiment de débordement (somme et
 * compte : les très longues images comptent dans les moyennes et les « 1 % low », pas seulement dans
 * le maximum). Les centiles se lisent en parcourant les compartiments : précision de 50 µs, ce qui
 * est bien inférieure au bruit d'une mesure d'image.
 */
final class BilanFrames {
    private static final long STEP_NANOS = 50_000L;
    private static final int BUCKETS = 2_000;

    private final long[] counts = new long[BUCKETS];
    private long overflowCount;
    private long overflowSum;
    private long count;
    private long sum;
    private long max;

    void record(long nanos) {
        if (nanos < 0) {
            return;
        }
        int bucket = (int) (nanos / STEP_NANOS);
        if (bucket >= BUCKETS) {
            overflowCount++;
            overflowSum += nanos;
        } else {
            counts[bucket]++;
        }
        count++;
        sum += nanos;
        if (nanos > max) {
            max = nanos;
        }
    }

    void reset() {
        java.util.Arrays.fill(counts, 0L);
        overflowCount = 0;
        overflowSum = 0;
        count = 0;
        sum = 0;
        max = 0;
    }

    long count() {
        return count;
    }

    double meanMs() {
        return count == 0 ? 0d : sum / (double) count / 1.0e6;
    }

    double maxMs() {
        return max / 1.0e6;
    }

    /** FPS moyen = images / durée cumulée (pas la moyenne des FPS instantanés). */
    double fps() {
        return sum == 0 ? 0d : count * 1.0e9 / sum;
    }

    /** Durée en ms en dessous de laquelle se trouvent {@code p} (0..1) des images. */
    double percentileMs(double p) {
        if (count == 0) {
            return 0d;
        }
        long target = Math.max(1L, (long) Math.ceil(p * count));
        long seen = 0;
        for (int i = 0; i < BUCKETS; i++) {
            seen += counts[i];
            if (seen >= target) {
                return (i + 0.5d) * STEP_NANOS / 1.0e6;
            }
        }
        // dans le compartiment de débordement : la moyenne de celui-ci, faute de mieux
        return overflowCount == 0 ? max / 1.0e6 : overflowSum / (double) overflowCount / 1.0e6;
    }

    /**
     * « x % low » : FPS moyen des {@code fraction} images les plus lentes (0,01 = 1 % low, 0,001 =
     * 0,1 % low), la définition usuelle des outils de mesure d'images.
     */
    double lowFps(double fraction) {
        if (count == 0) {
            return 0d;
        }
        long need = Math.max(1L, (long) Math.ceil(fraction * count));
        long taken = 0;
        double timeSum = 0d;
        if (overflowCount > 0) {
            long take = Math.min(need, overflowCount);
            timeSum += take * (overflowSum / (double) overflowCount);
            taken += take;
        }
        for (int i = BUCKETS - 1; i >= 0 && taken < need; i--) {
            long c = counts[i];
            if (c == 0) {
                continue;
            }
            long take = Math.min(need - taken, c);
            timeSum += take * (i + 0.5d) * STEP_NANOS;
            taken += take;
        }
        return taken == 0 || timeSum == 0d ? 0d : taken * 1.0e9 / timeSum;
    }
}
