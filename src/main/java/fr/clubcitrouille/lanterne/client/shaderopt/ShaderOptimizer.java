package fr.clubcitrouille.lanterne.client.shaderopt;

import java.util.Map;
import java.util.function.Predicate;

/**
 * Façade de l'optimiseur de shaders de Lanterne.
 *
 * <h2>Niveaux (règle d'iso-qualité de OBJECTIFS-FPS.md)</h2>
 * <ul>
 *   <li><b>NIVEAU 1 — sans perte</b> (actif par défaut) : le rendu est identique au bit près à l'état
 *       courant. Spécialisation sur l'état de jeu neutre ({@link ConstFolder}), retrait des écritures de
 *       pièces jointes jamais relues ({@link AttachmentGraph}).</li>
 *   <li><b>NIVEAU 2 — quasi sans perte</b> (désactivé par défaut) : précision réduite (fp16/mediump) sur
 *       des calculs dont l'erreur reste sous un seuil mesuré. Activable par profil.</li>
 *   <li><b>NIVEAU 3 — avec perte</b> (désactivé par défaut) : demi-résolution d'effets, moins d'échantillons.
 *       Activable par profil, avec l'écart d'image affiché à l'écran d'options.</li>
 * </ul>
 *
 * <h2>Principe de sûreté</h2>
 * L'optimiseur ne dégrade JAMAIS un shader qu'il ne comprend pas. Chaque transformation passe par
 * {@link #apply} : si le validateur (compilation, ou différence d'image hors seuil) refuse le résultat, ou
 * si la transformation lève une exception, le shader d'ORIGINE est conservé et l'événement est journalisé
 * (ligne {@code [BILAN]} côté client).
 */
public final class ShaderOptimizer {
    private ShaderOptimizer() {}

    /** Niveau de risque d'une transformation. */
    public enum Level { SANS_PERTE, QUASI_SANS_PERTE, AVEC_PERTE }

    /** Résultat d'une application : le texte retenu et la raison d'un éventuel repli. */
    public record Outcome(String text, boolean transformed, String reason) {}

    /**
     * Spécialisation sur l'état neutre (niveau 1). {@code validator} rend {@code true} si le texte transformé
     * est accepté (ex. compile sans erreur) ; sinon on garde l'original.
     */
    public static Outcome specializeNeutral(String flat, Predicate<String> validator) {
        return specialize(flat, ConstFolder.NEUTRAL_STATE, validator);
    }

    public static Outcome specialize(String flat, Map<String, String> state, Predicate<String> validator) {
        try {
            ConstFolder.Result r = ConstFolder.specialize(flat, state);
            if (r.constUniforms() == 0) return new Outcome(flat, false, "aucun uniform d'état dans ce programme");
            if (validator != null && !validator.test(r.text())) {
                return new Outcome(flat, false, "validation refusée : original conservé");
            }
            return new Outcome(r.text(), true, r.constUniforms() + " uniform(s) figé(s), " + r.foldedIfs()
                    + " branche(s) repliée(s), " + r.removedChars() + " caractères en moins");
        } catch (RuntimeException e) {
            return new Outcome(flat, false, "exception : " + e.getClass().getSimpleName() + " — original conservé");
        }
    }
}
