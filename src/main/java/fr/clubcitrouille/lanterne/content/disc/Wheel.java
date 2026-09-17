package fr.clubcitrouille.lanterne.content.disc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import net.minecraft.client.Minecraft;

import org.jspecify.annotations.Nullable;

import fr.clubcitrouille.lanterne.Lanterne;
import fr.clubcitrouille.lanterne.content.painting.Mill;
import fr.clubcitrouille.lanterne.content.painting.Studio;

/**
 * Le plateau : la part cliente des disques gravés — préparer, monter, recevoir.
 *
 * <h2>Le jumeau du magot des tableaux</h2>
 *
 * <p>Ce fichier fait pour l'audio exactement ce que {@code content.painting.Hoard} fait pour les
 * images, et il le fait de la même façon : un cache nommé par l'empreinte du contenu, un fil de fond
 * pour tout ce qui coûte, et la règle « on ne transmet que ce que l'autre n'a pas ».
 *
 * <p>La duplication est assumée. Un mécanisme commun aux deux aurait dû vivre dans l'un des deux
 * paquets — il n'y a pas de paquet commun autorisé — et aurait fait dépendre les disques des
 * tableaux pour une raison qui n'a rien à voir avec eux. Quatre-vingts lignes répétées valent mieux
 * qu'un couplage faux. L'empreinte, elle, est bien partagée : {@code Mill.fingerprint} ne connaît
 * rien aux images, et deux calculs différents auraient été une source de bogues.
 *
 * <h2>Ce que le client fait, et que le serveur ne fait jamais</h2>
 *
 * <p>Le téléchargement et la mesure du morceau se passent <b>ici</b>. Le serveur ne voit jamais une
 * adresse, pour la même raison que pour les images : lui faire suivre un lien fourni par un joueur,
 * c'est lui faire sonder son propre réseau. Voir {@code content.painting.Reach}.
 */
public final class Wheel {
    /** Le fil qui mesure et qui lit. Un seul, en démon, de priorité basse. */
    private static final ExecutorService LATHE = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Lanterne-plateau");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });

    /** Les morceaux préparés par ce joueur, en attente d'être réclamés par le serveur. */
    private static final Map<String, byte[]> OFFERS = new HashMap<>();

    /** Les empreintes déjà demandées au serveur, pour ne pas redemander. */
    private static final Set<String> ASKED = new HashSet<>();

    /** Les réassemblages en cours. */
    private static final Map<String, byte[]> BUILDING = new HashMap<>();

    private static final Map<String, Integer> FILLED = new HashMap<>();

    private static String giving;
    private static byte[] givingData;
    private static int givingOffset;

    private Wheel() {}

    // --- Ce que le joueur apporte -----------------------------------------

    /**
     * Prépare un morceau : reconnaissance du format, mesure, empreinte, cache.
     *
     * <p>Plus rien n'est réencodé : les octets gardés sont ceux que le joueur a apportés, étiquetés
     * du format reconnu dans leur signature. Voir {@link Press}, qui explique pourquoi décoder à la
     * lecture vaut mieux que convertir à l'import.
     *
     * <p>Tout se passe quand même sur le fil de fond. Mesurer un MP3 à débit variable sans entête
     * Xing demande de parcourir toutes ses trames, et un détour par {@code ffmpeg} — le seul qui
     * reste, pour les formats exotiques — peut durer plusieurs secondes. Le retour se fait par les
     * deux consommateurs, appelés depuis ce même fil : c'est à l'écran de revenir au fil du client
     * s'il touche à l'interface.
     */
    public static void prepare(byte[] raw, Consumer<Post.Vinyl> ready, Consumer<String> failed) {
        LATHE.submit(() -> {
            // L'empreinte de la SOURCE : elle ne sert qu'au détour par ffmpeg, seul cas où les octets
            // gardés diffèrent de ceux qu'on a reçus. Voir « remember ».
            String source = Mill.fingerprint(raw);
            Press.Cut cut = recall(source);
            if (cut == null) {
                try {
                    cut = Press.grind(raw, Studio.discMaxBytes(), Studio.discMaxSeconds());
                } catch (IOException refusal) {
                    failed.accept("Refusé : " + refusal.getMessage());
                    return;
                } catch (RuntimeException broken) {
                    Lanterne.LOG.warn("[ATELIER] préparation audio ratée", broken);
                    failed.accept("Fichier audio illisible.");
                    return;
                }
            }
            String hash = Mill.fingerprint(cut.data());
            try {
                Path target = Groove.cachedFile(hash, cut.grain());
                Files.createDirectories(target.getParent());
                if (!Files.isRegularFile(target)) {
                    Files.write(target, cut.data());
                }
                if (cut.converted()) {
                    remember(source, hash);
                }
            } catch (IOException problem) {
                Lanterne.LOG.warn("[ATELIER] morceau non mis en cache : {}", problem.getMessage());
            }
            byte[] data = cut.data();
            Post.Vinyl posted = new Post.Vinyl(-1, hash, "", cut.seconds());
            // OFFERS est une HashMap ordinaire, lue et écrite par ailleurs uniquement depuis le fil
            // client (grant, pump) : la remplir ici, depuis le fil de fond LATHE, la ferait courir
            // contre ce fil sans verrou — exactement le bogue que Hoard.prepare évite déjà en
            // repassant par Minecraft.execute avant de toucher son équivalent d'OFFERS.
            Minecraft.getInstance().execute(() -> {
                OFFERS.clear();
                OFFERS.put(hash, data);
                ready.accept(posted);
            });
        });
    }

    /**
     * Le morceau déjà converti à partir de cette source, s'il est encore là.
     *
     * <h2>Ce que cette table sert encore, et ce qu'elle a cessé de servir</h2>
     *
     * <p>Tant que <b>tout</b> passait par {@code ffmpeg}, cette table était indispensable, et pour une
     * raison instructive : un conteneur Ogg porte, dans l'entête de chaque page, un « numéro de série
     * de flux » qu'un encodeur tire au hasard. Deux conversions du même MP3 produisaient donc deux
     * fichiers de taille identique et d'octets différents — donc deux empreintes, donc deux sillons
     * consommés pour un seul morceau, et une retransmission complète à tout le serveur. Le journal du
     * commanditaire porte exactement cela : {@code 4 504 492} octets les deux fois, deux empreintes,
     * les sillons 45 puis 46.
     *
     * <p>Ce problème a disparu tout seul le jour où plus rien n'a été réencodé : les octets gardés
     * sont ceux du fichier, donc l'empreinte du morceau <em>est</em> celle de la source, et importer
     * deux fois le même fichier retombe sur le même sillon par construction.
     *
     * <p>La table reste pour le seul cas où des octets sont encore fabriqués : le détour par
     * {@code ffmpeg} pour les formats exotiques. Elle y rend le même service qu'avant — même sillon,
     * et plusieurs secondes de conversion économisées.
     */
    private static Press.@Nullable Cut recall(String source) {
        java.util.Properties index = index();
        String known = index.getProperty(source);
        if (known == null) {
            return null;
        }
        Path file = Groove.cachedFile(known);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            byte[] data = Files.readAllBytes(file);
            Press.Reading reading = Press.read(file);
            Lanterne.LOG.info("[ATELIER] morceau déjà converti — conversion évitée.");
            return new Press.Cut(data, Press.grain(file), reading.seconds(), true);
        } catch (IOException stale) {
            return null;
        }
    }

    private static void remember(String source, String hash) {
        java.util.Properties index = index();
        if (hash.equals(index.getProperty(source))) {
            return;
        }
        index.setProperty(source, hash);
        try (java.io.OutputStream out = Files.newOutputStream(indexFile())) {
            index.store(out, "Lanterne - conversions deja faites : <empreinte source> = <empreinte ogg>."
                    + " Supprimer ce fichier ne fait que refaire des conversions.");
        } catch (IOException problem) {
            Lanterne.LOG.warn("[ATELIER] index des conversions non écrit : {}", problem.getMessage());
        }
    }

    private static Path indexFile() {
        return Groove.cacheFolder().resolve("conversions.properties");
    }

    private static java.util.Properties index() {
        java.util.Properties properties = new java.util.Properties();
        Path file = indexFile();
        if (Files.isRegularFile(file)) {
            try (java.io.InputStream in = Files.newInputStream(file)) {
                properties.load(in);
            } catch (IOException ignored) {
                // Un index illisible n'est pas une panne : on reconvertit, une fois.
            }
        }
        return properties;
    }

    /**
     * Le serveur réclame le morceau que ce joueur vient de préparer.
     *
     * <p>Seul {@link #OFFERS} est consulté : on ne remonte jamais un morceau qu'on a simplement reçu
     * ou trouvé dans son cache. Un serveur qui demanderait une empreinte quelconque n'obtiendrait
     * rien — il n'y a aucune raison légitime pour qu'il lise le cache d'un joueur.
     */
    public static void grant(Post.Want want) {
        byte[] data = OFFERS.get(want.hash());
        if (data == null || giving != null) {
            return;
        }
        giving = want.hash();
        givingData = data;
        givingOffset = 0;
    }

    /** Pousse quelques tranches vers le serveur, une fois par tick. */
    public static void pump() {
        if (giving == null) {
            return;
        }
        int size = Studio.shardBytes();
        for (int sent = 0; sent < Studio.shardsPerTick() && giving != null; sent++) {
            int length = Math.min(size, givingData.length - givingOffset);
            byte[] slice = new byte[length];
            System.arraycopy(givingData, givingOffset, slice, 0, length);
            net.neoforged.neoforge.client.network.ClientPacketDistributor.sendToServer(
                    new Post.Gift(giving, givingOffset, givingData.length, slice));
            givingOffset += length;
            if (givingOffset >= givingData.length) {
                OFFERS.remove(giving);
                giving = null;
                givingData = null;
                givingOffset = 0;
            }
        }
    }

    // --- Ce que le serveur annonce ----------------------------------------

    /**
     * Le catalogue des sillons vient d'arriver.
     *
     * <h2>Pourquoi le client garde la même table que le serveur</h2>
     *
     * <p>{@link Slots} est renseigné des deux côtés, et c'est {@link Reel} qui s'en sert pour
     * répondre « quel fichier joue le sillon numéro sept ». Un serveur dédié le remplit en gravant ;
     * un client le remplit en recevant ce catalogue. La même classe répond donc à la même question
     * des deux côtés, sans qu'aucune ne connaisse l'autre.
     *
     * <p>C'est ce qui permet à un morceau gravé de se jouer <b>sans rechargement de ressources</b> :
     * le fichier n'est ouvert qu'à la lecture, et le chemin est recalculé à ce moment-là.
     */
    public static void accept(Post.Roll roll) {
        // On remplit le MIROIR, jamais la table du serveur : en partie solo elles vivent dans la
        // même machine virtuelle, et écraser celle du serveur effaçait les gravures. Voir Slots.
        Slots.forgetMirror();
        for (Post.Vinyl cut : roll.cuts()) {
            Slots.mirror(cut.slot(), new Slots.Cut(cut.hash(), cut.title(), cut.seconds(), ""));
        }
        int missing = 0;
        for (Post.Vinyl cut : roll.cuts()) {
            if (cut.hash().isEmpty() || Files.isRegularFile(Groove.cachedFile(cut.hash()))) {
                continue;
            }
            if (ASKED.add(cut.hash())) {
                missing++;
                net.neoforged.neoforge.client.network.ClientPacketDistributor.sendToServer(
                        new Post.Wish(cut.hash()));
            }
        }
        if (missing > 0) {
            Lanterne.LOG.info("[ATELIER] {} morceau(x) à recevoir.", missing);
        }
    }

    /**
     * Une tranche de morceau arrive.
     *
     * <p>Mêmes bornes que pour les images, et pour les mêmes raisons : un tampon dimensionné sur une
     * taille annoncée est un tampon dimensionné par l'autre bout du fil. L'empreinte est recalculée
     * à la fin — sans quoi le cache, qui tient pour bon tout ce qu'il contient, garderait un fichier
     * faux pour toujours.
     */
    public static void absorb(Post.Shard shard) {
        String hash = shard.hash();
        if (!Mill.plausible(hash) || shard.size() < 1 || shard.size() > Studio.discMaxBytes()
                || shard.offset() < 0 || shard.data().length == 0) {
            return;
        }
        byte[] buffer = BUILDING.get(hash);
        if (buffer == null) {
            buffer = new byte[shard.size()];
            BUILDING.put(hash, buffer);
            FILLED.put(hash, 0);
        }
        if (buffer.length != shard.size() || shard.offset() + shard.data().length > buffer.length) {
            BUILDING.remove(hash);
            FILLED.remove(hash);
            return;
        }
        System.arraycopy(shard.data(), 0, buffer, shard.offset(), shard.data().length);
        int filled = FILLED.merge(hash, shard.data().length, Integer::sum);
        if (filled < buffer.length) {
            return;
        }

        BUILDING.remove(hash);
        FILLED.remove(hash);
        byte[] music = buffer;
        LATHE.submit(() -> {
            // ASKED est une HashSet ordinaire, touchée par ailleurs uniquement depuis le fil client
            // (accept) : chaque retrait ci-dessous repasse donc par Minecraft.execute plutôt que de
            // la muter directement sur ce fil de fond.
            if (!Mill.fingerprint(music).equals(hash)) {
                Lanterne.LOG.warn("[ATELIER] morceau « {} » reçu abîmé.", hash);
                Minecraft.getInstance().execute(() -> ASKED.remove(hash));
                return;
            }
            // Le format est relu sur les octets reçus : le serveur ne l'annonce pas, et il n'aurait
            // servi à rien qu'il l'annonce puisqu'on peut le voir.
            Press.Grain grain = Press.grain(music);
            if (!grain.playable()) {
                Lanterne.LOG.warn("[ATELIER] morceau « {} » reçu dans un format illisible.", hash);
                Minecraft.getInstance().execute(() -> ASKED.remove(hash));
                return;
            }
            try {
                Path target = Groove.cachedFile(hash, grain);
                Files.createDirectories(target.getParent());
                Files.write(target, music);
                Lanterne.LOG.info("[ATELIER] morceau « {} » reçu ({} Kio, {}).", hash,
                        music.length / 1024, grain.label());
            } catch (IOException problem) {
                Lanterne.LOG.warn("[ATELIER] morceau « {} » non écrit : {}", hash, problem.getMessage());
                Minecraft.getInstance().execute(() -> ASKED.remove(hash));
            }
        });
    }

    /** Oublie ce qui est propre à une partie. Le cache disque, lui, ne s'oublie jamais. */
    public static void forget() {
        OFFERS.clear();
        ASKED.clear();
        BUILDING.clear();
        FILLED.clear();
        giving = null;
        givingData = null;
        givingOffset = 0;
        Slots.forgetMirror();
    }

    /** Les morceaux encore attendus, pour l'écran d'état. */
    public static List<String> pending() {
        return new ArrayList<>(BUILDING.keySet());
    }

    /** Branche le client. Appelé derrière une garde de distribution, et de nulle part ailleurs. */
    public static void install() {
        Groove.clientSide(new Groove.Turntable() {
            @Override
            public void open(Post.Open payload) {
                Minecraft.getInstance().gui.setScreen(new Lathe(payload.pos()));
            }

            @Override
            public void want(Post.Want payload) {
                grant(payload);
            }

            @Override
            public void roll(Post.Roll payload) {
                accept(payload);
            }

            @Override
            public void shard(Post.Shard payload) {
                absorb(payload);
            }

            @Override
            public void refresh() {
                Groove.forget();
                Minecraft.getInstance().reloadResourcePacks();
            }
        });
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.client.event.ClientTickEvent.Post event) -> pump());
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut event)
                        -> forget());
    }
}
