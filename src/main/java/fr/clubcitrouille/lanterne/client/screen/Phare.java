package fr.clubcitrouille.lanterne.client.screen;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundSetBeaconPacket;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.BeaconMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.level.block.entity.BeaconBlockEntity;
import net.minecraft.world.phys.BlockHitResult;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

import fr.clubcitrouille.lanterne.Lanterne;
import fr.clubcitrouille.lanterne.core.Aureole;
import fr.clubcitrouille.lanterne.core.Config;

/**
 * Le phare : l'écran de la balise, refondu.
 *
 * <h2>Ce que vanilla ne montrait jamais</h2>
 *
 * <p>L'écran vanilla affiche le niveau, le paiement et une grille d'effets — jamais la portée, ni le
 * matériau, ni la verticalité. Une fois ces trois-là rendus configurables (voir {@link Aureole}), les
 * laisser invisibles aurait rendu le réglage aveugle : un joueur qui bâtit une pyramide de diamant
 * doit voir, immédiatement, que le multiplicateur s'applique — pas le déduire d'un fichier de
 * configuration qu'il n'a pas forcément ouvert.
 *
 * <h2>Le bug qui a rendu tout l'écran muet, et sa preuve</h2>
 *
 * <p>Cette classe déclarait {@code extractBackground(GuiGraphicsExtractor, int, int, float)}. Ce nom
 * ne correspond à AUCUNE méthode de {@code AbstractContainerScreen} dans ce moteur — vérifié au
 * {@code javap} sur le jar client 26.3 réellement téléchargé par NeoForm
 * ({@code neoformruntime/artifacts/minecraft_26.3_client.jar}), qui liste {@code extractRenderState}
 * et {@code extractContents}, jamais {@code extractBackground}. Sans {@code @Override} qui aurait
 * fait échouer la compilation, cette méthode compilait comme un simple ajout mort : jamais appelée
 * par le moteur, donc le fond, le titre, les infos de portée ET toute la grille d'effets ne se
 * dessinaient jamais. Le paiement et les emplacements d'inventaire, eux, continuaient de fonctionner
 * (rendus par l'implémentation par défaut, jamais remplacée) — d'où un écran à moitié vide plutôt
 * qu'un écran d'erreur, et un bogue invisible à la compilation comme à la vérification statique des
 * mixins.
 *
 * <p>Le vrai point d'accroche est {@link #extractContents} : {@code AbstractContainerScreen} l'appelle
 * depuis {@code extractRenderState}, et son implémentation par défaut commence par peindre les
 * widgets ({@code Screen.extractRenderState}, appelé en {@code invokespecial}), PUIS pousse une
 * matrice de translation de {@code (leftPos, topPos)} avant les étiquettes et les emplacements. Notre
 * fond doit donc se peindre AVANT l'appel à {@code super.extractContents}, en coordonnées absolues —
 * exactement comme {@code renderBg} le faisait dans les moteurs vanilla plus anciens — sous peine de
 * peindre le panneau PAR-DESSUS le bouton et les emplacements au lieu de dessous.
 *
 * <h2>Des vrais widgets plutôt qu'une détection de clic à la main</h2>
 *
 * <p>La version précédente recalculait elle-même les rectangles de chaque icône et les comparait à la
 * souris dans {@code mouseClicked}, dupliquant la mise en page du dessin. En plus d'avoir été le
 * complice silencieux du bogue ci-dessus (rien ne prouvait plus que ces rectangles correspondaient à
 * quoi que ce soit de visible), c'est le genre de code que la moindre divergence future entre dessin et
 * détection casse sans avertissement. {@link EffectIcon} est un {@code AbstractWidget} par icône,
 * ajouté une fois en {@link #init()} : survol, clic et infobulle passent par le même mécanisme déjà
 * éprouvé par le bouton « Confirmer » vanilla, plutôt que par une seconde implémentation maison.
 *
 * <h2>Une seule case connue de la balise elle-même, et ce n'est pas la position</h2>
 *
 * <p>{@code BeaconMenu} ne porte sa position nulle part — vanilla n'en a jamais eu besoin, faute
 * d'y afficher quoi que ce soit qui en dépende. Ce module la retrouve dans {@code hitResult}, gelé
 * dès qu'un écran est ouvert puisque le jeu cesse de viser tant qu'il l'est : c'est la cible du clic
 * qui a ouvert CET écran, presque toujours encore la balise elle-même. Une reconnexion, un clic
 * ailleurs juste avant l'ouverture (le round-trip réseau) peuvent la rendre fausse ou absente — dans
 * ce cas {@link #drawInfo} dégrade proprement : il annonce la portée AVANT matériau plutôt que de
 * mentir sur un multiplicateur qu'il ne peut pas connaître.
 *
 * <h2>Un menu vanilla, un écran neuf</h2>
 *
 * <p>Pas de nouveau {@code MenuType} : {@code BeaconMenu} porte déjà tout ce qu'il faut (niveau,
 * effet principal, effet secondaire, case de paiement) et {@code encodeEffect}/{@code decodeEffect}
 * passent n'importe quel effet du registre, pas seulement les six de vanilla — vérifié au
 * {@code javap}. Seul l'écran change ; {@link #onRegisterScreens} écrase l'enregistrement vanilla du
 * même {@code MenuType.BEACON}, exactement le mécanisme que {@code RegisterMenuScreensEvent} existe
 * pour offrir depuis que {@code MenuScreens.register} est devenu privé dans ce moteur.
 */
@EventBusSubscriber(modid = Lanterne.ID, value = Dist.CLIENT)
public class Phare extends AbstractContainerScreen<BeaconMenu> {
    private static final int PANEL = 0xE0141418;
    private static final int EDGE = 0xFF2A2A32;
    private static final int AMBER = 0xFFFFC857;
    private static final int TEXT = 0xFFE8E8E8;
    private static final int DIM = 0xFF8A8A92;
    private static final int FAINT = 0xFF5A5A62;

    private static final int IMAGE_WIDTH = 230;
    private static final int IMAGE_HEIGHT = 219;
    private static final int ICON = 18;
    private static final int GAP = 2;
    private static final int TIER_GAP = 4;

    /** Les six effets vanilla, par palier — {@code BeaconBlockEntity.BEACON_EFFECTS}, privé, donc
     *  recopié ici plutôt que lu par réflexion pour si peu. */
    private static final List<Holder<MobEffect>> TIER1 = List.of(MobEffects.SPEED, MobEffects.HASTE);
    private static final List<Holder<MobEffect>> TIER2 =
            List.of(MobEffects.RESISTANCE, MobEffects.JUMP_BOOST);
    private static final List<Holder<MobEffect>> TIER3 = List.of(MobEffects.STRENGTH);
    private static final List<Holder<MobEffect>> TIER4 = List.of(MobEffects.REGENERATION);

    private BlockPos pos;

    /**
     * Le choix EN COURS, pas encore envoyé au serveur.
     *
     * <h2>Pourquoi ne pas envoyer au premier clic</h2>
     *
     * <p>Vanilla n'envoie qu'un seul paquet — {@code ServerboundSetBeaconPacket}, principal ET
     * secondaire ensemble — quand le joueur clique « confirmer », et {@code BeaconMenu.updateEffects}
     * ne retire qu'UN SEUL objet de paiement par appel. Envoyer un paquet à chaque icône cliquée,
     * comme le ferait la version la plus directe de cet écran, consommerait donc DEUX objets de
     * paiement pour choisir un principal PUIS un secondaire au niveau 4 — le double du prix vanilla
     * pour le même résultat. Les champs ci-dessous retiennent le choix localement ; un seul paquet
     * part au clic sur {@link #confirm}.
     */
    private Holder<MobEffect> pendingPrimary;
    private Holder<MobEffect> pendingSecondary;
    private Button confirm;
    private final List<EffectIcon> icons = new ArrayList<>();

    public Phare(BeaconMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, IMAGE_WIDTH, IMAGE_HEIGHT);
        this.inventoryLabelY = IMAGE_HEIGHT - 94;
    }

    /**
     * <h2>{@code register} refuse tout ce que vanilla a déjà pris</h2>
     *
     * <p>Vérifié en jeu, pas seulement lu : {@code MenuScreens.init()} enregistre
     * {@code MenuType.BEACON -> BeaconScreen::new} pour son propre compte, PUIS seulement fait feu de
     * {@code RegisterMenuScreensEvent} — et {@code register()} lève {@code IllegalStateException} dès
     * que la clé existe déjà dans sa carte (`javap` sur {@code RegisterMenuScreensEvent.class} :
     * {@code containsKey} suivi d'un {@code athrow} sans aucune voie de remplacement). Écran d'erreur
     * au lancement du client — l'hypothèse d'origine (cet évènement sert à *remplacer* un écran
     * vanilla) était fausse : il ne sert qu'à en ENREGISTRER un nouveau qui n'existe pas encore.
     *
     * <p>La carte que reçoit l'évènement est la MÊME que celle de {@code MenuScreens} — passée par
     * référence à son constructeur, pas une copie. Écrire dedans directement, en contournant le garde-
     * fou de {@code register()}, obtient donc exactement l'effet voulu (remplacer l'écran de
     * {@code MenuType.BEACON} par {@link Phare}) sans dupliquer tout {@code MenuScreens.init()} dans
     * un mixin pour un seul type de menu.
     */
    @SuppressWarnings("unchecked")
    @SubscribeEvent
    public static void onRegisterScreens(RegisterMenuScreensEvent event) {
        try {
            var champ = RegisterMenuScreensEvent.class.getDeclaredField("registeredScreens");
            champ.setAccessible(true);
            var carte = (java.util.Map<net.minecraft.world.inventory.MenuType<?>,
                    net.minecraft.client.gui.screens.MenuScreens.ScreenConstructor<?, ?>>) champ.get(event);
            carte.put(MenuType.BEACON, (net.minecraft.client.gui.screens.MenuScreens.ScreenConstructor<BeaconMenu, Phare>) Phare::new);
        } catch (ReflectiveOperationException erreur) {
            Lanterne.LOG.error("[PHARE] remplacement de l'ecran du beacon impossible, "
                    + "l'ecran vanilla reste actif : {}", erreur.toString());
        }
    }

    @Override
    protected void init() {
        super.init();
        this.pos = beaconPos();
        this.pendingPrimary = this.menu.getPrimaryEffect();
        this.pendingSecondary = this.menu.getSecondaryEffect();
        this.icons.clear();
        addIconRow(primaryOptions(), primaryRowY(), true);
        addIconRow(secondaryOptions(), secondaryRowY(), false);
        // A droite de la rangee secondaire, jamais en dessous : les emplacements d'inventaire du
        // joueur sont fixes par BeaconMenu (addStandardInventorySlots) et un bouton pose entre les
        // deux rangees et cette grille aurait chevauche l'un ou l'autre selon la resolution.
        this.confirm = this.addRenderableWidget(Button.builder(Component.literal("Confirmer"),
                button -> confirm())
                .bounds(this.leftPos + IMAGE_WIDTH - 90, secondaryRowY() - 1, 80, 20)
                .build());
        refreshConfirmButton();
    }

    private void addIconRow(List<Holder<MobEffect>> options, int rowY, boolean primaryRow) {
        int x = this.leftPos + 10;
        int lastTier = 0;
        for (Holder<MobEffect> effect : options) {
            int tier = requiredTier(effect);
            if (lastTier != 0 && tier != lastTier) {
                x += TIER_GAP;
            }
            lastTier = tier;
            EffectIcon icon = new EffectIcon(effect, tier, primaryRow, x, rowY);
            this.icons.add(icon);
            this.addRenderableWidget(icon);
            x += ICON + GAP;
        }
    }

    /**
     * Resynchronise le choix en attente sur ce que le serveur a réellement retenu.
     *
     * <p>Appelée à chaque tick de conteneur — comme {@code BeaconScreen.containerTick()} vanilla,
     * vérifié au {@code javap} — parce qu'une confirmation ne change rien au niveau de la pyramide
     * mais consomme le paiement : {@link #confirm} doit redevenir inactif dès que le serveur a traité
     * l'envoi, sans attendre une fermeture/réouverture de l'écran. C'est aussi ici que chaque icône
     * réapprend si son palier est débloqué : la pyramide peut grandir ou s'effondrer pendant que
     * l'écran reste ouvert.
     */
    @Override
    public void containerTick() {
        super.containerTick();
        refreshConfirmButton();
        int level = this.menu.getLevels();
        for (EffectIcon icon : this.icons) {
            icon.active = level >= icon.tier;
        }
    }

    private void refreshConfirmButton() {
        if (this.confirm != null) {
            this.confirm.active = this.menu.hasPayment() && this.pendingPrimary != null;
        }
    }

    private BlockPos beaconPos() {
        if (this.minecraft == null || this.minecraft.level == null) {
            return null;
        }
        if (!(this.minecraft.hitResult instanceof BlockHitResult hit)) {
            return null;
        }
        return this.minecraft.level.getBlockEntity(hit.getBlockPos()) instanceof BeaconBlockEntity
                ? hit.getBlockPos() : null;
    }

    // --- Le dessin ---------------------------------------------------------

    /**
     * Le vrai point d'accroche du fond — voir la Javadoc de classe pour ce qui remplaçait ceci avant
     * et pourquoi ça ne dessinait jamais rien. Dessine en coordonnées ABSOLUES, avant d'appeler
     * {@code super.extractContents} : ce dernier peint les widgets (nos icônes, le bouton Confirmer),
     * les étiquettes et les emplacements PAR-DESSUS, jamais dessous.
     */
    @Override
    public void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partial) {
        int left = this.leftPos;
        int top = this.topPos;
        int level = this.menu.getLevels();

        graphics.fill(left - 1, top - 1, left + IMAGE_WIDTH + 1, top + IMAGE_HEIGHT + 1, EDGE);
        graphics.fill(left, top, left + IMAGE_WIDTH, top + IMAGE_HEIGHT, PANEL);
        graphics.fill(left, top, left + IMAGE_WIDTH, top + 2, AMBER);

        graphics.text(this.font, "BALISE", left + 10, top + 9, AMBER, false);
        String tag = "niveau " + level + "/4";
        graphics.text(this.font, tag, left + IMAGE_WIDTH - 10 - this.font.width(tag), top + 9, DIM, false);

        drawInfo(graphics, left, top, level);
        graphics.text(this.font, "Effet principal", left + 10, primaryRowY() - 10, DIM, false);
        graphics.text(this.font, "Effet secondaire (niveau 4)", left + 10, secondaryRowY() - 10, DIM, false);

        super.extractContents(graphics, mouseX, mouseY, partial);
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        // Le titre vanilla est déjà remplacé par "BALISE" ci-dessus ; seule l'étiquette de
        // l'inventaire du joueur reste à peindre.
        graphics.text(this.font, this.playerInventoryTitle, this.inventoryLabelX, this.inventoryLabelY,
                DIM, false);
    }

    private void drawInfo(GuiGraphicsExtractor graphics, int left, int top, int level) {
        if (level <= 0) {
            graphics.text(this.font, "Aucune pyramide : la balise n'a pas encore d'effet.",
                    left + 10, top + 22, FAINT, false);
            return;
        }
        double base = Aureole.active() ? Aureole.baseRange(level) : level * 10 + 10;
        String rangeLine;
        int rangeColour;
        if (this.pos != null) {
            double multiplier = Aureole.active()
                    ? Aureole.pyramidMultiplier(this.minecraft.level, this.pos, level) : 1.0d;
            double range = Aureole.scaledRange(base, multiplier);
            rangeLine = String.format(Locale.ROOT, "Portee : %.0f blocs (materiau x%.1f)",
                    range, multiplier);
            rangeColour = TEXT;
        } else {
            rangeLine = String.format(Locale.ROOT, "Portee : %.0f blocs avant materiau", base);
            rangeColour = DIM;
        }
        graphics.text(this.font, rangeLine, left + 10, top + 22, rangeColour, false);

        String verticalLine = Aureole.active() && Config.BEACON_VERTICAL_UNLIMITED.get()
                ? "Verticale : illimitee (du fond au ciel)"
                : "Verticale : symetrique, plafond deja tres haut";
        graphics.text(this.font, verticalLine, left + 10, top + 32, DIM, false);
    }

    private int primaryRowY() {
        return this.topPos + 56;
    }

    private int secondaryRowY() {
        return this.topPos + 90;
    }

    private List<Holder<MobEffect>> primaryOptions() {
        List<Holder<MobEffect>> all = new ArrayList<>();
        all.addAll(TIER1);
        all.addAll(Aureole.extraEffectsAt(1));
        all.addAll(TIER2);
        all.addAll(Aureole.extraEffectsAt(2));
        all.addAll(TIER3);
        all.addAll(Aureole.extraEffectsAt(3));
        return all;
    }

    private List<Holder<MobEffect>> secondaryOptions() {
        List<Holder<MobEffect>> all = new ArrayList<>();
        all.addAll(TIER4);
        all.addAll(Aureole.extraEffectsAt(4));
        return all;
    }

    private int requiredTier(Holder<MobEffect> effect) {
        if (TIER1.contains(effect)) {
            return 1;
        }
        if (TIER2.contains(effect)) {
            return 2;
        }
        if (TIER3.contains(effect)) {
            return 3;
        }
        if (TIER4.contains(effect)) {
            return 4;
        }
        return Aureole.extraEffectTier(effect);
    }

    /** Ne fait QUE mettre à jour le choix local — voir la Javadoc de {@link #pendingPrimary} pour
     *  pourquoi l'envoi réseau attend {@link #confirm()}. */
    private void choose(Holder<MobEffect> effect, boolean primaryRow) {
        if (primaryRow) {
            this.pendingPrimary = effect;
        } else {
            this.pendingSecondary = effect;
        }
        refreshConfirmButton();
    }

    /** Le même paquet que le bouton « confirmer » de vanilla, envoyé UNE fois pour les deux choix —
     *  pas de réseau neuf : voir la Javadoc de {@link #pendingPrimary}. */
    private void confirm() {
        if (this.minecraft.getConnection() == null || this.pendingPrimary == null) {
            return;
        }
        this.minecraft.getConnection().send(new ServerboundSetBeaconPacket(
                Optional.of(this.pendingPrimary), Optional.ofNullable(this.pendingSecondary)));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /**
     * Une icône d'effet, vraie {@code AbstractWidget} — voir la Javadoc de classe pour pourquoi ce
     * n'est plus une simple zone testée à la main dans {@code mouseClicked}.
     *
     * <p>{@code active} (champ de {@code AbstractWidget}) porte le déblocage de palier : c'est lui
     * qui empêche le clic sur une icône pas encore atteinte, exactement comme il empêche déjà le
     * clic sur {@link Phare#confirm} tant qu'il n'y a pas de paiement. {@link Phare#containerTick}
     * le remet à jour à chaque tick, la pyramide pouvant grandir ou s'effondrer pendant que l'écran
     * reste ouvert.
     */
    private final class EffectIcon extends AbstractWidget {
        private final Holder<MobEffect> effect;
        private final int tier;
        private final boolean primaryRow;

        EffectIcon(Holder<MobEffect> effect, int tier, boolean primaryRow, int x, int y) {
            super(x, y, ICON, ICON, effect.value().getDisplayName());
            this.effect = effect;
            this.tier = tier;
            this.primaryRow = primaryRow;
        }

        private boolean picked() {
            Holder<MobEffect> selected = this.primaryRow ? Phare.this.pendingPrimary : Phare.this.pendingSecondary;
            return selected != null && selected.equals(this.effect);
        }

        @Override
        public void onClick(MouseButtonEvent event, boolean doubleClick) {
            Phare.this.choose(this.effect, this.primaryRow);
        }

        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                float partial) {
            int x = this.getX();
            int y = this.getY();
            if (picked()) {
                graphics.fill(x - 2, y - 2, x + ICON + 2, y + ICON + 2, AMBER);
            } else if (this.isHovered) {
                graphics.fill(x - 1, y - 1, x + ICON + 1, y + ICON + 1, EDGE);
            }
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, Hud.getMobEffectSprite(this.effect), x, y, ICON, ICON);
            if (!this.active) {
                // Assombrit l'icône plutôt que de la cacher : un joueur doit voir ce qui
                // l'attend au palier suivant, pas seulement ce qu'il a déjà.
                graphics.fill(x, y, x + ICON, y + ICON, 0x90000000);
            }
            // Les icônes vanilla n'ont jamais porté leur nom : sans lui, Chance et Absorption sont
            // deux ronds jaunes indiscernables l'un de l'autre. Le niveau requis se lit dans la
            // légende quand l'effet n'est pas encore débloqué.
            if (this.isHovered) {
                Component name = this.effect.value().getDisplayName();
                graphics.setTooltipForNextFrame(Phare.this.font, this.active ? name
                        : name.copy().append(" (niveau " + this.tier + " requis)"), mouseX, mouseY);
            }
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            output.add(NarratedElementType.TITLE, this.getMessage());
        }
    }
}
