package fr.clubcitrouille.lanterne.client.screen;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Locale;

import net.minecraft.resources.Identifier;

import fr.clubcitrouille.lanterne.Lanterne;
import fr.clubcitrouille.lanterne.content.screen.Clock;
import fr.clubcitrouille.lanterne.content.screen.Embed;

/**
 * Le lecteur intégré : un navigateur que le joueur a installé, piloté par l'horloge partagée.
 *
 * <h2>La correction qui a rendu ce fichier nécessaire</h2>
 *
 * <p>La première reconnaissance de ce chantier écartait YouTube. Elle citait les conditions
 * d'utilisation, et elle avait tort — <b>par confusion entre deux gestes très différents</b>.
 *
 * <p><b>Extraire</b> le flux — retrouver l'adresse du fichier pour le décoder soi-même, à la façon de
 * {@code yt-dlp} — est bien interdit : c'est du téléchargement automatisé, et cela suppose de
 * contourner une protection. C'était vrai et cela reste vrai.
 *
 * <p><b>Intégrer</b> ne l'est pas. Le texte réserve l'accès aux « pages de lecture vidéo, <em>au
 * lecteur intégrable</em>, ou à d'autres moyens explicitement autorisés ». Le lecteur intégrable est
 * <b>nommé</b>, c'est ce pour quoi il existe, et l'affichage passe par le lecteur officiel : la
 * publicité est servie, la vue est comptée. C'est ce que font depuis toujours les mods de cinéma.
 *
 * <p>Écarter la seconde au nom de la première a coûté un écran noir à un joueur. Voir
 * {@link Embed}, qui transforme l'adresse collée en la forme que l'hébergeur publie pour cet usage.
 *
 * <h2>Rien n'est embarqué, rien n'est téléchargé par nous</h2>
 *
 * <p>Ce moteur ne dépend de rien à la compilation et ne tire aucun octet. Il regarde si <b>Rinku</b>
 * est installé — le fork vivant de MCEF, LGPL-2.1-or-later, qui couvre la 26.2 sur NeoForge — et s'en
 * saisit par réflexion. Rinku apporte son propre Chromium, le télécharge lui-même, et montre son
 * propre écran de progression : nous n'avons ni à le déclencher ni à le surveiller.
 *
 * <p>Le joueur qui ne veut pas de navigateur ne l'installe pas et garde les liens directs par FFmpeg,
 * sans rien télécharger de plus. Celui qui veut YouTube installe un mod. <b>Personne ne paie cent
 * quatre-vingts mébioctets pour une fonction qu'il n'emploie pas</b> — ce qui est tout l'intérêt de
 * ne pas l'embarquer.
 *
 * <h2>La texture tombe juste, et c'était la question qui décidait de tout</h2>
 *
 * <p>Un navigateur hors écran qui ne rendrait que dans une fenêtre aurait été inutilisable : il
 * aurait fallu relire les pixels depuis la carte graphique à chaque image. Rinku rend mieux que cela
 * — il enregistre sa texture dans le gestionnaire de vanilla et expose son {@code Identifier}. On le
 * passe donc <b>tel quel</b> au type de rendu, exactement comme celui d'une pellicule de
 * {@link Film}. Rien du rendu des écrans n'a eu à changer.
 *
 * <h2>Ce que le son ne fera pas, et il faut le dire</h2>
 *
 * <p>Le son d'un navigateur sort <b>directement vers le périphérique audio</b>. Il n'est donc
 * <b>pas spatialisé</b> : il ne baisse pas quand on s'éloigne de l'écran, et il ne vient d'aucune
 * direction. C'est une limite réelle du chemin intégré, et elle est annoncée plutôt que découverte.
 *
 * <p>Ce qui reste possible : le rendre muet. Le volume réglé dans l'écran du bloc est donc appliqué
 * <em>dans la page</em> — voir {@link #SCRIPT} — ce qui donne une décroissance avec la distance même
 * si elle n'a pas de direction. Un chemin existe pour récupérer le flux brut et le spatialiser
 * vraiment ; il passe par un gestionnaire audio global à tout le navigateur, et il n'est pas écrit.
 */
public final class Chrome implements Engine {
    public static final Chrome INSTANCE = new Chrome();

    /** La classe d'entrée de Rinku. Son absence est la seule chose qui distingue « installé ». */
    private static final String ENTRY = "de.keksuccino.rinku.Rinku";

    /**
     * Le script qui met la page à l'heure de tout le monde.
     *
     * <h2>Pourquoi l'élément {@code <video>} et pas l'API du lecteur</h2>
     *
     * <p>YouTube publie une API d'IFrame avec {@code seekTo}, {@code playVideo}, {@code pauseVideo}.
     * Elle suppose d'être dans le cadre <em>parent</em> et de dialoguer par messages ; ici le lecteur
     * <b>est</b> la page du haut, et il n'y a pas de parent.
     *
     * <p>L'élément {@code <video>} du HTML, lui, est là dans tous les cas — chez YouTube, chez Vimeo,
     * chez Dailymotion, et sur n'importe quelle page qui lit une vidéo. Un seul script les pilote
     * tous, et il n'y a pas d'API à suivre quand un hébergeur change la sienne.
     *
     * <h2>Le seuil est bien plus large que celui de l'horloge, et c'est voulu</h2>
     *
     * <p>{@code Clock.correct} rattrape à partir de quatre-vingts millisecondes. Ce serait ruineux
     * ici : un {@code currentTime} imposé à un lecteur web coûte une remise en tampon, parfois une
     * requête réseau, et une image figée pendant ce temps. Corriger dix fois par seconde donnerait
     * une vidéo qui bégaie en permanence pour rester juste au centième.
     *
     * <p>Une demi-seconde est en dessous de ce qu'on remarque entre deux écrans dans une même pièce,
     * et au-dessus de ce qu'un lecteur web fait de dérive en une minute. La correction se fait donc
     * <b>dans la page</b>, qui est la seule à pouvoir comparer sans aller-retour : on lui envoie la
     * position voulue, elle décide si elle bouge.
     */
    private static final String SCRIPT =
            "(function(){var v=document.querySelector('video');if(!v)return;"
            + "if(Math.abs(v.currentTime-%1$s)>0.5){try{v.currentTime=%1$s;}catch(e){}}"
            + "if(%2$s){if(v.paused){var p=v.play();if(p&&p.catch)p.catch(function(){});}}"
            + "else{if(!v.paused)v.pause();}"
            + "v.volume=%3$s;v.muted=(%3$s<=0.001);})()";

    /**
     * Le script pour un YouTube encadré par {@link Hote} : plus de {@code <video>} à portée, parce
     * que la page du haut n'est plus le lecteur mais un cadre qui le contient — l'iframe est d'une
     * autre origine, {@code document.querySelector} n'y voit rien. Seul {@code postMessage} traverse
     * cette frontière, avec le protocole que YouTube publie pour son lecteur intégrable
     * ({@code enablejsapi=1}, déjà posé par {@code Embed}).
     *
     * <h2>Pourquoi la position n'est corrigée qu'à l'occasion, et pas à chaque battement</h2>
     *
     * <p>{@code postMessage} n'attend pas de réponse — {@code Chrome.wire} le dit déjà pour le
     * chemin direct. Ici c'est pire : il n'y a même pas de {@code v.currentTime} local à lire pour
     * savoir si la vidéo a dérivé avant de corriger. Imposer {@code seekTo} à chaque battement, à
     * l'aveugle, remettrait en tampon le flux quatre fois par seconde — précisément le bégaiement
     * que le seuil du chemin direct existe pour éviter. On imite donc ce seuil sans pouvoir le
     * mesurer : {@code %1$s} vaut soit une position à imposer, soit le mot {@code null} pour dire
     * « laisse-la courir toute seule cette fois », et {@link Pane} n'envoie une position que toutes
     * les {@link Pane#HARD_SEEK} millisecondes.
     */
    private static final String SCRIPT_YOUTUBE =
            "(function(){var f=document.getElementById('p');if(!f||!f.contentWindow)return;"
            + "function send(func,args){try{f.contentWindow.postMessage(JSON.stringify("
            + "{event:'command',func:func,args:args||[]}),'*');}catch(e){}}"
            + "if(%1$s!==null)send('seekTo',[%1$s,true]);"
            + "send(%2$s?'playVideo':'pauseVideo',[]);"
            + "send('setVolume',[%3$s]);})()";

    /** Les poignées vers Rinku, résolues une fois. Nulles tant que le mod n'est pas là. */
    private static final class Bridge {
        MethodHandle initialised;
        MethodHandle create;
        MethodHandle textureId;
        MethodHandle textureReady;
        MethodHandle resize;
        MethodHandle script;
        MethodHandle close;
        MethodHandle renderer;
        MethodHandle widthOf;
        MethodHandle heightOf;
        boolean ok;
    }

    private static Bridge bridge;

    private Chrome() {}

    @Override
    public String label() {
        return "Lecteur intégré (Rinku)";
    }

    @Override
    public Verdict verdict() {
        if (!Consent.remoteAllowed()) {
            return Verdict.SANS_CONSENTEMENT;
        }
        Bridge wired = wire();
        if (wired == null || !wired.ok) {
            return Verdict.SANS_MOD;
        }
        try {
            // Rinku télécharge son Chromium tout seul, avec son propre écran de progression. Tant
            // qu'il n'a pas fini, il n'est pas initialisé — et créer un navigateur lèverait. Ce
            // n'est pas une panne : c'est une attente, et l'ardoise le dira.
            return (boolean) wired.initialised.invoke() ? Verdict.PRET : Verdict.SANS_BIBLIOTHEQUE;
        } catch (Throwable problem) {
            return Verdict.SANS_MOD;
        }
    }

    /**
     * Ouvre une page.
     *
     * <p>La hauteur demandée vient de {@code Grade}, comme pour FFmpeg : un navigateur rendu en
     * 1280 × 720 coûte quatre fois moins qu'en 2560 × 1440, et c'est le même arbitrage. La largeur
     * est déduite d'un seizième-neuvième, parce qu'une page n'a pas de « forme native » à respecter.
     */
    @Override
    public Reel open(String source, int wantedHeight) {
        if (verdict() != Verdict.PRET) {
            return null;
        }
        Embed.Form form = Embed.of(source);
        try {
            int height = Math.clamp(wantedHeight, 240, 1440);
            int width = Math.max(2, Math.round(height * 16f / 9f) & ~1);
            boolean youtube = form.url().startsWith(Embed.YOUTUBE_EMBED_PREFIX);
            String address = youtube ? Hote.frame(form.url()) : form.url();
            if (address == null) {
                // L'hote local n'a pas pu demarrer : pas de repli silencieux vers l'adresse nue,
                // ce serait rouvrir l'Erreur 153 qu'il existe pour fermer.
                Lanterne.LOG.warn("[PROJECTION] hote local indisponible, YouTube abandonne.");
                return null;
            }
            Object browser = bridge.create.invoke(address, false, width, height);
            if (browser == null) {
                return null;
            }
            return new Pane(browser, width, height, youtube);
        } catch (Throwable refused) {
            Lanterne.LOG.warn("[PROJECTION] lecteur intégré indisponible : {}",
                    String.valueOf(refused));
            return null;
        }
    }

    /**
     * Résout les poignées, une fois.
     *
     * <p>Par réflexion et non par dépendance : Rinku est facultatif, et le nommer à la compilation
     * ferait de ce mod un mod qui refuse de démarrer sans lui. Les poignées de méthode coûtent, une
     * fois résolues, à peu près ce que coûte un appel ordinaire — ce qui compte quand on en fait
     * quelques-unes par image.
     */
    private static synchronized Bridge wire() {
        if (bridge != null) {
            return bridge;
        }
        Bridge wired = new Bridge();
        bridge = wired;
        try {
            ClassLoader loader = Chrome.class.getClassLoader();
            Class<?> entry = Class.forName(ENTRY, false, loader);
            Class<?> browser = Class.forName("de.keksuccino.rinku.RinkuBrowser", false, loader);
            Class<?> renderer = Class.forName("de.keksuccino.rinku.RinkuRenderer", false, loader);
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();

            wired.initialised = lookup.findStatic(entry, "isInitialized",
                    MethodType.methodType(boolean.class));
            wired.create = lookup.findStatic(entry, "createBrowser",
                    MethodType.methodType(browser, String.class, boolean.class, int.class, int.class));
            wired.textureId = lookup.findVirtual(browser, "getTextureIdentifier",
                    MethodType.methodType(Identifier.class));
            wired.textureReady = lookup.findVirtual(browser, "isTextureReady",
                    MethodType.methodType(boolean.class));
            wired.resize = lookup.findVirtual(browser, "resize",
                    MethodType.methodType(void.class, int.class, int.class));
            wired.close = lookup.findVirtual(browser, "close", MethodType.methodType(void.class));
            wired.renderer = lookup.findVirtual(browser, "getRenderer",
                    MethodType.methodType(renderer));
            wired.widthOf = lookup.findVirtual(renderer, "getTextureWidth",
                    MethodType.methodType(int.class));
            wired.heightOf = lookup.findVirtual(renderer, "getTextureHeight",
                    MethodType.methodType(int.class));
            // « executeJavaScript » est hérité de JCEF, dont Rinku embarque un fork dans son propre
            // jar. On le cherche donc sur la classe du navigateur, qui l'expose par héritage, plutôt
            // que sur une interface d'org.cef qu'on n'a aucune raison de nommer.
            wired.script = lookup.findVirtual(browser, "executeJavaScript",
                    MethodType.methodType(void.class, String.class, String.class, int.class));
            wired.ok = true;
            Lanterne.LOG.info("[PROJECTION] lecteur intégré détecté : Rinku.");
            hookLoadErrors(entry, loader);
        } catch (Throwable absent) {
            // Absent, ou d'une version dont les signatures ont bougé. Les deux se traitent pareil :
            // on ne s'en sert pas, et l'ardoise dit quoi installer. Au niveau « debug » parce qu'un
            // mod facultatif qui manque n'est pas une anomalie.
            Lanterne.LOG.debug("[PROJECTION] Rinku absent ou incompatible : {}",
                    String.valueOf(absent));
        }
        return wired;
    }

    /**
     * Journalise les échecs de chargement de Rinku — jamais une action, seulement un journal.
     *
     * <h2>Pourquoi ce crochet existe</h2>
     *
     * <p>{@code executeJavaScript} et {@code createBrowser} sont des envois sans réponse — voir la
     * Javadoc de {@link #SCRIPT}. Quand une page refuse de charger (un {@code Referer} encore rejeté,
     * un port local injoignable), rien ne le dit ailleurs qu'ici : {@code CefLoadHandler.onLoadError}
     * est le seul canal qui existe pour le savoir plutôt que le deviner. Ce module n'était pas prêt à
     * l'écrire tant que cette classe n'avait rien à corréler avec — {@link Hote} lui donne enfin
     * quelque chose à confirmer ou infirmer au prochain échec réel.
     *
     * <p>Par proxy dynamique et non par implémentation compilée : {@code org.cef.*} n'est nommé nulle
     * part ailleurs dans ce fichier, précisément parce que Rinku est facultatif. Un proxy sur une
     * interface résolue par réflexion tient la même promesse que le reste de {@link #wire} — aucune
     * dépendance de compilation sur un jar qui peut ne pas être là.
     */
    private static void hookLoadErrors(Class<?> entry, ClassLoader loader) {
        try {
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            Object client = lookup.findStatic(entry, "getClient",
                    MethodType.methodType(Class.forName("de.keksuccino.rinku.RinkuClient", false, loader)))
                    .invoke();
            Class<?> handlerType = Class.forName("org.cef.handler.CefLoadHandler", false, loader);
            Object proxy = java.lang.reflect.Proxy.newProxyInstance(loader, new Class<?>[] {handlerType},
                    (target, method, args) -> {
                        if ("onLoadError".equals(method.getName()) && args != null && args.length >= 5) {
                            Lanterne.LOG.warn("[PROJECTION] echec de chargement ({}) : {} — {}",
                                    args[2], args[4], args[3]);
                        }
                        return null;
                    });
            client.getClass().getMethod("addLoadHandler",
                    Class.forName("org.cef.handler.CefLoadHandler", false, loader)).invoke(client, proxy);
        } catch (Throwable indisponible) {
            // Une version de Rinku dont cette API a bougé. Le lecteur reste utilisable ; seul ce
            // diagnostic manque, et il n'a jamais conditionné wired.ok.
            Lanterne.LOG.debug("[PROJECTION] diagnostic de chargement indisponible : {}",
                    String.valueOf(indisponible));
        }
    }

    /**
     * Une page ouverte, tenue à l'heure de l'horloge partagée.
     *
     * <p>Elle ne décode rien elle-même et n'écrit dans aucune pellicule : le navigateur tient sa
     * propre texture, et Rinku la pousse vers la carte graphique tout seul, à chaque image du jeu.
     * {@link #present} n'a donc rien à téléverser — seulement à dire l'heure.
     */
    private static final class Pane implements Reel {
        /**
         * Millisecondes entre deux mises à l'heure.
         *
         * <p>Quatre fois par seconde. Chaque appel traverse la frontière vers le processus du
         * navigateur et fait analyser un script : à soixante hertz ce serait une dépense permanente
         * pour corriger une dérive qui se compte en millisecondes par minute.
         */
        private static final long BEAT = 250L;

        /** Voir le commentaire de {@link Chrome#SCRIPT_YOUTUBE} : trois secondes entre deux
         *  positions imposées à l'aveugle, assez rare pour ne pas faire bégayer le flux, assez
         *  fréquent pour rattraper une horloge qui vient de sauter (rembobinage, rejoint en cours). */
        private static final long HARD_SEEK = 3000L;

        private final Object browser;
        private final int width;
        private final int height;
        private final boolean framed;

        private long spokeAt;
        private long hardSeekAt;
        private long shownMillis;
        private volatile boolean shut;
        private String trouble = "";

        Pane(Object browser, int width, int height, boolean framed) {
            this.browser = browser;
            this.width = width;
            this.height = height;
            this.framed = framed;
        }

        @Override
        public boolean present(long millis, Film film) {
            if (this.shut) {
                return false;
            }
            this.shownMillis = millis;
            long now = System.currentTimeMillis();
            if (now - this.spokeAt < BEAT) {
                return false;
            }
            this.spokeAt = now;
            speak(millis);
            return true;
        }

        /** Envoie la position, l'état de lecture et le volume à la page. */
        private void speak(long millis) {
            try {
                String code = this.framed ? speakFramed(millis) : String.format(Locale.ROOT, SCRIPT,
                        String.format(Locale.ROOT, "%.3f", millis / 1000d),
                        this.playing ? "true" : "false",
                        String.format(Locale.ROOT, "%.3f", this.gain));
                bridge.script.invoke(this.browser, code, "", 0);
            } catch (Throwable ignored) {
                // Une page qui n'a pas fini de charger, ou qui vient d'être fermée. Ni l'un ni
                // l'autre n'est une panne : le battement suivant réessaiera dans un quart de
                // seconde. Journaliser ferait une ligne toutes les 250 ms au démarrage.
            }
        }

        /** Le code pour {@link Chrome#SCRIPT_YOUTUBE} : voir son commentaire pour {@link #HARD_SEEK}. */
        private String speakFramed(long millis) {
            long now = System.currentTimeMillis();
            String position = "null";
            if (now - this.hardSeekAt >= HARD_SEEK) {
                this.hardSeekAt = now;
                position = String.format(Locale.ROOT, "%.3f", millis / 1000d);
            }
            return String.format(Locale.ROOT, SCRIPT_YOUTUBE, position,
                    this.playing ? "true" : "false",
                    String.format(Locale.ROOT, "%.0f", Math.clamp(this.gain, 0f, 1f) * 100));
        }

        private volatile boolean playing;
        private volatile float gain = 1f;

        /** Appelé par {@link Gaze} : l'horloge dit si l'on doit jouer ou attendre. */
        void playing(boolean value) {
            this.playing = value;
        }

        @Override
        public Identifier texture() {
            try {
                return this.shut || !(boolean) bridge.textureReady.invoke(this.browser)
                        ? null : (Identifier) bridge.textureId.invoke(this.browser);
            } catch (Throwable problem) {
                return null;
            }
        }

        @Override
        public boolean ready() {
            return !this.shut && texture() != null;
        }

        @Override
        public boolean broken() {
            return this.shut;
        }

        @Override
        public String trouble() {
            return this.trouble;
        }

        @Override
        public int width() {
            try {
                int seen = (int) bridge.widthOf.invoke(bridge.renderer.invoke(this.browser));
                return seen > 0 ? seen : this.width;
            } catch (Throwable problem) {
                return this.width;
            }
        }

        @Override
        public int height() {
            try {
                int seen = (int) bridge.heightOf.invoke(bridge.renderer.invoke(this.browser));
                return seen > 0 ? seen : this.height;
            } catch (Throwable problem) {
                return this.height;
            }
        }

        /**
         * Durée inconnue, toujours.
         *
         * <p>On pourrait la lire dans la page — {@code v.duration} — mais {@code executeJavaScript}
         * ne rend rien : c'est un envoi sans réponse. La récupérer demanderait un pont de messages
         * dans l'autre sens, pour une valeur qui ne sert qu'à dessiner une barre de progression.
         *
         * <p>Zéro veut dire « inconnue » pour {@link Clock}, qui cesse alors de replier la boucle et
         * laisse la position courir. C'est le bon comportement pour un lecteur qui gère lui-même sa
         * fin de vidéo.
         */
        @Override
        public long duration() {
            return 0L;
        }

        /**
         * Aucune dérive rapportée, et ce n'est pas un aveu.
         *
         * <p>La correction ne se fait pas ici : elle se fait <b>dans la page</b>, par le script, qui
         * est le seul à pouvoir comparer la position voulue et la position réelle sans aller-retour.
         * Rendre zéro dit à {@code Clock.correct} de ne rien tenter de plus — ce qui est exact, parce
         * que la correction a déjà eu lieu.
         */
        @Override
        public long drift() {
            return 0L;
        }

        @Override
        public void rate(float multiplier) {
            // Un lecteur web accélère mal et audiblement. Le script préfère un saut au-delà d'une
            // demi-seconde, ce qui rend le rattrapage progressif sans objet ici.
        }

        @Override
        public void volume(float value) {
            this.gain = Math.clamp(value, 0f, 1f);
        }

        @Override
        public void close() {
            if (this.shut) {
                return;
            }
            this.shut = true;
            try {
                // Sur le fil de rendu : c'est la condition pour que Rinku libère ses ressources
                // graphiques tout de suite plutôt qu'à un moment qu'il choisira. Gaze ferme depuis
                // le tick client, qui est ce fil.
                bridge.close.invoke(this.browser);
            } catch (Throwable ignored) {
                // Un navigateur déjà parti. Rien à sauver, et rien à dire.
            }
        }
    }

    /** Le volet de cette bobine, si c'en est un. Sert à {@link Gaze} pour transmettre la lecture. */
    static void playing(Reel reel, boolean value) {
        if (reel instanceof Pane pane) {
            pane.playing(value);
        }
    }
}
