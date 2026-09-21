package fr.clubcitrouille.lanterne.client.shaderopt;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Graphe des pièces jointes de rendu (colortex0…15) d'un shaderpack : qui écrit quoi, qui relit quoi.
 *
 * <h2>Pourquoi</h2>
 * Chaque passe de géométrie écrit plusieurs textures (albedo, normales, données de matériau…). Une écriture
 * que AUCUN programme ne relit, avec les options actives, coûte de la bande passante mémoire pour rien —
 * surtout sur un GPU intégré qui partage la mémoire avec le processeur. La retirer est SANS PERTE : le
 * rendu final ne peut pas changer, puisque personne ne lit cette donnée.
 *
 * <h2>Entrée</h2>
 * Les programmes aplatis (options appliquées). Les écritures sont lues dans les marqueurs
 * {@code @@DRAWBUFFERS:...} que l'outil de préprocessing laisse à la place des commentaires
 * {@code /* DRAWBUFFERS:... *​/} ou {@code RENDERTARGETS:...}, et dans les sorties {@code layout(location=N)}.
 * Les lectures sont les échantillonneurs {@code colortexN} réellement utilisés hors de leur déclaration.
 */
public final class AttachmentGraph {
    /** Une pièce jointe et son usage. */
    public record Attachment(int index, List<String> writers, List<String> readers, boolean dead) {}

    private final Map<Integer, TreeSet<String>> writers = new TreeMap<>();
    private final Map<Integer, TreeSet<String>> readers = new TreeMap<>();

    private static final Pattern MARK = Pattern.compile("@@(?:DRAWBUFFERS|RENDERTARGETS):([0-9a-fA-F, ]+)");
    private static final Pattern LOC_OUT = Pattern.compile("layout\\s*\\(\\s*location\\s*=\\s*(\\d+)[^)]*\\)\\s*out\\b");
    private static final Pattern SAMPLER = Pattern.compile("\\b(?:colortex(\\d+)|gcolor|gdepth|gnormal|composite|gaux([1-4]))\\b");
    private static final Pattern DECL = Pattern.compile("uniform\\s+(?:highp\\s+|mediump\\s+|lowp\\s+)?sampler2D\\s+\\w+\\s*;");

    /** Ajoute une passe. {@code flat} est le texte aplati du programme (fragment). */
    public void addPass(String passName, String flat) {
        Matcher m = MARK.matcher(flat);
        while (m.find()) {
            String list = m.group(1);
            if (list.contains(",")) {
                for (String t : list.split(",")) {
                    String s = t.strip();
                    if (!s.isEmpty()) writers.computeIfAbsent(Integer.parseInt(s), k -> new TreeSet<>()).add(passName);
                }
            } else {
                for (char c : list.strip().toCharArray()) {
                    if (Character.digit(c, 16) >= 0)
                        writers.computeIfAbsent(Character.digit(c, 16), k -> new TreeSet<>()).add(passName);
                }
            }
        }
        Matcher lo = LOC_OUT.matcher(flat);
        while (lo.find()) writers.computeIfAbsent(Integer.parseInt(lo.group(1)), k -> new TreeSet<>()).add(passName);

        // lectures : on retire les déclarations puis on cherche les échantillonneurs restants
        String body = DECL.matcher(flat).replaceAll("");
        Matcher sm = SAMPLER.matcher(body);
        while (sm.find()) {
            int idx;
            if (sm.group(1) != null) idx = Integer.parseInt(sm.group(1));
            else if (sm.group(2) != null) idx = 3 + Integer.parseInt(sm.group(2));
            else idx = switch (sm.group()) {
                case "gcolor" -> 0;
                case "gdepth" -> 1;
                case "gnormal" -> 2;
                default -> 3; // composite
            };
            readers.computeIfAbsent(idx, k -> new TreeSet<>()).add(passName);
        }
    }

    /** Écritures jamais relues (sauf par la passe elle-même). */
    public List<Attachment> report() {
        List<Attachment> out = new ArrayList<>();
        TreeSet<Integer> all = new TreeSet<>(writers.keySet());
        all.addAll(readers.keySet());
        for (int i : all) {
            List<String> w = new ArrayList<>(writers.getOrDefault(i, new TreeSet<>()));
            List<String> r = new ArrayList<>(readers.getOrDefault(i, new TreeSet<>()));
            // relu ailleurs que par ses propres écrivains ?
            // Iris alterne (« flip ») les tampons : une passe qui lit colortexN en l'écrivant lit le résultat de
            // la passe précédente, et une lecture placée avant l'écriture lit l'image d'avant : dans tous ces
            // cas la donnée est VIVANTE. Elle n'est morte que si personne ne la lit du tout.
            boolean dead = !w.isEmpty() && r.isEmpty();
            out.add(new Attachment(i, w, r, dead));
        }
        return out;
    }

    /** Résumé lisible : une ligne par pièce jointe. */
    public String summary() {
        StringBuilder sb = new StringBuilder();
        Map<Integer, String> lines = new LinkedHashMap<>();
        for (Attachment a : report()) {
            sb.append(String.format("colortex%-2d écrit par %-4d passes, relu par %-4d passes%s%n", a.index(),
                    a.writers().size(), a.readers().size(), a.dead() ? "  <- ÉCRITURE JAMAIS RELUE (écrivains : " + a.writers() + ")" : ""));
        }
        return sb.toString();
    }
}
