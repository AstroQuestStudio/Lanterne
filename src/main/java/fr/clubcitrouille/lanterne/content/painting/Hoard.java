package fr.clubcitrouille.lanterne.content.painting;

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

import com.mojang.blaze3d.platform.NativeImage;

import net.minecraft.client.Minecraft;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * Le magot : ce que le client garde sur son disque, pour ne jamais redemander deux fois.
 *
 * <h2>La promesse, et comment elle est tenue</h2>
 *
 * <p>Une image n'est transmise qu'une seule fois, jamais deux — pas seulement par session, mais
 * <b>pour toujours</b>. Reconnecté demain, réinstallé le mois prochain, passé d'un serveur à un
 * autre qui héberge les mêmes images : le client les reconnaît et ne demande rien.
 *
 * <p>Le mécanisme tient en une phrase : le fichier est nommé par l'empreinte de son contenu. Savoir
 * si l'on possède une image, c'est donc tester l'existence d'un fichier — pas de base de données, pas
 * d'index à tenir à jour, pas de désynchronisation possible entre ce que l'index prétend et ce que le
 * disque contient. Un fichier effacé à la main est simplement redemandé.
 *
 * <p>Le dossier est le même que celui du serveur ({@code config/lanterne/tableaux/.cache/}). En
 * partie solo, cela veut dire que <b>rien ne transite du tout</b> : le serveur intégré vient d'y
 * écrire les images préparées, le client les y trouve, et le protocole réseau ne sert jamais. Ce
 * n'est pas un cas particulier traité à part — c'est la même ligne de code qui, par construction,
 * donne le bon résultat des deux côtés.
 *
 * <h2>Où chaque chose se passe</h2>
 *
 * <p>Trois fils, et la frontière entre eux est la règle la plus importante de ce fichier :
 *
 * <ul>
 *   <li><b>Le fil réseau</b> reçoit les segments. Il ne fait que recopier des octets dans un tampon
 *       — le travail est ramené sur le fil client par {@code enqueueWork} avant même d'arriver
 *       ici.</li>
 *   <li><b>Un fil de fond</b> lit le disque et décode les PNG. C'est le poste coûteux — quelques
 *       dizaines de millisecondes par image — et le laisser sur le fil de rendu ferait sauter une
 *       image à chaque tableau découvert.</li>
 *   <li><b>Le fil de rendu</b>, et lui seul, pose dans la mosaïque. L'envoi vers la carte graphique
 *       n'est pas sûr ailleurs. Le passage de relais se fait par {@code Minecraft.execute}.</li>
 * </ul>
 */
@OnlyIn(Dist.CLIENT)
public final class Hoard {
    /**
     * Le fil qui décode.
     *
     * <p>Un seul, en démon, de priorité basse. Un seul parce que le décodage est court et que la
     * contention sur le disque coûterait plus que le parallélisme ne rapporterait ; en démon pour ne
     * pas retenir la machine virtuelle à la fermeture ; de priorité basse parce que rien ici n'est
     * plus urgent que d'afficher une image de jeu.
     */
    private static final ExecutorService KITCHEN = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Lanterne-magot");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });

    /** Les empreintes annoncées par le serveur. */
    private static final Set<String> WANTED = new HashSet<>();

    /** Les empreintes déjà demandées, pour ne pas redemander pendant le transfert. */
    private static final Set<String> ASKED = new HashSet<>();

    /** Les réassemblages en cours. */
    private static final Map<String, Assembly> BUILDING = new HashMap<>();

    /**
     * Taille d'image au-delà de laquelle on refuse d'allouer, en octets.
     *
     * <p>Soixante-quatre mébioctets, soit bien plus qu'une image ré-échantillonnée à 4096 pixels ne
     * pourra jamais peser en PNG. Ce n'est pas une limite de fonctionnement : c'est le garde-fou qui
     * fait qu'un serveur — ou quelque chose qui se fait passer pour lui — ne peut pas annoncer deux
     * gigaoctets et provoquer un manque de mémoire chez le joueur avant même d'avoir envoyé un octet.
     */
    private static final int CEILING = 64 * 1024 * 1024;

    /**
     * Les images que ce joueur vient de préparer et que le serveur n'a peut-être pas.
     *
     * <p>Elles n'y restent que le temps d'un envoi. Les garder plus longtemps serait doubler en
     * mémoire ce qui est déjà sur le disque — le cache les contient dès leur préparation.
     */
    private static final Map<String, byte[]> OFFERS = new HashMap<>();

    /** L'envoi en cours vers le serveur, s'il y en a un. */
    private static String giving;

    private static byte[] givingData;

    private static int givingOffset;

    /** Ce que le serveur autorise. Valeurs larges tant qu'il n'a rien dit. */
    private static PaintingRoll.Rules rules = PaintingRoll.Rules.OPEN;

    private Hoard() {}

    public static PaintingRoll.Rules rules() {
        return rules;
    }

    /** Un réassemblage : le tampon, et combien d'octets y sont déjà tombés. */
    private static final class Assembly {
        private byte[] buffer;
        private int received;
    }

    /**
     * Le catalogue du serveur vient d'arriver.
     *
     * <p>C'est ici que se décide, image par image, s'il faut demander quoi que ce soit. L'ordre des
     * trois tests n'est pas indifférent : le moins cher d'abord.
     */
    public static void accept(List<Plate> plates, PaintingRoll.Rules law) {
        Easel.echo(plates);
        rules = law;
        WANTED.clear();
        for (Plate plate : plates) {
            WANTED.add(plate.hash());
        }
        int missing = 0;
        for (Plate plate : plates) {
            if (Mosaic.holds(plate.hash())) {
                continue;
            }
            if (Files.isRegularFile(Easel.cachedFile(plate.hash()))) {
                fetchFromDisk(plate.hash());
                continue;
            }
            if (ASKED.add(plate.hash())) {
                missing++;
                net.neoforged.neoforge.client.network.ClientPacketDistributor.sendToServer(
                        new PaintingWish(plate.hash()));
            }
        }
        Lanterne.LOG.info("[ATELIER] {} tableau(x) annoncé(s), {} à recevoir.",
                plates.size(), missing);
    }

    /**
     * Repose toutes les images connues après une reconstruction de la mosaïque.
     *
     * <p>Appelée par {@link Mosaic#get()} quand la texture a été fermée sous nos pieds — un
     * rechargement de ressources peut le faire. Les images sont sur le disque : il n'y a rien à
     * redemander au serveur, seulement à relire. C'est exactement le genre de situation où le choix
     * de nommer les fichiers par leur empreinte se paie tout seul.
     */
    public static void restock() {
        // On retire d'abord la marque « déjà demandé » : elle dit « cette image est en route ou
        // posée », et après une reconstruction de la mosaïque elle n'est plus posée. Sans cela, la
        // relecture serait refusée par le garde-fou anti-doublon et l'écran resterait vide.
        ASKED.clear();
        for (String hash : new ArrayList<>(WANTED)) {
            if (Files.isRegularFile(Easel.cachedFile(hash))) {
                fetchFromDisk(hash);
            }
        }
    }

    /** Oublie tout ce qui est propre à une partie — mais rien de ce qui est sur le disque. */
    public static void forget() {
        WANTED.clear();
        ASKED.clear();
        BUILDING.clear();
        OFFERS.clear();
        giving = null;
        givingData = null;
        givingOffset = 0;
        rules = PaintingRoll.Rules.OPEN;
        Mosaic.dispose();
    }

    // --- Ce que le joueur apporte -----------------------------------------

    /**
     * Prépare une image fournie par le joueur, puis la pose dans la mosaïque.
     *
     * <h2>Où chaque morceau s'exécute, et pourquoi c'est écrit ici plutôt que dans l'écran</h2>
     *
     * <p>Réduire une image de quatre mille pixels prend des dizaines de millisecondes — assez pour
     * qu'une image de jeu saute, et le joueur regarde justement l'écran à ce moment-là. Le travail
     * va donc sur le fil de fond, celui qui décode déjà les images reçues du serveur : un seul fil
     * pour tout ce qui est lourd et rien à arbitrer entre eux.
     *
     * <p>Le résultat est écrit dans le cache disque <b>immédiatement</b>, avant même que le serveur
     * n'ait accepté. C'est volontaire : si le serveur la connaît déjà, il n'y aura aucun envoi et le
     * fichier sera déjà là ; s'il la refuse, un fichier de plus dans un cache indexé par empreinte ne
     * gêne personne et servira peut-être un jour.
     *
     * @param ready appelé sur le fil de fond quand l'image est posée ; l'appelant doit revenir
     *     lui-même au fil du client s'il touche à l'interface.
     * @param failed appelé avec un message lisible par un humain.
     */
    public static void prepare(byte[] raw, String name, int maxSide,
            java.util.function.Consumer<Plate> ready, java.util.function.Consumer<String> failed) {
        KITCHEN.submit(() -> {
            Mill.Ground ground;
            try {
                ground = Mill.grind(raw, name, maxSide);
            } catch (IOException refusal) {
                failed.accept("Refusée : " + refusal.getMessage());
                return;
            } catch (RuntimeException broken) {
                Lanterne.LOG.warn("[ATELIER] préparation ratée", broken);
                failed.accept("Image illisible.");
                return;
            }
            String hash = ground.plate().hash();
            try {
                Path target = Easel.cachedFile(hash);
                Files.createDirectories(target.getParent());
                if (!Files.isRegularFile(target)) {
                    Files.write(target, ground.png());
                }
            } catch (IOException problem) {
                Lanterne.LOG.warn("[ATELIER] image « {} » non mise en cache : {}",
                        hash, problem.getMessage());
            }

            NativeImage image;
            try {
                image = NativeImage.read(ground.png());
            } catch (IOException impossible) {
                failed.accept("Image illisible après préparation.");
                return;
            }
            Minecraft.getInstance().execute(() -> {
                // Le serveur ne réclamera peut-être jamais ces octets — il connaît souvent déjà
                // l'image. Sans ce ménage, un joueur qui essaie dix images d'affilée garderait dix
                // tampons en mémoire jusqu'à sa déconnexion. On ne garde que la dernière offre en
                // attente : le cache disque conserve tout le reste.
                if (OFFERS.size() > 1 && !OFFERS.containsKey(hash)) {
                    OFFERS.keySet().removeIf(kept -> !kept.equals(giving));
                }
                OFFERS.put(hash, ground.png());
                ASKED.add(hash);
                WANTED.add(hash);
                if (!Mosaic.lay(hash, image)) {
                    failed.accept("La mosaïque est pleine — augmente « mosaique_cote ».");
                    return;
                }
                ready.accept(ground.plate());
            });
        });
    }

    /**
     * Le serveur réclame une image que ce joueur vient de choisir.
     *
     * <p>Le tampon n'est pris que dans {@link #OFFERS} : on ne remonte <b>jamais</b> une image qu'on
     * a simplement reçue du serveur ou lue dans le cache. Sans cette règle, un serveur pourrait
     * demander n'importe quelle empreinte et se faire renvoyer le contenu du cache d'un joueur, ce
     * qui n'aurait aucune utilité légitime.
     */
    public static void grant(PaintingWant want) {
        byte[] data = OFFERS.get(want.hash());
        if (data == null || giving != null) {
            return;
        }
        giving = want.hash();
        givingData = data;
        givingOffset = 0;
    }

    /**
     * Pousse quelques tranches vers le serveur, une fois par tick.
     *
     * <p>Même cadence que dans l'autre sens, et pour la même raison : ce qui compte n'est pas le
     * débit moyen mais le travail fait pendant une image. Le plafond est celui des réglages du
     * client, et le serveur ne s'y fie pas — il compte les octets qu'il reçoit.
     */
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
                    new PaintingGift(giving, givingOffset, givingData.length, slice));
            givingOffset += length;
            if (givingOffset >= givingData.length) {
                // Le tampon est rendu dès la dernière tranche : le cache disque en garde une copie,
                // et le serveur en aura la sienne. Le tenir plus longtemps serait un troisième
                // exemplaire de la même image, en mémoire, pour rien.
                OFFERS.remove(giving);
                giving = null;
                givingData = null;
                givingOffset = 0;
            }
        }
    }

    /**
     * Un segment vient d'arriver.
     *
     * <h2>Ce dont on se méfie</h2>
     *
     * <p>Un client fait confiance à son serveur bien plus qu'un serveur à son client — mais pas au
     * point de le laisser allouer de la mémoire sur commande. Le nombre de segments annoncé est
     * borné, l'indice est vérifié, et un segment qui déborderait du tampon fait abandonner tout le
     * réassemblage plutôt que d'agrandir quoi que ce soit.
     *
     * <p>À la fin, l'empreinte est <b>recalculée</b> et comparée. C'est la seule vérification qui
     * compte vraiment : elle attrape d'un coup la corruption, la troncature, l'interversion de deux
     * segments et l'image qui ne serait pas celle annoncée. Une image dont l'empreinte ne tombe pas
     * juste n'est pas écrite sur le disque — sans quoi on y installerait un fichier erroné que le
     * cache tiendrait ensuite pour bon, à jamais.
     */
    public static void absorb(PaintingShard shard) {
        String hash = shard.hash();
        if (!Mill.plausible(hash) || shard.size() < 1 || shard.size() > CEILING
                || shard.offset() < 0 || shard.data().length == 0) {
            return;
        }
        Assembly assembly = BUILDING.get(hash);
        if (assembly == null) {
            assembly = new Assembly();
            assembly.buffer = new byte[shard.size()];
            BUILDING.put(hash, assembly);
        }
        if (assembly.buffer.length != shard.size()
                || shard.offset() + shard.data().length > assembly.buffer.length) {
            // Taille annoncée incohérente d'un segment à l'autre, ou tranche qui déborde : on jette
            // tout plutôt que d'agrandir un tampon sur ordre de l'autre bout du fil.
            BUILDING.remove(hash);
            return;
        }
        System.arraycopy(shard.data(), 0, assembly.buffer, shard.offset(), shard.data().length);
        assembly.received += shard.data().length;
        if (assembly.received < assembly.buffer.length) {
            return;
        }

        BUILDING.remove(hash);
        byte[] png = assembly.buffer;
        KITCHEN.submit(() -> {
            if (!Mill.fingerprint(png).equals(hash)) {
                Lanterne.LOG.warn("[ATELIER] image « {} » reçue abîmée : empreinte différente.", hash);
                ASKED.remove(hash);
                return;
            }
            try {
                Path target = Easel.cachedFile(hash);
                Files.createDirectories(target.getParent());
                Files.write(target, png);
            } catch (IOException problem) {
                // Le cache est un confort, pas une nécessité : si le disque refuse, l'image
                // s'affiche quand même pour cette session et sera redemandée à la prochaine.
                Lanterne.LOG.warn("[ATELIER] image « {} » non mise en cache : {}",
                        hash, problem.getMessage());
            }
            decode(hash, png);
        });
    }

    /** Relit une image déjà sur le disque, sur le fil de fond. */
    private static void fetchFromDisk(String hash) {
        if (!ASKED.add(hash)) {
            return;
        }
        KITCHEN.submit(() -> {
            try {
                decode(hash, Files.readAllBytes(Easel.cachedFile(hash)));
            } catch (IOException problem) {
                Lanterne.LOG.warn("[ATELIER] image « {} » illisible sur le disque : {}",
                        hash, problem.getMessage());
                ASKED.remove(hash);
            }
        });
    }

    /**
     * Décode le PNG puis renvoie la pose au fil de rendu.
     *
     * <p>{@code NativeImage} alloue hors du tas de la machine virtuelle : l'image est donc fermée
     * par {@link Mosaic#lay}, qui en devient propriétaire. Si la pose n'a pas lieu — fil de rendu
     * arrêté, monde quitté entre-temps — l'image serait perdue sans être fermée ; c'est pourquoi le
     * chemin d'erreur du décodage la ferme lui-même.
     */
    private static void decode(String hash, byte[] png) {
        NativeImage image;
        try {
            image = NativeImage.read(png);
        } catch (IOException problem) {
            Lanterne.LOG.warn("[ATELIER] image « {} » indécodable : {}", hash, problem.getMessage());
            ASKED.remove(hash);
            return;
        }
        Minecraft client = Minecraft.getInstance();
        client.execute(() -> {
            if (!Mosaic.lay(hash, image)) {
                ASKED.remove(hash);
            }
        });
    }
}
