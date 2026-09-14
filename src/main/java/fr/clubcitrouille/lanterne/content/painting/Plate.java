package fr.clubcitrouille.lanterne.content.painting;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * Une image importée, telle que le serveur la connaît — sans ses pixels.
 *
 * <h2>Pourquoi les pixels n'y sont pas</h2>
 *
 * <p>Cet objet circule : il part au client à la connexion, une fois par image du catalogue. S'il
 * portait les pixels, se connecter à un serveur qui héberge quarante tableaux voudrait dire recevoir
 * quarante mébioctets avant de voir le premier bloc. Il ne porte donc que de quoi <b>décider</b> :
 * un nom à afficher, des dimensions pour dessiner le cadre, et une empreinte.
 *
 * <h2>L'empreinte fait tout le travail</h2>
 *
 * <p>{@link #hash} est le SHA-256 des <em>octets PNG ré-échantillonnés</em>, tronqué à seize octets
 * et écrit en hexadécimal. Trois conséquences, et c'est tout le système de synchronisation :
 *
 * <ul>
 *   <li><b>Deux fichiers identiques donnent la même empreinte.</b> Renommer une image, la copier
 *       dans deux serveurs, la remettre après une réinstallation : le client la reconnaît et ne la
 *       redemande pas.</li>
 *   <li><b>Deux images différentes ne la donnent jamais.</b> Seize octets de SHA-256, c'est une
 *       collision attendue au bout de deux puissance soixante-quatre images — davantage que ce
 *       qu'un disque dur contiendra jamais.</li>
 *   <li><b>Le cache du client s'indexe dessus</b>, et non sur le nom du fichier. Un tableau
 *       renommé côté serveur n'est pas retransmis ; un tableau modifié l'est, parce que son
 *       empreinte a changé. C'est exactement le comportement voulu, et il sort tout seul.</li>
 * </ul>
 *
 * <p>L'empreinte est prise <b>après</b> le ré-échantillonnage, pas avant. Sans cela, changer la
 * limite de taille dans les réglages n'aurait rien changé pour un client qui a déjà l'image en
 * cache : il aurait gardé l'ancienne définition pour toujours.
 *
 * @param hash empreinte hexadécimale, trente-deux caractères.
 * @param name nom lisible, tiré du nom de fichier sans son extension.
 * @param pixelWidth largeur de l'image ré-échantillonnée.
 * @param pixelHeight hauteur de l'image ré-échantillonnée.
 */
public record Plate(String hash, String name, int pixelWidth, int pixelHeight) {
    /** Longueur exacte d'une empreinte — seize octets, deux caractères chacun. */
    public static final int HASH_LENGTH = 32;

    /** Longueur maximale d'un nom affiché. Au-delà, l'infobulle déborde de l'écran. */
    public static final int NAME_LIMIT = 48;

    public static final StreamCodec<RegistryFriendlyByteBuf, Plate> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.stringUtf8(HASH_LENGTH), Plate::hash,
                    ByteBufCodecs.stringUtf8(NAME_LIMIT), Plate::name,
                    ByteBufCodecs.VAR_INT, Plate::pixelWidth,
                    ByteBufCodecs.VAR_INT, Plate::pixelHeight,
                    Plate::new);

    /**
     * Les dimensions en blocs qui respectent le mieux les proportions de l'image.
     *
     * <h2>Pourquoi ce n'est pas le joueur qui choisit par défaut</h2>
     *
     * <p>Un tableau dont les proportions ne sont pas celles de l'image est laid — l'image est
     * étirée, et cela se voit immédiatement sur un visage ou une ligne d'horizon. Le joueur peut
     * imposer une taille, mais s'il ne dit rien, la taille proposée est celle qui approche le mieux
     * le rapport de l'image, dans la limite autorisée.
     *
     * <h2>La recherche est exhaustive, et c'est la bonne façon</h2>
     *
     * <p>Le domaine compte au plus seize fois seize couples. Les parcourir tous et garder le
     * meilleur coûte deux cent cinquante-six divisions, une seule fois par image et par import.
     * Une formule fermée serait plus savante et se tromperait aux bords ; ici l'exactitude est
     * gratuite.
     *
     * @param limit côté maximal, en blocs.
     * @return un couple {@code {largeur, hauteur}}, tous deux entre un et {@code limit}.
     */
    public int[] fittingBlocks(int limit) {
        double wanted = (double) this.pixelWidth / (double) this.pixelHeight;
        int bestW = 1;
        int bestH = 1;
        double bestError = Double.MAX_VALUE;
        for (int h = 1; h <= limit; h++) {
            for (int w = 1; w <= limit; w++) {
                double ratio = (double) w / (double) h;
                // L'erreur est mesurée sur le logarithme du rapport : sans cela, une image deux
                // fois trop large et une image deux fois trop haute n'auraient pas la même
                // pénalité, et les portraits seraient systématiquement moins bien servis que les
                // paysages.
                double error = Math.abs(Math.log(ratio / wanted));
                // À erreur égale, on préfère le plus grand : un tableau minuscule qui respecte les
                // proportions est une réponse techniquement juste et pratiquement décevante.
                if (error < bestError - 1.0e-9d
                        || (Math.abs(error - bestError) <= 1.0e-9d && w * h > bestW * bestH)) {
                    bestError = error;
                    bestW = w;
                    bestH = h;
                }
            }
        }
        return new int[] {bestW, bestH};
    }
}
