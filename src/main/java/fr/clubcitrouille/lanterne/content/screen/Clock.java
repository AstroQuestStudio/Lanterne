package fr.clubcitrouille.lanterne.content.screen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * L'horloge partagée : quatre nombres au serveur, la position calculée chez chacun.
 *
 * <h2>Le piège que ce fichier existe pour éviter</h2>
 *
 * <p>La façon naturelle de synchroniser une vidéo entre joueurs est aussi la seule qui ne marche
 * jamais : un client annonce « je suis à 12,4 s », le serveur le répète aux autres, les autres se
 * recalent. Elle échoue pour trois raisons qui se cumulent.
 *
 * <p><b>Le débit.</b> Un recalage utile doit être fréquent ; à soixante messages par seconde et par
 * écran, dix écrans dans un monde coûtent six cents paquets par seconde pour transporter un nombre
 * qui était prévisible.
 *
 * <p><b>La latence.</b> Le nombre annoncé est déjà faux quand il arrive. Le corriger demande de
 * connaître le temps de vol, ce que l'émetteur ne sait pas et le récepteur non plus.
 *
 * <p><b>L'autorité.</b> Si c'est un client qui dit l'heure, un client modifié décide de l'heure de
 * tout le monde. Et si l'on choisit « le premier arrivé fait foi », alors un joueur qui se
 * déconnecte emporte la séance avec lui.
 *
 * <h2>Ce qu'on fait à la place : une ancre, et de l'arithmétique</h2>
 *
 * <p>Le serveur ne retient que le point de départ : <b>à quel tick la lecture valait quelle
 * position</b>, et si elle est en pause. Rien d'autre ne circule, et rien ne circule ensuite —
 * l'ancre ne change qu'aux gestes du joueur : lecture, pause, déplacement du curseur.
 *
 * <p>Chaque client en déduit sa position par une soustraction. Deux clients qui font la même
 * soustraction sur les mêmes nombres obtiennent le même résultat : c'est là toute la synchronisation,
 * et elle est exacte par construction plutôt que maintenue par correction.
 *
 * <h2>Pourquoi le tick de jeu, et pas l'heure de la machine</h2>
 *
 * <p>{@code getGameTime()} est un compteur que le serveur diffuse à <b>tous</b> les clients toutes
 * les vingt ticks — {@code MinecraftServer.tickServer} appelle
 * {@code forceGameTimeSynchronization} sur {@code tickCount % 20 == 0}, et le client le pose tel quel
 * dans {@code ClientLevel.setTimeFromServer}. Il existe donc déjà une horloge partagée dans le jeu,
 * recalée chaque seconde, et il serait absurde d'en bâtir une seconde à côté.
 *
 * <p>L'heure de la machine, elle, n'est partagée par personne : deux joueurs dont les horloges
 * système divergent de deux secondes — ce qui est banal — regarderaient deux films différents.
 *
 * <p>Le compteur de ticks porte en revanche un défaut qu'il faut nommer : <b>il ralentit quand le
 * serveur ralentit</b>. Sur un serveur à quinze ticks par seconde, la séance avance à trois quarts de
 * sa vitesse. C'est désagréable et c'est le bon compromis : tout le monde ralentit <em>ensemble</em>,
 * là où une horloge murale ferait diverger les clients entre eux au moment précis où le serveur
 * souffre. On préfère un film lent et commun à un film juste et désuni.
 *
 * <h2>La latence, et pourquoi on ne la compense qu'à moitié</h2>
 *
 * <p>Le paquet de synchronisation met un aller simple à arriver. Le client qui le reçoit est donc en
 * retard de cet aller simple, et comptera ensuite ses propres ticks à partir d'une valeur périmée.
 * Un joueur à deux cents millisecondes de latence regarde cent millisecondes dans le passé.
 *
 * <p>Le jeu connaît déjà cette latence : le serveur la mesure sur ses battements de cœur et la
 * diffuse dans la liste des joueurs, où {@code PlayerInfo.getLatency()} la rend. C'est un
 * aller-retour ; l'aller simple en vaut la moitié. Voir {@link #compensation(int)}.
 *
 * <p>On la compense, mais bornée. Une latence rapportée à trois secondes — ce qui arrive sur une
 * connexion qui se noie — ferait sauter la séance de trois secondes en avant pour un seul joueur,
 * c'est-à-dire que la correction ferait beaucoup plus de dégâts que le défaut.
 */
public record Clock(long anchorTick, boolean paused, long anchorMillis, long durationMillis) {
    /** Millisecondes dans un tick. Vingt ticks par seconde, par définition du jeu. */
    public static final long TICK_MILLIS = 50L;

    /**
     * Compensation de latence maximale, en millisecondes.
     *
     * <p>Voir l'en-tête : au-delà, la correction coûte plus que le défaut qu'elle corrige. Un quart
     * de seconde couvre une connexion transatlantique honnête et refuse de suivre une connexion qui
     * s'effondre.
     */
    private static final int COMPENSATION_CAP = 250;

    /**
     * Écart au-delà duquel on saute au lieu de rattraper, en millisecondes.
     *
     * <h2>Deux remèdes, et le mauvais se voit</h2>
     *
     * <p>Quand la position réellement affichée s'écarte de celle que l'horloge commande, il y a deux
     * façons de revenir : <b>sauter</b> — se replacer d'un coup — ou <b>rattraper</b> — jouer un peu
     * plus vite jusqu'à recoller.
     *
     * <p>Le saut est instantané et brutal : l'image se fige, le son claque, et sur un moteur qui doit
     * redemander une image-clé, il coûte une seconde de noir. Le rattrapage est invisible tant qu'il
     * reste modeste — une image sur cinquante, une hauteur de son inchangée à l'oreille.
     *
     * <p>Le seuil départage les deux situations, qui ne sont pas de degré mais de nature. Sous deux
     * secondes, l'écart vient d'une machine qui souffle : elle recollera. Au-delà, il vient d'un
     * joueur qui arrive en cours de séance, qui sort d'un chargement de terrain ou qui revient de
     * loin — et rattraper vingt minutes à un centième près prendrait trente-trois heures.
     */
    private static final long SEEK_THRESHOLD = 2_000L;

    /**
     * Écart en deçà duquel on ne fait rien, en millisecondes.
     *
     * <p>Corriger un écart de vingt millisecondes, c'est corriger le bruit de la mesure elle-même :
     * la latence rapportée par le jeu bouge d'un battement à l'autre, et l'horloge de tick avance par
     * paliers de cinquante millisecondes. Une correction permanente autour de zéro serait une
     * oscillation, c'est-à-dire exactement ce qu'on cherche à éviter.
     */
    private static final long DEADBAND = 80L;

    /** Vitesse maximale du rattrapage. Cinq pour cent : inaudible, invisible, et suffisant. */
    private static final float MAX_TRIM = 0.05f;

    /** Une séance neuve, à l'arrêt, sans source. */
    public static final Clock STOPPED = new Clock(0L, true, 0L, 0L);

    public static final Codec<Clock> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.LONG.fieldOf("ancre_tick").forGetter(Clock::anchorTick),
            Codec.BOOL.fieldOf("pause").forGetter(Clock::paused),
            Codec.LONG.fieldOf("ancre_ms").forGetter(Clock::anchorMillis),
            Codec.LONG.fieldOf("duree_ms").forGetter(Clock::durationMillis)
    ).apply(instance, Clock::new));

    public static final StreamCodec<RegistryFriendlyByteBuf, Clock> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_LONG, Clock::anchorTick,
                    ByteBufCodecs.BOOL, Clock::paused,
                    ByteBufCodecs.VAR_LONG, Clock::anchorMillis,
                    ByteBufCodecs.VAR_LONG, Clock::durationMillis,
                    Clock::new);

    /**
     * La position de lecture, en millisecondes.
     *
     * <p>C'est <b>la</b> fonction de tout ce paquet. Tout le reste — les blocs, les plis, l'interface
     * — n'existe que pour lui fournir ses quatre nombres.
     *
     * @param gameTime le tick courant, celui que le serveur a diffusé
     * @param partialTick la fraction de tick écoulée, entre 0 et 1. Sans elle, la position avancerait
     *     par paliers de cinquante millisecondes : sur une vidéo à soixante images par seconde, trois
     *     images d'affilée porteraient la même consigne, puis la quatrième sauterait de trois.
     */
    public long position(long gameTime, float partialTick) {
        if (this.paused) {
            return this.anchorMillis;
        }
        long elapsed = Math.round((gameTime - this.anchorTick + partialTick) * TICK_MILLIS);
        return wrap(this.anchorMillis + Math.max(0L, elapsed));
    }

    /**
     * La position repliée sur la durée, quand la séance boucle.
     *
     * <p>Une durée nulle veut dire « inconnue » — un direct, ou un moteur qui n'a pas encore lu les
     * entêtes. On ne replie alors rien : replier sur zéro donnerait une division par zéro, et
     * supposer une durée serait inventer.
     */
    private long wrap(long raw) {
        if (this.durationMillis <= 0L) {
            return raw;
        }
        return raw % this.durationMillis;
    }

    /** La séance a-t-elle dépassé sa fin ? Faux si la durée est inconnue, ou si elle boucle. */
    public boolean finished(long gameTime) {
        if (this.paused || this.durationMillis <= 0L) {
            return false;
        }
        return this.anchorMillis + (gameTime - this.anchorTick) * TICK_MILLIS >= this.durationMillis;
    }

    // --- Les gestes -----------------------------------------------------------
    //
    // Chacun rend une horloge NEUVE. Une ancre mutable serait partagée entre le bloc-entité, le pli
    // qui la transporte et l'écran de réglages qui l'affiche — trois lecteurs pour un écrivain, sur
    // deux fils. Un enregistrement immuable rend la question sans objet.

    /**
     * Reprend la lecture à l'instant présent.
     *
     * <p>L'ancre est reposée sur le tick courant : sans cela, une pause de dix minutes serait
     * comptée comme dix minutes de lecture au moment de la reprise.
     */
    public Clock play(long gameTime) {
        return this.paused ? new Clock(gameTime, false, this.anchorMillis, this.durationMillis) : this;
    }

    /**
     * Arrête la lecture là où elle en est.
     *
     * <p>La position courante devient la nouvelle ancre. C'est ce qui fait qu'une pause suivie d'une
     * reprise ne perd rien, et qu'elle ne dépend pas de la durée de la pause.
     */
    public Clock pause(long gameTime) {
        if (this.paused) {
            return this;
        }
        return new Clock(gameTime, true, position(gameTime, 0f), this.durationMillis);
    }

    /** Déplace le curseur, sans changer l'état de lecture. */
    public Clock seek(long gameTime, long millis) {
        long target = Math.max(0L, this.durationMillis > 0L
                ? Math.min(millis, this.durationMillis) : millis);
        return new Clock(gameTime, this.paused, target, this.durationMillis);
    }

    /** Repart de zéro, toujours en pause : changer de source ne doit pas lancer la lecture. */
    public Clock rewind(long gameTime) {
        return new Clock(gameTime, true, 0L, 0L);
    }

    /**
     * Inscrit la durée que le moteur vient de lire.
     *
     * <p>Elle arrive toujours en retard — un moteur ne connaît la durée qu'après avoir ouvert le
     * flux — et elle n'existe pas du tout pour un direct. Rien de ce qui précède ne l'attend : c'est
     * pourquoi la position se calcule sans elle, et pourquoi la boucle ne s'arme qu'une fois qu'elle
     * est connue.
     */
    public Clock withDuration(long millis) {
        return millis == this.durationMillis ? this : new Clock(this.anchorTick, this.paused,
                this.anchorMillis, Math.max(0L, millis));
    }

    // --- La dérive, et ce qu'on en fait --------------------------------------

    /** Ce que le moteur doit faire pour recoller à l'horloge. */
    public sealed interface Correction {
        /** Rien à faire : l'écart est dans le bruit. Voir {@link #DEADBAND}. */
        record Hold() implements Correction {}

        /**
         * Rattraper progressivement, en jouant à {@code rate} fois la vitesse.
         *
         * <p>Au-dessus de un pour rattraper un retard, en dessous pour laisser revenir une avance.
         * Un moteur qui ne sait pas changer de vitesse peut traiter ceci comme un {@link Hold} : il
         * dérivera un peu plus longtemps, il ne cassera rien.
         */
        record Trim(float rate) implements Correction {}

        /** Se replacer d'un coup. Coûteux, visible, et parfois la seule réponse honnête. */
        record Seek(long millis) implements Correction {}
    }

    /**
     * Ce qu'il faut faire quand le moteur est à {@code actual} et l'horloge commande {@code target}.
     *
     * <p>Une fonction pure, et c'est délibéré : la politique de rattrapage est ce qu'il y a de plus
     * facile à régler de travers et de plus difficile à observer dans un jeu qui tourne. Écrite ici,
     * elle se lit, se discute et s'éprouve sans qu'aucun moteur n'existe.
     *
     * @param target la position que l'horloge partagée commande
     * @param actual celle que le moteur affiche réellement
     */
    public static Correction correct(long target, long actual) {
        long drift = target - actual;
        long size = Math.abs(drift);
        if (size < DEADBAND) {
            return new Correction.Hold();
        }
        if (size >= SEEK_THRESHOLD) {
            return new Correction.Seek(target);
        }
        // La vitesse croît avec l'écart, et non par paliers : un écart de cent millisecondes se
        // rattrape doucement, un écart d'une seconde et demie presque au plafond. Le rattrapage se
        // termine donc en s'apaisant, au lieu de dépasser puis de repartir dans l'autre sens.
        float share = (float) size / SEEK_THRESHOLD;
        float trim = MAX_TRIM * share;
        return new Correction.Trim(drift > 0L ? 1f + trim : 1f - trim);
    }

    /**
     * De combien un client doit avancer sa position pour compenser son propre retard.
     *
     * @param latencyMillis l'aller-retour que le jeu rapporte, jamais l'aller simple
     */
    public static long compensation(int latencyMillis) {
        if (latencyMillis <= 0) {
            return 0L;
        }
        return Math.min(COMPENSATION_CAP, latencyMillis / 2);
    }

    /** « 12:34 » ou « 1:02:03 ». Pour l'interface, qui ne montrera jamais des millisecondes. */
    public static String clockface(long millis) {
        long total = Math.max(0L, millis) / 1000L;
        long hours = total / 3600L;
        long minutes = total % 3600L / 60L;
        long seconds = total % 60L;
        return hours > 0L
                ? String.format(java.util.Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
                : String.format(java.util.Locale.ROOT, "%d:%02d", minutes, seconds);
    }
}
