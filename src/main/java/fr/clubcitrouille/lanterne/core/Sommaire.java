package fr.clubcitrouille.lanterne.core;

import java.util.Locale;

/**
 * Le sommaire : un zip se lit une fois, pas deux cent cinquante fois.
 *
 * <h2>Le défaut, et il est dans deux méthodes</h2>
 *
 * <p>{@code FilePackResources} sert les paquets de ressources et les datapacks rangés en
 * {@code .zip}. Deux de ses méthodes appellent {@code zipFile.entries()} et <b>balayent le fichier
 * entier</b> :
 *
 * <pre>
 * getNamespaces  — FilePackResources.java, ligne 70
 * listResources  — FilePackResources.java, ligne 110
 * </pre>
 *
 * <p>Ni l'une ni l'autre ne garde quoi que ce soit. Chaque appel recommence.
 *
 * <h2>L'amplification, comptée dans le code du jeu</h2>
 *
 * <p>{@code MultiPackResourceManager.listResources} boucle sur les gestionnaires par espace de noms,
 * chacun boucle sur la pile de paquets, et chacun appelle {@code listResources} du paquet. Au-dessus,
 * un rechargement de datapacks appelle {@code ResourceManager.listResources} une fois par registre
 * dynamique — quarante-sept — puis une fois par registre pour les étiquettes — une centaine — plus
 * les recettes, les avancements, les fonctions et les tables de butin.
 *
 * <p>Le coût vanilla est donc <b>entrées × espaces de noms × paquets × appels</b>, et les quatre
 * facteurs sont grands en même temps.
 *
 * <h2>Ce que fait le sommaire, et ce qu'il ne fait pas</h2>
 *
 * <p>Il construit <b>une fois</b>, au premier appel, un ensemble trié des noms d'entrées du zip. Une
 * demande « tout ce qui commence par {@code data/minecraft/recipe/} » devient alors une recherche
 * dichotomique suivie d'un parcours des seuls éléments qui répondent, au lieu d'un balayage complet.
 *
 * <p>Il ne touche <b>pas</b> à {@code getResource}, et c'est délibéré : celui-ci utilise
 * {@code ZipFile.getEntry}, qui est une consultation de table de hachage native — déjà en temps
 * constant. Indexer pour lui n'aurait rien rapporté et aurait ajouté un point d'accroche.
 *
 * <h2>Où le gain se trouve, et où il ne se trouve pas</h2>
 *
 * <p>Il faut le dire sans enthousiasme : <b>un serveur dédié ne charge aucun paquet de ressources</b>.
 * Seul le client le fait ({@code Minecraft.java} construit le {@code FolderRepositorySource} des
 * {@code CLIENT_RESOURCES}). Côté serveur, seuls les <b>datapacks en .zip</b> passent par cette
 * classe : un datapack rangé en dossier est servi par {@code PathPackResources}, qui parcourt le
 * système de fichiers en se limitant au sous-répertoire demandé et n'a donc pas ce défaut.
 *
 * <p>Le gain est donc : <b>au démarrage du client</b>, sur les gros paquets de ressources — et c'en
 * est un pour le Club, dont le paquet porte les mosaïques de tableaux et les disques ; et
 * <b>au rechargement d'un serveur</b> dont les datapacks sont zippés.
 */
public final class Sommaire {
    private static long built;
    private static long indexed;
    private static long served;
    private static long spared;
    private static long namespacesServed;

    /**
     * Rend le sommaire incomplet, et fait donc échouer l'épreuve.
     *
     * <h2>Le premier interrupteur choisi ne cassait rien, et c'est ce qui l'a appris</h2>
     *
     * <p>Il retirait la borne supérieure de la recherche — le défaut classique d'un index trié. Le
     * gain est tombé de ×18,5 à ×1,0 et la conformité a annoncé « ensembles identiques ». La cause
     * est dans {@code PackIndexMixin} : on ne remplace que l'énumération, et le
     * {@code startsWith} de vanilla filtre derrière. <b>Un intervalle trop large ne peut pas
     * produire de faux.</b>
     *
     * <p>La seule panne que ce module puisse réellement produire est donc le <b>manque</b> : une
     * ressource du paquet que le sommaire ne connaît pas. Rien ne la rattrape, rien ne plante, et
     * elle se voit des semaines plus tard sous la forme d'une texture absente.
     *
     * <p>{@code LANTERNE_BREAK_SOMMAIRE=1} fait donc sauter une entrée sur mille. L'épreuve
     * {@code lab.Sommaire} <b>doit</b> alors annoncer une divergence.
     */
    private static final boolean BROKEN = "1".equals(System.getenv("LANTERNE_BREAK_SOMMAIRE"));

    private Sommaire() {}

    public static boolean broken() {
        return BROKEN;
    }

    /**
     * Un sommaire bâti, et le nombre d'entrées qu'il a fallu lire pour cela.
     *
     * <p>Ce chiffre est le <b>coût</b> du module, et il doit figurer au même endroit que son gain :
     * le sommaire paie un balayage complet pour en éviter beaucoup d'autres. Si le paquet n'était
     * interrogé qu'une fois, il serait une perte sèche.
     */
    public static void noteBuild(int entries) {
        built++;
        indexed += entries;
    }

    /** Une demande servie par recherche dichotomique, et le balayage qu'elle a évité. */
    public static void noteQuery(long entriesSpared) {
        served++;
        spared += entriesSpared;
    }

    /** Une liste d'espaces de noms rendue de mémoire. */
    public static void noteNamespaces() {
        namespacesServed++;
    }

    public static long builds() {
        return built;
    }

    public static long served() {
        return served;
    }

    public static long namespaces() {
        return namespacesServed;
    }

    /**
     * Entrées non relues.
     *
     * <p>C'est le troisième des trois nombres de ce laboratoire — le taux et l'épargne unitaire sont
     * dans l'épreuve. Publié seul, il ne prouve rien : ce dépôt a déjà retiré deux modules qui
     * supprimaient des millions d'opérations pour zéro milliseconde.
     */
    public static long spared() {
        return spared;
    }

    public static void reset() {
        built = 0L;
        indexed = 0L;
        served = 0L;
        spared = 0L;
        namespacesServed = 0L;
    }

    public static String describe() {
        return String.format(Locale.ROOT,
                "%d sommaire(s) bâti(s) sur %d entrée(s) · %d demande(s) servie(s) · "
                        + "%d espace(s) de noms rendu(s) de mémoire · %d entrée(s) non relue(s)",
                built, indexed, served, namespacesServed, spared);
    }
}
