package fr.clubcitrouille.lanterne.content.disc;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackMetadataResources;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.MetadataSectionType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackCompatibility;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.world.flag.FeatureFlagSet;

import org.jspecify.annotations.Nullable;

import fr.clubcitrouille.lanterne.Lanterne;
import fr.clubcitrouille.lanterne.content.painting.Studio;

/**
 * La bobine : une archive de ressources qui n'existe sur aucun disque.
 *
 * <h2>Le tour de passe-passe, et pourquoi c'est la bonne solution</h2>
 *
 * <p>Un disque de musique de Minecraft, ce sont trois choses qui doivent exister <b>avant</b> que la
 * partie ne commence :
 *
 * <ol>
 *   <li>une entrée dans {@code sounds.json}, côté ressources client, qui dit où est le fichier et
 *       qu'il faut le lire en flux ;</li>
 *   <li>un fichier au chemin que cette entrée indique ;</li>
 *   <li>une entrée {@code jukebox_song}, côté données, qui donne le titre, la durée et le son.</li>
 * </ol>
 *
 * <p>Or les fichiers du joueur arrivent après la compilation du mod : on ne peut pas les mettre dans
 * l'archive. La parade est de <b>fabriquer une archive à l'exécution</b> — non pas un fichier zip
 * qu'on écrirait sur le disque, mais un objet qui répond aux questions du gestionnaire de ressources
 * comme le ferait une archive, en lisant le dossier du joueur et en composant le JSON à la volée.
 *
 * <p>C'est ce que {@code AddPackFindersEvent} rend possible, et c'est bien plus propre que d'écrire
 * un vrai zip dans {@code resourcepacks/} — qu'il faudrait activer à la main, nettoyer, et qui
 * vieillirait mal.
 *
 * <h2>Le « .ogg » du chemin est un nom, pas une promesse</h2>
 *
 * <p>Toutes les ressources servies ici se terminent par {@code .ogg}, et ce n'est pas nous qui le
 * décidons : {@code Sound.getPath} colle cette extension au nom déclaré dans {@code sounds.json},
 * sans que rien ne puisse s'y opposer. Les octets, eux, sont ceux du fichier du joueur — souvent du
 * MP3.
 *
 * <p>Cela ne gêne personne parce que <b>personne ne lit cette extension</b> : {@code
 * mixin.SoundDecodeMixin} intercepte la construction du flux audio et choisit le décodeur sur la
 * signature réelle des octets. Voir {@code Press} pour le raisonnement complet, et {@code Needle}
 * pour les décodeurs.
 *
 * <h2>Pourquoi le son n'est pas déclaré dans un registre</h2>
 *
 * <p>{@code SoundEvent} vit dans un registre <em>statique</em>, scellé à la fin du chargement des
 * mods : on ne peut pas y ajouter une entrée par fichier trouvé plus tard. C'est le mur contre
 * lequel butent la plupart des mods de disques personnalisés, qui réservent alors un nombre fixe de
 * fentes — « disque_1 » à « disque_64 » — et s'arrêtent là.
 *
 * <p>Ce mur n'existe pas ici, et la raison est un détail de {@code JukeboxSong} : son champ
 * {@code sound_event} utilise {@code RegistryFileCodec}, qui accepte <b>soit</b> un identifiant de
 * registre, <b>soit</b> l'objet écrit en toutes lettres. En l'écrivant en toutes lettres, on obtient
 * un son valide sans jamais l'enregistrer nulle part. Le nombre de disques n'est donc borné que par
 * les réglages.
 *
 * <p>{@code jukebox_song}, lui, est un registre de <em>données</em>, alimenté par les fichiers d'un
 * paquet de données — exactement ce que la bobine fournit. Il est aussi synchronisé vers le client à
 * la connexion, ce qui fait qu'un disque posé dans un jukebox s'affiche correctement même chez un
 * joueur qui n'aurait pas le fichier audio : il verra le titre et n'entendra rien, plutôt que de
 * voir un objet cassé.
 *
 * <h2>Ce qui ne marche pas, et qu'il faut dire</h2>
 *
 * <p><b>Sur un serveur dédié, le fichier audio n'est pas transmis.</b> Le serveur envoie le registre
 * des morceaux — titres et durées — mais pas les octets : un morceau pèse plusieurs mébioctets, et
 * les envoyer à chaque joueur à la connexion transformerait l'entrée en partie en téléchargement.
 * Chaque joueur doit donc avoir le même dossier {@code config/lanterne/disques/}. C'est une
 * limitation réelle, elle est assumée, et elle se contourne en partageant le dossier — ou en passant
 * par un paquet de ressources de serveur, que Minecraft sait déjà distribuer.
 *
 * <p>Les images, elles, sont transmises : elles pèsent cent fois moins. Voir
 * {@code content.painting.Hoard}.
 */
public final class Reel implements PackResources {
    /** Le sous-dossier, dans l'espace de noms du mod, où vivent les sons des disques. */
    public static final String SOUND_DIR = "disques";

    /** Le sous-dossier des sillons gravés en jeu. */
    public static final String BURNT_DIR = "disques/graves";

    private final PackType type;
    private final PackLocationInfo location;

    private Reel(PackType type, PackLocationInfo location) {
        this.type = type;
        this.location = location;
    }

    /**
     * Construit le paquet à donner au dépôt.
     *
     * <p>{@code required} est vrai et {@code fixedPosition} aussi : ce paquet n'est pas un choix
     * esthétique que le joueur pourrait désactiver, c'est le support de ses propres fichiers. Le
     * placer en bas ({@link Pack.Position#BOTTOM}) le laisse surchargeable par n'importe quel paquet
     * de ressources — ce qui est correct : si un joueur veut remplacer un de ces sons, il en a le
     * droit.
     */
    public static Pack pack(PackType type) {
        PackLocationInfo location = new PackLocationInfo(
                "lanterne/atelier",
                Component.literal("Lanterne — atelier"),
                PackSource.BUILT_IN,
                Optional.empty());
        // Visible plutôt que caché, et obligatoire plutôt que facultatif. Un paquet caché est, dans
        // ce moteur, prévu pour être l'enfant d'un autre ; en faire un paquet racine caché
        // fonctionnerait probablement et reposerait sur un détail non garanti. Visible et
        // verrouillé donne le même résultat — le joueur ne peut pas le désactiver — et il comprend
        // d'où viennent ses disques en lisant la liste.
        Pack.Metadata metadata = new Pack.Metadata(
                Component.literal("Les disques que tu as déposés dans config/lanterne/disques/."),
                PackCompatibility.COMPATIBLE,
                FeatureFlagSet.of(),
                java.util.List.of(),
                false);
        Reel reel = new Reel(type, location);
        return new Pack(location,
                new Pack.ResourcesSupplier() {
                    @Override
                    public PackMetadataResources openMetadata(PackLocationInfo info) {
                        return reel;
                    }

                    @Override
                    public Stream<PackResources> openResources(PackLocationInfo info, Pack.Metadata meta) {
                        return Stream.of(reel);
                    }
                },
                metadata,
                new PackSelectionConfig(true, Pack.Position.BOTTOM, true));
    }

    // --- Lecture -----------------------------------------------------------

    @Override
    public @Nullable IoSupplier<InputStream> getRootResource(String... path) {
        // Les métadonnées du paquet sont fournies directement à sa construction ; le jeu n'a donc
        // jamais besoin d'ouvrir un « pack.mcmeta », et en inventer un serait du bruit.
        return null;
    }

    @Override
    public @Nullable IoSupplier<InputStream> getResource(PackType type, Identifier location) {
        if (type != this.type || !Lanterne.ID.equals(location.getNamespace())) {
            return null;
        }
        String path = location.getPath();
        if (type == PackType.CLIENT_RESOURCES) {
            if ("sounds.json".equals(path)) {
                return bytes(soundsJson());
            }
            String burnt = "sounds/" + BURNT_DIR + "/";
            if (path.startsWith(burnt) && path.endsWith(".ogg")) {
                return burntStream(path.substring(burnt.length(), path.length() - 4));
            }
            String prefix = "sounds/" + SOUND_DIR + "/";
            if (path.startsWith(prefix) && path.endsWith(".ogg")) {
                String slug = path.substring(prefix.length(), path.length() - 4);
                Wax wax = Groove.disc(slug);
                if (wax != null && Files.isRegularFile(wax.file())) {
                    // Un flux ouvert à la demande, et non des octets lus d'avance : c'est ce qui
                    // permet au moteur sonore de lire le morceau au fur et à mesure. Un fichier de
                    // vingt mébioctets n'occupe jamais vingt mébioctets de mémoire.
                    return () -> Files.newInputStream(wax.file());
                }
            }
            return null;
        }
        if (type == PackType.SERVER_DATA && path.startsWith("jukebox_song/") && path.endsWith(".json")) {
            String slug = path.substring("jukebox_song/".length(), path.length() - 5);
            Wax wax = Groove.disc(slug);
            return wax == null ? null : bytes(songJson(wax));
        }
        return null;
    }

    @Override
    public void listResources(PackType type, String namespace, String directory, ResourceOutput output) {
        if (type != this.type || !Lanterne.ID.equals(namespace)) {
            return;
        }
        Collection<Wax> discs = Groove.catalogue();
        if (type == PackType.CLIENT_RESOURCES && startsWith(directory, "sounds")) {
            for (Wax wax : discs) {
                Identifier id = Identifier.fromNamespaceAndPath(Lanterne.ID,
                        "sounds/" + SOUND_DIR + "/" + wax.slug() + ".ogg");
                output.accept(id, () -> Files.newInputStream(wax.file()));
            }
            // TOUS les sillons, occupes ou non, et avec un fournisseur paresseux : c'est cette
            // liste que le gestionnaire de sons retient, et ce qu'il retient ici vaut pour toute la
            // session. Un sillon omis maintenant serait muet meme apres avoir ete grave.
            for (int slot = 0; slot < Studio.discSlots(); slot++) {
                final int number = slot;
                output.accept(Identifier.fromNamespaceAndPath(Lanterne.ID,
                        "sounds/" + BURNT_DIR + "/" + Slots.name(slot) + ".ogg"),
                        () -> open(number));
            }
        } else if (type == PackType.SERVER_DATA && startsWith(directory, "jukebox_song")) {
            for (Wax wax : discs) {
                Identifier id = Identifier.fromNamespaceAndPath(Lanterne.ID,
                        "jukebox_song/" + wax.slug() + ".json");
                output.accept(id, () -> new ByteArrayInputStream(songJson(wax)));
            }
            // Les sillons, toujours : ils doivent exister dans le registre AVANT la premiere
            // gravure, sans quoi il n'y aurait rien a occuper.
            for (int slot = 0; slot < Studio.discSlots(); slot++) {
                final int number = slot;
                Identifier id = Identifier.fromNamespaceAndPath(Lanterne.ID,
                        "jukebox_song/" + Slots.name(slot) + ".json");
                output.accept(id, () -> new ByteArrayInputStream(burntJson(number)));
            }
        }
    }

    /**
     * Le dossier demandé englobe-t-il celui que nous servons ?
     *
     * <p>Le gestionnaire de ressources interroge tantôt {@code "sounds"}, tantôt
     * {@code "sounds/disques"} selon l'appelant. Répondre au premier et pas au second ferait
     * disparaître les disques d'un chargeur sur deux, et le diagnostic serait pénible.
     */
    /**
     * Le fichier d'un sillon, résolu au moment de la lecture.
     *
     * <h2>C'est ici que se joue l'absence de rechargement</h2>
     *
     * <p>Le nom du sillon est fixe — {@code grave_07} existe dès le premier démarrage, occupé ou
     * non. Ce qui change en cours de partie, c'est l'<b>empreinte</b> qu'il porte, donc le fichier
     * où aller lire. Comme ce flux n'est ouvert qu'à l'instant où le morceau commence, le
     * gestionnaire de ressources n'a jamais à relire son inventaire : graver un disque change ce
     * qu'on entend sans que rien ne soit rechargé.
     *
     * <p>{@link Slots} répond des deux côtés du fil : le serveur le remplit en gravant, le client
     * en recevant le catalogue. Cette classe n'a donc pas à savoir de quel côté elle tourne.
     *
     * <h2>Le piège qui a rendu le premier graveur muet</h2>
     *
     * <p>La première version décidait <b>ici et tout de suite</b> quel fichier servir : le vrai
     * morceau si le sillon était occupé, un silence sinon. C'était faux, et d'une façon très
     * instructive.
     *
     * <p>Ce que le gestionnaire de ressources retient, c'est le {@link IoSupplier} — pas les octets.
     * Il l'appelle à chaque lecture, ce qui est exactement ce qu'on voulait. Mais il le retient
     * <b>tel qu'il était au chargement</b>, et au chargement tous les sillons sont vides : chacun
     * s'est donc vu attribuer, une fois pour toutes, le fournisseur qui rend le silence. Graver
     * remplissait bien le sillon, et le jukebox jouait consciencieusement un quart de seconde de
     * rien.
     *
     * <p>La correction tient en un déplacement : c'est <b>le corps du fournisseur</b> qui interroge
     * {@link Slots}, et non le code qui le construit. Le sillon est donc relu à chaque lecture, et un
     * morceau gravé s'entend immédiatement — sans rechargement de ressources, ce qui était tout
     * l'intérêt du mécanisme.
     *
     * <h2>Pourquoi aucun mixin n'est nécessaire POUR CELA</h2>
     *
     * <p>Le moteur sonore met bien en cache les sons <em>courts</em> :
     * {@code SoundBufferLibrary.getCompleteBuffer} garde une table de tampons décodés. Mais il ne
     * prend ce chemin que pour les sons non diffusés en flux. Les nôtres déclarent
     * {@code "stream": true}, et {@code SoundEngine} les envoie alors vers
     * {@code SoundBufferLibrary.getStream}, qui <b>n'a aucun cache</b> : il rouvre la ressource à
     * chaque lecture. Vérifié dans {@code SoundEngine} et dans {@code SoundBufferLibrary}, où seul
     * {@code getCompleteBuffer} porte une table.
     *
     * <p>Il existe bien un mixin sur cette classe — {@code mixin.SoundDecodeMixin} — mais il répond à
     * une autre question : <em>quel décodeur</em>, et non <em>quels octets</em>. La fraîcheur des
     * octets tient au fournisseur paresseux ci-dessous, et à rien d'autre.
     *
     * <h2>Un sillon vide rend du silence, et il le FAUT</h2>
     *
     * <p>La première version rendait {@code null} pour un sillon libre, en se disant qu'aucun objet
     * ne le désignait de toute façon. C'était faux, et d'une façon qui aurait tout cassé sans qu'on
     * s'en aperçoive avant la première gravure.
     *
     * <p>{@code SoundManager.validateSoundResource} vérifie l'existence du fichier <b>au chargement
     * des ressources</b>, et un son dont le fichier manque est <b>écarté du registre</b> — pas
     * seulement ignoré cette fois-ci : retiré. Le sillon serait alors définitivement muet, même une
     * fois le vrai fichier écrit, et il aurait fallu recharger toutes les ressources après chaque
     * gravure. C'est justement ce qu'on cherchait à éviter.
     *
     * <p>Un sillon vide rend donc un <b>silence d'un quart de seconde</b>, embarqué dans le mod. La
     * validation passe, le son reste au registre, et le jour où le sillon est occupé, ce même chemin
     * rend le vrai morceau — parce qu'il est résolu à la lecture et non au chargement.
     */
    private static @Nullable IoSupplier<InputStream> burntStream(String name) {
        int slot = slotOf(name);
        return slot < 0 ? null : () -> open(slot);
    }

    /**
     * Ouvre le fichier du sillon, tel qu'il est <b>en cet instant</b>.
     *
     * <p>Chaque ligne de cette méthode s'exécute au moment où le jukebox commence à jouer, et pas
     * une seconde avant. C'est la différence entre un disque audible et un disque muet.
     */
    private static InputStream open(int slot) throws IOException {
        String hash = Slots.hashAt(slot);
        if (!hash.isEmpty()) {
            Path file = Groove.cachedFile(hash);
            if (Files.isRegularFile(file)) {
                return Files.newInputStream(file);
            }
        }
        return silence();
    }

    /** Le numéro d'un sillon d'après son nom, ou moins un si ce n'en est pas un. */
    private static int slotOf(String name) {
        for (int slot = 0; slot < Studio.discSlots(); slot++) {
            if (Slots.name(slot).equals(name)) {
                return slot;
            }
        }
        return -1;
    }

    /**
     * Le silence embarqué, lu depuis l'archive du mod.
     *
     * <p>Encodé une fois pour toutes et versionné avec le code : trois kibioctets de Vorbis valide.
     * L'écrire à la main aurait supposé d'encoder les livres de codes Vorbis, ce qui n'est pas
     * raisonnable ; le générer au démarrage aurait supposé un encodeur, et le mod n'en embarque
     * aucun — il décode, il n'encode jamais.
     */
    private static InputStream silence() throws IOException {
        InputStream in = Reel.class.getResourceAsStream("/assets/lanterne/sounds/silence.ogg");
        if (in == null) {
            throw new IOException("silence.ogg absent de l'archive du mod");
        }
        return in;
    }

    private static boolean startsWith(String directory, String ours) {
        return ours.startsWith(directory) || directory.startsWith(ours);
    }

    /**
     * Toujours notre espace de noms.
     *
     * <p>La première version ne le déclarait que si le dossier contenait des fichiers. C'était
     * juste tant que le dossier était la seule source ; ce ne l'est plus. Les sillons existent
     * même quand le dossier est vide — c'est précisément ce qui permet de graver un disque sur un
     * serveur où personne n'a jamais rien déposé.
     */
    @Override
    public Set<String> getNamespaces(PackType type) {
        return type == this.type ? Set.of(Lanterne.ID) : Set.of();
    }

    @Override
    public <T> @Nullable T getMetadataSection(MetadataSectionType<T> serializer) throws IOException {
        return null;
    }

    @Override
    public PackLocationInfo location() {
        return this.location;
    }

    @Override
    public void close() {
        // Rien à fermer : les flux sont ouverts par l'appelant et fermés par lui.
    }

    // --- Composition des fichiers -----------------------------------------

    private static IoSupplier<InputStream> bytes(byte[] data) {
        return () -> new ByteArrayInputStream(data);
    }

    /**
     * Le {@code sounds.json}, composé à partir du catalogue.
     *
     * <p>{@code "stream": true} est le réglage qui compte. Sans lui, Minecraft charge le fichier
     * entier en mémoire et le décode d'un coup avant de jouer la première note : un morceau de cinq
     * minutes, c'est cinquante mébioctets de son décompressé et une seconde de gel. Avec lui, le
     * moteur lit par tranches pendant la lecture, et le coût mémoire est celui d'un tampon de
     * quelques dizaines de kilo-octets.
     *
     * <p>C'est aussi ce que vanilla fait pour ses propres disques, et pour la même raison.
     */
    private static byte[] soundsJson() {
        JsonObject root = new JsonObject();
        // Les sillons d'abord, et declares MEME VIDES : un son absent de sounds.json au
        // chargement ne pourra jamais etre joue ensuite, quoi qu'on grave par la suite.
        for (int slot = 0; slot < Studio.discSlots(); slot++) {
            JsonObject sound = new JsonObject();
            sound.add("name", new JsonPrimitive(
                    Lanterne.ID + ":" + BURNT_DIR + "/" + Slots.name(slot)));
            sound.add("stream", new JsonPrimitive(true));
            JsonArray sounds = new JsonArray();
            sounds.add(sound);
            JsonObject entry = new JsonObject();
            entry.add("sounds", sounds);
            root.add(BURNT_DIR + "/" + Slots.name(slot), entry);
        }
        for (Wax wax : Groove.catalogue()) {
            JsonObject sound = new JsonObject();
            sound.add("name", new JsonPrimitive(Lanterne.ID + ":" + SOUND_DIR + "/" + wax.slug()));
            sound.add("stream", new JsonPrimitive(true));
            JsonArray sounds = new JsonArray();
            sounds.add(sound);
            JsonObject entry = new JsonObject();
            entry.add("sounds", sounds);
            entry.add("subtitle", new JsonPrimitive(wax.name()));
            root.add(SOUND_DIR + "/" + wax.slug(), entry);
        }
        return root.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Le {@code jukebox_song} d'un disque.
     *
     * <p>Le son est écrit <b>en toutes lettres</b> plutôt que par identifiant : c'est ce qui évite
     * d'avoir à l'enregistrer dans le registre statique des sons, scellé bien avant que ce fichier
     * n'existe. Voir le Javadoc de classe.
     */
    /**
     * Le {@code jukebox_song} d'un sillon.
     *
     * <p>La durée est celle que le sillon DÉCLARE, pas celle du morceau : c'est elle qui décide
     * quand le jukebox s'arrête, et elle ne peut plus changer une fois le registre bâti. Voir
     * {@link Slots} pour la répartition géométrique qui réduit le silence résiduel à quelques
     * pour cent.
     *
     * <p>La description est générique, parce qu'elle est figée elle aussi. Le vrai titre du disque
     * est porté par le composant de nom de l'objet, qui n'a pas cette contrainte.
     */
    private static byte[] burntJson(int slot) {
        JsonObject sound = new JsonObject();
        sound.add("sound_id", new JsonPrimitive(
                Lanterne.ID + ":" + BURNT_DIR + "/" + Slots.name(slot)));

        JsonObject description = new JsonObject();
        description.add("text", new JsonPrimitive("disque gravé"));
        description.add("italic", new JsonPrimitive(true));

        JsonObject root = new JsonObject();
        root.add("sound_event", sound);
        root.add("description", description);
        root.add("length_in_seconds", new JsonPrimitive(Slots.length(slot)));
        root.add("comparator_output", new JsonPrimitive(1 + slot % 15));
        return root.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] songJson(Wax wax) {
        JsonObject sound = new JsonObject();
        sound.add("sound_id", new JsonPrimitive(Lanterne.ID + ":" + SOUND_DIR + "/" + wax.slug()));

        JsonObject description = new JsonObject();
        description.add("text", new JsonPrimitive(wax.name()));

        JsonObject root = new JsonObject();
        root.add("sound_event", sound);
        root.add("description", description);
        root.add("length_in_seconds", new JsonPrimitive(wax.seconds()));
        root.add("comparator_output", new JsonPrimitive(wax.comparatorOutput()));
        return root.toString().getBytes(StandardCharsets.UTF_8);
    }
}
