package fr.clubcitrouille.lanterne.chantier;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.google.gson.JsonObject;

import net.minecraft.server.MinecraftServer;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * La file des travaux, drainée à la fin de chaque tick serveur sous un budget en millisecondes.
 *
 * <p>Un seul travail avance à la fois, dans l'ordre d'arrivée : deux écritures qui se chevauchent
 * s'exécutent donc comme on les a envoyées, et le journal d'annulation de la seconde voit bien le
 * résultat de la première. Le budget est divisé par quatre tant que le tick moyen du serveur passe
 * 40 ms — un chantier ne doit jamais être la goutte qui fait déborder un serveur déjà en retard.
 */
final class Atelier {
    private static final int FILE_MAX = 32;
    private static final int GARDES = 16;

    private static final ConcurrentLinkedQueue<Travail> FILE = new ConcurrentLinkedQueue<>();
    /** Travaux connus par identifiant, pour {@code /travail/<id>} ; les plus anciens finis sont oubliés. */
    private static final Map<String, Travail> CONNUS = new ConcurrentHashMap<>();
    private static final ArrayDeque<String> ORDRE = new ArrayDeque<>();
    private static volatile Travail courant;

    /** Mise en forme et écriture disque, hors du thread serveur, un fil unique : l'ordre des journaux est garanti. */
    private static final ExecutorService DISQUE = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Lanterne-Chantier-Disque");
        t.setDaemon(true);
        return t;
    });

    // Mesures cumulées, lues par /etat depuis un autre fil.
    static volatile long ticksActifs;
    static volatile long tempsTotal;
    static volatile long pireTick;
    static volatile long travauxFinis;

    private Atelier() {}

    static Travail soumettre(Travail t) {
        if (FILE.size() >= FILE_MAX) {
            throw new Refus(503, "file du chantier pleine (" + FILE_MAX + " travaux) : réessaie plus tard");
        }
        synchronized (ORDRE) {
            CONNUS.put(t.id, t);
            ORDRE.addLast(t.id);
            while (ORDRE.size() > FILE_MAX + GARDES) {
                String vieux = ORDRE.peekFirst();
                Travail v = CONNUS.get(vieux);
                if (v != null && !v.fin.isDone()) {
                    break;
                }
                ORDRE.pollFirst();
                CONNUS.remove(vieux);
            }
        }
        FILE.add(t);
        return t;
    }

    static Travail connu(String id) {
        return CONNUS.get(id);
    }

    static int enFile() {
        return FILE.size() + (courant != null ? 1 : 0);
    }

    static void tick(MinecraftServer server) {
        if (courant == null && FILE.isEmpty()) {
            return;
        }
        long budget = Chantier.BUDGET_MS.get() * 1_000_000L;
        if (server.getAverageTickTimeNanos() > 40_000_000L) {
            budget = Math.max(1_000_000L, budget / 4);
        }
        long debut = System.nanoTime();
        long echeance = debut + budget;
        while (System.nanoTime() < echeance) {
            if (courant == null) {
                courant = FILE.poll();
                if (courant == null) {
                    break;
                }
                courant.demarre = System.nanoTime();
                courant.etat = "en_cours";
                try {
                    courant.demarrer();
                } catch (Throwable e) {
                    echouer(courant, e);
                    courant = null;
                    continue;
                }
            }
            Travail t = courant;
            long avant = System.nanoTime();
            boolean fini;
            try {
                fini = t.avancer(echeance);
            } catch (Throwable e) {
                echouer(t, e);
                courant = null;
                continue;
            }
            long duree = System.nanoTime() - avant;
            t.tempsServeur += duree;
            t.ticks++;
            t.pireTick = Math.max(t.pireTick, duree);
            if (duree > 50_000_000L) {
                // Un pas qui déborde d'un tick entier : jamais attendu (les chunks sont chargés hors
                // du tick), donc toujours à signaler — c'est ce journal qui a trouvé Etats.charger.
                Lanterne.LOG.warn("[CHANTIER] pas de {} ms pour « {} » ({}), budget {} ms",
                        duree / 1_000_000L, t.nature, t.id, budget / 1_000_000L);
            }
            if (!fini) {
                break; // budget épuisé, ou attente d'un chunk : on reprendra au tick suivant.
            }
            t.termine = System.nanoTime();
            t.etat = "mise_en_forme";
            courant = null;
            // Un rendu 3D rastérise plusieurs secondes : son propre fil, pour ne pas retenir les journaux.
            (t instanceof Rendu3d ? Rendu3d.FIL : DISQUE).execute(() -> {
                try {
                    t.fin.complete(t.conclure());
                    t.etat = "fini";
                } catch (Throwable e) {
                    echouer(t, e);
                }
                travauxFinis++;
            });
        }
        long duree = System.nanoTime() - debut;
        ticksActifs++;
        tempsTotal += duree;
        pireTick = Math.max(pireTick, duree);
    }

    private static void echouer(Travail t, Throwable e) {
        t.etat = "echec";
        if (e instanceof Refus r) {
            t.fin.complete(new Travail.Resultat(r.code, erreur(r.getMessage()), null));
        } else {
            Lanterne.LOG.error("[CHANTIER] travail {} ({}) en échec", t.id, t.nature, e);
            t.fin.complete(new Travail.Resultat(500, erreur(e.toString()), null));
        }
    }

    static JsonObject erreur(String message) {
        JsonObject o = new JsonObject();
        o.addProperty("erreur", message);
        return o;
    }

    /**
     * À l'arrêt du serveur. Le travail en cours n'est pas jeté : il est conclu tel quel, pour qu'une
     * écriture interrompue laisse quand même son journal d'annulation (ce qui a été posé doit pouvoir
     * être défait). Les travaux en file, eux, n'ont rien touché : ils sont simplement refusés.
     */
    static void abandonnerTout(String raison) {
        Travail t = courant;
        courant = null;
        if (t != null) {
            t.interrompu = raison;
            t.termine = System.nanoTime();
            DISQUE.execute(() -> {
                try {
                    t.fin.complete(t.conclure());
                } catch (Throwable e) {
                    echouer(t, e);
                }
            });
        }
        for (Iterator<Travail> it = FILE.iterator(); it.hasNext();) {
            echouer(it.next(), new Refus(503, raison));
            it.remove();
        }
        try {
            DISQUE.submit(() -> {}).get(30, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            Lanterne.LOG.warn("[CHANTIER] journaux peut-être incomplets à l'arrêt : {}", e.toString());
        }
    }
}
