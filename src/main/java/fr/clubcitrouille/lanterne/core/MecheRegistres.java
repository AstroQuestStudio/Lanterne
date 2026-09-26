package fr.clubcitrouille.lanterne.core;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * Les clés de registre SYNCHRONISÉES de l'espace {@code lanterne} — ce que NeoForge envoie au client
 * pendant la configuration, et ce qui le fait refuser un serveur dont il ne connaît pas une entrée
 * (« The server sent registries with unknown keys », vécu le 26/09/2026 avec la 26.3.145).
 *
 * <h2>La référence : ce que TOUS les clients anciens connaissent</h2>
 *
 * <p>{@code lanterne-meche/registres-reference.txt}, embarqué dans le jar, est la liste exacte des
 * clés de la 26.3.146 — et, vérifié dans l'historique git (mêmes appels {@code register("…")} de la
 * 26.3.16 environ à la 26.3.146), de toutes les versions que les joueurs peuvent encore avoir. Un
 * serveur dont les clés Lanterne sont TOUTES dans cette liste peut laisser entrer un client ancien
 * sans rien changer : il ne lui enverra aucune clé inconnue. Sinon, voir {@link MecheCompat}.
 *
 * <p><b>Ce fichier ne doit JAMAIS être régénéré</b> quand on ajoute un bloc : il décrit les clients
 * d'avant la Mèche en configuration (≤ 26.3.146), qui, eux, ne changeront plus. Les clients 26.3.147
 * et suivants se mettent à jour pendant la configuration ({@link MecheConfig}), avant même que la
 * question se pose.
 */
public final class MecheRegistres {
    private static final String REFERENCE = "/lanterne-meche/registres-reference.txt";

    private static volatile Set<String> reference;

    private MecheRegistres() {}

    /** Les clés {@code lanterne} des registres synchronisés, triées, sous la forme {@code registre clé}. */
    public static List<String> clesLanterne() {
        List<String> cles = new ArrayList<>();
        for (Registry<?> registry : BuiltInRegistries.REGISTRY) {
            if (!registry.doesSync()) {
                continue;
            }
            String nom = registry.key().identifier().toString();
            for (Identifier key : registry.keySet()) {
                if (Lanterne.ID.equals(key.getNamespace())) {
                    cles.add(nom + " " + key);
                }
            }
        }
        cles.sort(null);
        return cles;
    }

    /** Toutes ces clés existent-elles dans les registres de CETTE machine ? (côté client) */
    public static boolean toutesPresentes(List<String> cles) {
        for (String ligne : cles) {
            int espace = ligne.indexOf(' ');
            if (espace <= 0) {
                return false;
            }
            Identifier nom = Identifier.tryParse(ligne.substring(0, espace));
            Identifier cle = Identifier.tryParse(ligne.substring(espace + 1));
            if (nom == null || cle == null) {
                return false;
            }
            Registry<?> registry = BuiltInRegistries.REGISTRY.getValue(nom);
            if (registry == null || !registry.containsKey(cle)) {
                return false;
            }
        }
        return true;
    }

    /** Les clés de la liste de référence (voir la Javadoc de classe) ; vide si le fichier manque. */
    public static Set<String> reference() {
        Set<String> cached = reference;
        if (cached != null) {
            return cached;
        }
        Set<String> lues = new HashSet<>();
        try (InputStream in = MecheRegistres.class.getResourceAsStream(REFERENCE)) {
            if (in != null) {
                try (var reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        String s = line.strip();
                        if (!s.isEmpty() && !s.startsWith("#")) {
                            lues.add(s);
                        }
                    }
                }
            }
        } catch (Exception illisible) {
            Lanterne.LOG.warn("[MÈCHE] liste de référence des registres illisible : {}", illisible.toString());
        }
        reference = Set.copyOf(lues);
        return reference;
    }

    /**
     * Les clés de CE serveur absentes de la référence : ce qu'un client ancien ne connaît pas.
     * Vide = un client ancien peut entrer normalement. Si la référence manque (fichier absent), on
     * ne sait pas : on répond comme si tout manquait, ce qui mène au mode de compatibilité — le
     * choix prudent.
     */
    public static List<String> inconnuesDesAnciens() {
        Set<String> ref = reference();
        List<String> manquantes = new ArrayList<>();
        for (String cle : clesLanterne()) {
            if (ref.isEmpty() || !ref.contains(cle)) {
                manquantes.add(cle);
            }
        }
        return manquantes;
    }
}
