package fr.clubcitrouille.lanterne.client.regard;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * Vérification automatique du {@link Sight} (« Le Regard »), sans humain devant l'écran ni
 * mouvement de caméra confié au hasard — même besoin que {@code client.screen.PaletteSelfCheck} et
 * {@code lab.Vertige}, mais Vertige seul ne suffit pas ici : faire tourner la caméra à l'aveugle
 * espère croiser un conteneur, ça ne le garantit pas. Cette classe BÂTIT ses quatre cibles avant de
 * les regarder.
 *
 * <h2>Bâtir la scène plutôt que fouiller le monde existant</h2>
 *
 * <p>Un monde de banc ({@code run/saves/banc}) n'a aucune raison de contenir un coffre garni, un four
 * en train de cuire et un entonnoir au bon endroit — et un four ne cuit que si un vrai joueur y a mis
 * le feu. {@link #build} pose les quatre directement, en lisant le vrai serveur intégré
 * ({@code Minecraft.getSingleplayerServer()}) depuis le fil client, exactement comme
 * {@code lab.Glass#populate} pose son troupeau : en solo, client et serveur intégré partagent le même
 * processus, et cette classe ne vit que sur la distribution client — sans danger pour un serveur
 * dédié.
 *
 * <h2>Une cuisson réelle, pas simulée</h2>
 *
 * <p>Le four reçoit du bœuf cru et du charbon dans ses cases, puis c'est le TICK NORMAL du jeu qui
 * l'allume et fait avancer sa cuisson — rien n'est écrit de force dans {@code cookingTimer}. Au
 * moment de la capture, plusieurs secondes de vrais ticks se sont écoulées : la barre que
 * {@link Sight} affiche vient donc d'une vraie progression, pas d'un nombre choisi pour la démo.
 *
 * <h2>Viser sans souris : la trigonométrie plutôt que l'espoir</h2>
 *
 * <p>Le joueur est épinglé à un point fixe ({@link #camX}/{@link #camY}/{@link #camZ}, capturés à la
 * position d'apparition) et réorienté à CHAQUE image vers le centre exact de la cible du moment —
 * même principe que {@code lab.Glass#pose} : reposer à chaque image élimine toute dérive de gravité
 * ou de bousculade avant la capture, plutôt que de l'espérer. {@link #aimAt} résout l'angle exact
 * ({@code atan2}) au lieu d'un cap approché : les quatre cibles tiennent dans un rayon de moins de
 * trois blocs du point d'observation, sous la portée d'interaction d'ENTITÉ la plus stricte de ce
 * moteur (trois blocs en survie — voir {@code core.Cadence}, {@code DEFAULT_ENTITY_INTERACTION_RANGE}),
 * pour que {@code Minecraft.hitResult}/{@code crosshairPickEntity} — ce que {@link Sight} lit
 * réellement, jamais un rayon à part — désignent la cible quel que soit le mode de jeu.
 *
 * <h2>La vache ne bouge pas</h2>
 *
 * <p>{@code setNoAi(true)} fige la vache à sa position de naissance. Sans cela, nuit ou pas, dix
 * secondes suffisent à une vache pour s'éloigner du point visé — et la dernière capture ne
 * montrerait plus rien sous le viseur. Une scène déterministe ne doit rien laisser au hasard qu'il
 * est possible de supprimer.
 *
 * <h2>Gated, comme le reste de ce laboratoire</h2>
 *
 * <p>{@code LANTERNE_REGARD_TEST=1} uniquement — jamais actif pour un joueur normal. Sans variable,
 * cette classe ne fait rien du tout.
 *
 * <h2>La séquence</h2>
 *
 * <p>Après un monde vu, une pause de trois secondes laisse le temps au serveur intégré de bâtir la
 * scène ({@link #build} est mise en file via {@code IntegratedServer#execute}, donc asynchrone d'un
 * tick ou deux). Puis, pour chacune des quatre cibles : une seconde pour se stabiliser sur la cible,
 * une capture, nommée pour ce qu'elle montre plutôt que pour l'instant où elle a été prise —
 * {@code regard-coffre.png}, {@code regard-four.png}, {@code regard-entonnoir.png},
 * {@code regard-entite.png}, toutes dans {@code screenshots/} à la racine de lancement.
 */
@EventBusSubscriber(modid = Lanterne.ID, value = Dist.CLIENT)
public final class RegardSelfCheck {
    private static final boolean ARMED = "1".equals(System.getenv("LANTERNE_REGARD_TEST"));

    /**
     * Distance des cibles au point d'observation, en blocs, dans chaque direction horizontale.
     *
     * <p>Le rayon diagonal qui en résulte (2,8 blocs) reste sous les trois blocs de
     * {@code DEFAULT_ENTITY_INTERACTION_RANGE} en survie — voir la Javadoc de classe.
     */
    private static final int FORWARD = 2;
    private static final int[] OFFSET_X = {-2, -1, 1, 2};

    private static final String[] FILES = {
            "regard-coffre.png", "regard-four.png", "regard-entonnoir.png", "regard-entite.png",
    };

    private static boolean wasInWorld;
    private static long worldSeenAtNanos;
    private static int step;
    private static boolean buildQueued;
    private static int targetIndex = -1;

    private static double camX;
    private static double camY;
    private static double camZ;

    /** Publiés par le fil serveur (voir {@link #build}), lus par le fil client : d'où {@code volatile}. */
    private static volatile Vec3 chestCenter;
    private static volatile Vec3 furnaceCenter;
    private static volatile Vec3 hopperCenter;
    private static volatile Vec3 cowCenter;

    private RegardSelfCheck() {}

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (!ARMED) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        boolean inWorld = mc.level != null && mc.player != null;

        if (inWorld && !wasInWorld) {
            worldSeenAtNanos = System.nanoTime();
            step = 0;
            targetIndex = -1;
            buildQueued = false;
            chestCenter = null;
            furnaceCenter = null;
            hopperCenter = null;
            cowCenter = null;
            // Même raison que lab.Snap et client.screen.PaletteSelfCheck : ce processus n'a jamais
            // le focus OS pendant une vérification automatisée, et sans ceci le jeu se met en pause
            // et floute tout derrière le menu pause dès la première image.
            mc.options.pauseOnLostFocus = false;
            Lanterne.LOG.info("[REGARD-TEST] monde apparu, construction de la scène programmée");
        }
        wasInWorld = inWorld;
        if (!inWorld) {
            return;
        }
        LocalPlayer player = mc.player;

        if (!buildQueued) {
            buildQueued = true;
            camX = player.getX();
            camY = player.getY();
            camZ = player.getZ();
            build(mc);
            return;
        }

        // Repose sur la cible courante à CHAQUE image, avant même de savoir si cette image est celle
        // d'une capture — voir la Javadoc de classe pour pourquoi (dérive de gravité/bousculade).
        if (targetIndex >= 0) {
            Vec3 target = targetFor(targetIndex);
            if (target != null) {
                aimAt(player, target);
            }
        }

        long elapsedSec = (System.nanoTime() - worldSeenAtNanos) / 1_000_000_000L;
        switch (step) {
            case 0 -> { if (elapsedSec >= 3) { aim(0); step = 1; } }
            case 1 -> { if (elapsedSec >= 4) { shot(0); step = 2; } }
            case 2 -> { if (elapsedSec >= 5) { aim(1); step = 3; } }
            case 3 -> { if (elapsedSec >= 6) { shot(1); step = 4; } }
            case 4 -> { if (elapsedSec >= 7) { aim(2); step = 5; } }
            case 5 -> { if (elapsedSec >= 8) { shot(2); step = 6; } }
            case 6 -> { if (elapsedSec >= 9) { aim(3); step = 7; } }
            case 7 -> { if (elapsedSec >= 10) { shot(3); step = 8; } }
            default -> {
                // Terminé : la caméra reste sur la dernière cible, pour qu'une capture manuelle
                // supplémentaire reste possible sans avoir à rejouer toute la séquence.
            }
        }
    }

    private static void aim(int index) {
        targetIndex = index;
        Vec3 target = targetFor(index);
        if (target == null) {
            Lanterne.LOG.warn("[REGARD-TEST] cible {} absente — la scène a-t-elle fini de se bâtir "
                    + "(pas de serveur intégré, ou LANTERNE_SAVE hors solo) ?", FILES[index]);
            return;
        }
        Lanterne.LOG.info("[REGARD-TEST] visée sur la cible {} ({})", index, FILES[index]);
    }

    private static void shot(int index) {
        Vec3 target = targetFor(index);
        if (target == null) {
            Lanterne.LOG.warn("[REGARD-TEST] capture {} sautée : aucune cible construite.",
                    FILES[index]);
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        String file = FILES[index];
        Screenshot.grab(mc.gameDirectory, file, mc.gameRenderer.mainRenderTarget(), 1,
                message -> Lanterne.LOG.info("[REGARD-TEST] capture {}/{} — {}",
                        Screenshot.SCREENSHOT_DIR, file, message.getString()));
    }

    private static Vec3 targetFor(int index) {
        return switch (index) {
            case 0 -> chestCenter;
            case 1 -> furnaceCenter;
            case 2 -> hopperCenter;
            default -> cowCenter;
        };
    }

    /**
     * Oriente le joueur, épinglé au point fixe, vers le centre exact de {@code target}.
     *
     * <p>Convention de ce moteur, confirmée par {@code lab.Vertige} et vérifiée en jeu : à tangage
     * nul, {@code yRot = 0} regarde vers le sud (+Z), et le vecteur horizontal avant vaut
     * {@code (-sin(yRot), cos(yRot))}. On en tire l'angle inverse par {@code atan2} plutôt que de
     * deviner un cap approché.
     */
    private static void aimAt(LocalPlayer player, Vec3 target) {
        Vec3 eye = new Vec3(camX, camY + player.getEyeHeight(), camZ);
        Vec3 delta = target.subtract(eye);
        double horizontal = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
        float yaw = (float) Math.toDegrees(Math.atan2(-delta.x, delta.z));
        float pitch = (float) Math.toDegrees(Math.atan2(-delta.y, horizontal));
        // snapTo pose position ET rotation dans le même geste, sans interpolation résiduelle — même
        // appel que lab.Vertige et lab.Glass#pose.
        player.snapTo(camX, camY, camZ, yaw, pitch);
    }

    /**
     * Bâtit les quatre cibles sur le serveur intégré, en file d'attente pour le fil serveur.
     *
     * <p>Asynchrone : {@link #chestCenter} et consorts ne sont publiés qu'une fois le tick serveur
     * suivant passé. Les trois secondes avant la première visée (voir le tableau de la Javadoc de
     * classe) sont la marge prise pour cette latence — bien plus que nécessaire en pratique, mais le
     * même ordre de grandeur que celui déjà choisi par {@code PaletteSelfCheck} pour un besoin
     * comparable.
     */
    private static void build(Minecraft mc) {
        IntegratedServer server = mc.getSingleplayerServer();
        if (server == null) {
            Lanterne.LOG.warn("[REGARD-TEST] pas de serveur intégré — LANTERNE_SAVE doit désigner un "
                    + "monde solo pour que ce test puisse bâtir sa scène.");
            return;
        }
        int originX = (int) Math.floor(camX);
        int originZ = (int) Math.floor(camZ) + FORWARD;
        server.execute(() -> {
            ServerLevel level = server.overworld();

            BlockPos chestPos = grounded(level, originX + OFFSET_X[0], originZ);
            level.setBlock(chestPos, Blocks.CHEST.defaultBlockState(), 3);
            if (level.getBlockEntity(chestPos) instanceof ChestBlockEntity chest) {
                chest.setItem(0, new ItemStack(Items.DIAMOND, 3));
                chest.setItem(1, new ItemStack(Items.BREAD, 12));
                chest.setItem(4, new ItemStack(Items.TORCH, 6));
                chest.setChanged();
            }
            chestCenter = centerOf(chestPos);

            BlockPos furnacePos = grounded(level, originX + OFFSET_X[1], originZ);
            level.setBlock(furnacePos, Blocks.FURNACE.defaultBlockState(), 3);
            if (level.getBlockEntity(furnacePos) instanceof FurnaceBlockEntity furnace) {
                // Bœuf + charbon, pas de tour de passe-passe sur les compteurs : voir la Javadoc de
                // classe — c'est le TICK du jeu qui allume et fait cuire, à partir d'ici.
                furnace.setItem(0, new ItemStack(Items.BEEF, 8));
                furnace.setItem(1, new ItemStack(Items.COAL, 8));
                furnace.setChanged();
            }
            furnaceCenter = centerOf(furnacePos);

            BlockPos hopperPos = grounded(level, originX + OFFSET_X[2], originZ);
            level.setBlock(hopperPos,
                    Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, Direction.NORTH),
                    3);
            if (level.getBlockEntity(hopperPos) instanceof HopperBlockEntity hopper) {
                hopper.setItem(0, new ItemStack(Items.IRON_INGOT, 5));
                hopper.setItem(2, new ItemStack(Items.REDSTONE, 20));
                hopper.setChanged();
            }
            hopperCenter = centerOf(hopperPos);

            BlockPos cowGround = grounded(level, originX + OFFSET_X[3], originZ);
            Cow cow = EntityTypes.COW.create(level, EntitySpawnReason.COMMAND);
            if (cow != null) {
                cow.snapTo(cowGround.getX() + 0.5, cowGround.getY(), cowGround.getZ() + 0.5, 0f, 0f);
                cow.setPersistenceRequired();
                // Immobile : voir la Javadoc de classe. Sans ceci, la vache a le temps de s'éloigner
                // de la cible visée avant la dernière capture.
                cow.setNoAi(true);
                if (level.addFreshEntity(cow)) {
                    cowCenter = cow.getBoundingBox().getCenter();
                }
            }

            Lanterne.LOG.info("[REGARD-TEST] scène bâtie — coffre {}, four {}, entonnoir {}, "
                    + "vache en {}.", chestPos, furnacePos, hopperPos, cowGround);
        });
    }

    /** La position au sol, sur la vraie carte des hauteurs — jamais un bloc flottant ou enterré. */
    private static BlockPos grounded(ServerLevel level, int x, int z) {
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        return new BlockPos(x, y, z);
    }

    private static Vec3 centerOf(BlockPos pos) {
        return new Vec3(pos.getX() + 0.5d, pos.getY() + 0.5d, pos.getZ() + 0.5d);
    }
}
