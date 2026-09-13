package fr.clubcitrouille.lanterne.lab;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import fr.clubcitrouille.lanterne.Lanterne;
import fr.clubcitrouille.lanterne.core.Census;

/**
 * Le contrôle avant mesure : refuser un chiffre plutôt qu'en rendre un faux.
 *
 * <h2>Pourquoi cette classe existe</h2>
 *
 * <p>Ce banc a menti trois fois, et à chaque fois il a accusé le mod à tort. Les trois causes
 * étaient différentes ; le symptôme, lui, était toujours le même — <b>un chiffre plausible, rendu
 * avec assurance, sur des conditions qui n'étaient pas celles qu'on croyait</b>.
 *
 * <ol>
 *   <li>Les joueurs d'essai n'étaient pas de vrais joueurs, puis l'étaient mais en spectateur — que
 *       le recensement ignore — puis n'appartenaient à aucun monde. Dans les trois cas, le mod
 *       voyait un serveur vide et ralentissait tout au maximum. Un gain de ×10,5 a été publié sur
 *       cette base ; il ne valait rien.</li>
 *   <li>Le banc générait le monde pendant qu'il mesurait : la génération de terrain dominait les
 *       allocations relevées, et le mod paraissait deux fois moins efficace qu'il ne l'est.</li>
 *   <li>Le monde conservé gardait les créatures des épreuves précédentes : l'épreuve de chute a
 *       rendu six pour cent, imputés au mod alors que ses sujets étaient noyés dans une foule.</li>
 * </ol>
 *
 * <p>Dans les trois cas, <b>la condition fautive était vérifiable avant de mesurer</b>. Personne ne
 * l'a vérifiée parce que rien ne le faisait.
 *
 * <h2>Ce que fait ce contrôle</h2>
 *
 * <p>Il énumère ce dont la mesure dépend, et le vérifie. Si une condition manque, le banc
 * <b>ne mesure pas</b> : il dit ce qui manque et s'arrête. Un banc silencieux vaut mieux qu'un banc
 * qui se trompe, parce qu'un chiffre faux ne se corrige pas — il se propage.
 *
 * <p>Le contrôle est volontairement bavard sur ce qu'il a vérifié, y compris quand tout va bien.
 * C'est ce qui permet, en relisant un journal trois semaines plus tard, de savoir dans quelles
 * conditions un chiffre a été obtenu.
 */
public final class Preflight {
    /** Entités résiduelles tolérées avant de considérer le monde comme sale. */
    private static final int STRAY_LIMIT = 8;

    private Preflight() {}

    /** Le verdict d'un contrôle : ce qui va, ce qui ne va pas. */
    public record Verdict(boolean sound, List<String> faults, List<String> notes) {
        public String summary() {
            return sound ? "conditions saines" : String.join(" · ", faults);
        }
    }

    /**
     * Vérifie que la mesure peut avoir lieu.
     *
     * @param expectedEntities nombre d'entités que l'épreuve a demandé, ou zéro si indifférent
     */
    public static Verdict check(MinecraftServer server, ServerLevel level, int expectedEntities) {
        List<String> faults = new ArrayList<>();
        List<String> notes = new ArrayList<>();

        // 1. Des joueurs, et dans CE monde. Les deux sont distincts : le serveur peut en compter un
        //    qui n'appartient à aucun niveau, et c'est très exactement l'erreur commise.
        int onServer = server.getPlayerList().getPlayerCount();
        int inLevel = level.players().size();
        notes.add(onServer + " joueur(s) au serveur, " + inLevel + " dans le monde");
        if (inLevel == 0) {
            faults.add("aucun joueur dans le monde — rien ne sera recensé");
        }

        // 2. Aucun en spectateur : le recensement les ignore, à juste titre, et le mod se croirait
        //    alors sur un serveur désert.
        long watchers = level.players().stream().filter(p -> p.isSpectator()).count();
        if (watchers > 0 && watchers == inLevel) {
            faults.add("tous les joueurs sont spectateurs — le recensement les ignore");
        }

        // 3. Le recensement doit voir des chunks. C'est le contrôle qui aurait démasqué le
        //    débordement d'entier en une ligne, six bancs plus tôt.
        int chunks = Census.chunksSeen();
        notes.add(chunks + " chunk(s) recensé(s)");
        if (chunks == 0) {
            faults.add("aucun chunk recensé — le mod ne peut rien classer");
        }

        // 4. Les entités demandées doivent être vivantes. Trois épreuves ont mesuré un monde vide en
        //    croyant mesurer cinq mille vaches.
        int alive = count(level);
        notes.add(alive + " entité(s) vivante(s)");
        if (expectedEntities > 0 && alive < expectedEntities / 2) {
            faults.add("seulement " + alive + " entités sur " + expectedEntities
                    + " demandées — les chunks n'étaient pas prêts");
        }

        // 5. Pour une épreuve de conformité, le monde doit être propre : ses sujets ne doivent pas
        //    être noyés dans la foule d'une épreuve précédente.
        if (expectedEntities == 0 && alive > STRAY_LIMIT) {
            faults.add(alive + " entités résiduelles — l'épreuve sera faussée par la densité");
        }

        return new Verdict(faults.isEmpty(), faults, notes);
    }

    /** Écrit le contrôle au journal, qu'il passe ou non. */
    public static boolean announce(String what, Verdict verdict) {
        Lanterne.LOG.info("[CONTRÔLE] {} — {}", what, String.join(" · ", verdict.notes()));
        if (verdict.sound()) {
            return true;
        }
        Lanterne.LOG.error("[CONTRÔLE] MESURE REFUSÉE : {}", verdict.summary());
        Lanterne.LOG.error("[CONTRÔLE] Un chiffre faux ne se corrige pas — il se propage. "
                + "Corriger les conditions, puis relancer.");
        return false;
    }

    private static int count(ServerLevel level) {
        int total = 0;
        for (var ignored : level.getAllEntities()) {
            total++;
        }
        return total;
    }
}
