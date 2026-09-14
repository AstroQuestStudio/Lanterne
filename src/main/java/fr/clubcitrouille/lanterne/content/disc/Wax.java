package fr.clubcitrouille.lanterne.content.disc;

import java.nio.file.Path;
import java.util.Locale;

/**
 * Un disque : le fichier, son nom, sa durée.
 *
 * <h2>Pourquoi un identifiant séparé du nom</h2>
 *
 * <p>Le nom est ce que le joueur lit — accents, espaces, apostrophes, tout est permis. Le
 * {@link #slug} est ce que le jeu manipule : il devient un identifiant de son, un chemin de fichier
 * dans une archive de ressources et une clé de registre, et ces trois usages n'acceptent que des
 * minuscules, des chiffres, des tirets et des barres obliques. Confondre les deux, c'est un plantage
 * au premier fichier nommé « Été 2024.ogg ».
 *
 * <p>La transformation est déterministe : le même nom de fichier donne toujours le même
 * identifiant. C'est ce qui fait qu'un disque posé dans un jukebox y reste après un redémarrage, et
 * qu'un joueur qui reçoit le même dossier retrouve les mêmes disques.
 *
 * @param slug l'identifiant, sûr pour un registre et pour un chemin.
 * @param name le nom affiché, tel que le fichier s'appelle.
 * @param seconds la durée, lue dans les entêtes du fichier et non estimée.
 * @param file le fichier OGG à lire en flux.
 */
public record Wax(String slug, String name, float seconds, Path file) {
    /** Longueur maximale d'un nom affiché. */
    public static final int NAME_LIMIT = 48;

    /**
     * L'identifiant tiré d'un nom de fichier.
     *
     * <p>Les caractères interdits deviennent des tirets bas plutôt que de disparaître : sans cela
     * « été.ogg » et « ete.ogg » donneraient le même identifiant, et le second écraserait le
     * premier en silence. Un tiret bas par caractère refusé garde les deux distincts.
     *
     * <p>Un identifiant vide — un fichier nommé uniquement de caractères refusés — n'est pas
     * possible : la chaîne vide est remplacée, parce qu'un registre la rejetterait au chargement,
     * c'est-à-dire trop tard pour le dire proprement.
     */
    public static String slugify(String raw) {
        StringBuilder out = new StringBuilder(raw.length());
        String lower = raw.toLowerCase(Locale.ROOT);
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-' || c == '.') {
                out.append(c);
            } else {
                out.append('_');
            }
        }
        String slug = out.toString();
        // Le point est accepté à l'intérieur d'un identifiant de ressource, mais une extension
        // oubliée donnerait « chanson.mp3 » comme identifiant, ce qui est laid et prête à confusion.
        int dot = slug.lastIndexOf('.');
        if (dot > 0) {
            slug = slug.substring(0, dot);
        }
        if (slug.isEmpty()) {
            slug = "disque";
        }
        return slug.length() > 64 ? slug.substring(0, 64) : slug;
    }

    /** Le nom affiché tiré d'un nom de fichier : sans extension, tirets bas rendus aux espaces. */
    public static String prettify(String fileName) {
        int dot = fileName.lastIndexOf('.');
        String stem = dot > 0 ? fileName.substring(0, dot) : fileName;
        stem = stem.replace('_', ' ').trim();
        if (stem.isEmpty()) {
            return "Disque";
        }
        return stem.length() > NAME_LIMIT ? stem.substring(0, NAME_LIMIT) : stem;
    }

    /**
     * La sortie de comparateur, dérivée du nom.
     *
     * <p>Vanilla donne à chacun de ses treize disques une valeur distincte de un à quinze, ce qui
     * permet de trier des disques par redstone. Ici les disques sont en nombre quelconque, et il
     * n'existe pas d'attribution qui reste stable quand le joueur en ajoute un au milieu — sauf à la
     * tirer du nom lui-même, ce que l'on fait. Deux disques peuvent donc partager une valeur ; c'est
     * inévitable au-delà de quinze disques, et c'est annoncé plutôt que caché.
     */
    public int comparatorOutput() {
        int mixed = this.slug.hashCode();
        mixed ^= mixed >>> 16;
        return 1 + Math.floorMod(mixed, 15);
    }
}
