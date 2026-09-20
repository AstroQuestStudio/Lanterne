# Radiographie du 20 septembre 2026 — `isEntityVisible` / `Shroud` / `Shroud.clear`

> Même convention que `notes/hiz-occlusion-entites.md` : **VÉRIFIÉ** = commande lancée ou source
> primaire lue et citée (`javap` / sources décompilées officielles du jar patché réel
> `build/moddev/artifacts/minecraft-patched-26.3.0.3-beta*.jar`, jamais `.mcsrc/`, périmé,
> 26.1.2) ; **SUPPOSÉ** est signalé comme tel.

Contexte : dans la même session, `LevelExtractor.isEntityVisible` pèse 3,9 % de temps total et
`extractVisibleEntities` 4,6 %, avec `fr.clubcitrouille.lanterne.core.Shroud.clear` à 0,7 % de
temps propre. La mission demandait de vérifier que le Voile (`Shroud`) s'accroche bien **avant**
le travail de visibilité coûteux de vanilla, et d'examiner si `Shroud.clear` fait un travail
évitable (par exemple vider une collection déjà vide la plupart du temps).

---

## Verdict

**Conception déjà correcte, aucun bug, aucun correctif nécessaire.** Le point d'accroche est le
bon, l'ordre des tests est optimal — et il l'est pour une raison inverse de celle suggérée par la
mission : ce n'est pas vanilla qui fait un « raycast complet », c'est `Shroud`. Vanilla ne fait
que des comparaisons de boîte/distance, bon marché ; le lancer de rayon coûteux est **le module de
Lanterne lui-même**, et il s'exécute déjà en second, seulement pour les entités qui ont déjà passé
le test bon marché de vanilla. De plus, `Shroud.clear` n'est pas une méthode qui vide une
collection : c'est le nom de la primitive de parcours de rayon elle-même.

---

## 1. Le point d'accroche, vérifié sur la source décompilée réelle

`net/minecraft/client/renderer/extract/LevelExtractor.java` (sources officielles
26.3.0.3-beta) :

- `extractVisibleEntities` (ligne 268) itère `this.level.entitiesForRendering()` et, pour chaque
  entité, teste `isEntityVisible(...)` (ligne 281) **avant** d'appeler `this.extractEntity(entity,
  entityPartialTicks)` (ligne 290) — la construction complète de l'état de rendu (pose, calques
  d'équipement, éclairage, texture), le coût que `Shroud` cherche à éviter. C'est exactement ce que
  documente le Javadoc de `EntityCullMixin` : refuser **avant** ce point économise tout ce travail,
  refuser après n'économiserait que le dessin.
- `isEntityVisible` (lignes 298-308) est une méthode publique unique, appelée depuis
  `extractVisibleEntities` et, selon le Javadoc déjà écrit dans `EntityCullMixin`, depuis
  l'afficheur de boîtes de collision du débogage — soit exactement les deux appelants que le
  mixin annonçait.

`EntityCullMixin.lanterne$veil` (`@Redirect` sur l'appel à
`EntityRenderDispatcher.shouldRender` **depuis l'intérieur** de `isEntityVisible`) : appelle
d'abord le vrai `shouldRender` vanilla, ne teste `Shroud.hidden(...)` **que s'il a réussi**. C'est
bien le point d'accroche unique du Voile, confirmé par lecture du fichier actuel.

## 2. Ce que fait réellement le test « coûteux » de vanilla — et ce n'est pas un raycast

`net/minecraft/client/renderer/entity/EntityRenderDispatcher.java` ligne 123-126 : `shouldRender`
délègue à `EntityRenderer.shouldRender`. `net/minecraft/client/renderer/entity/
EntityRenderer.java` lignes 64-80 (lu en entier) :

```java
public boolean shouldRender(T entity, Frustum culler, double camX, double camY, double camZ, float partialTicks) {
    if (!entity.shouldRender(camX, camY, camZ)) {      // distance
        return false;
    }
    if (!this.affectedByCulling(entity)) {
        return true;
    }
    AABB boundingBox = this.getBoundingBoxForCulling(entity, partialTicks).inflate(0.5);
    ...
    if (culler.isVisible(boundingBox)) {                // test de frustum sur une boîte
        return true;
    }
    ...
}
```

Aucun lancer de rayon, aucun parcours de bloc — une comparaison de distance au carré puis un test
boîte-contre-plans-de-frustum. C'est très exactement ce que documentait déjà le Javadoc
d'`EntityCullMixin` (« quelques comparaisons de boîte, bien moindre qu'un parcours de grille »),
et cette passe le vérifie indépendamment sur la source réelle plutôt que de le reprendre tel quel.
La mission parlait d'un « raycast complet » côté vanilla à éviter en second — il n'existe pas : le
seul raycast de ce chemin est celui de `Shroud`, et il est déjà placé **après**, pas avant. L'ordre
en place est donc l'ordre correct ; l'inverser (raycast avant le test bon marché) coûterait plus
cher, pas moins — exactement ce que le Javadoc de `EntityCullMixin` affirme déjà, confirmé ici sur
la source vanilla elle-même plutôt que pris pour argent comptant.

## 3. Le test `isSectionCompiledAndVisible` qui suit n'est pas un doublon

Après le `||` (`shouldRender(...) || hasIndirectPassenger(...)`), `isEntityVisible` fait encore
`this.level.isOutsideBuildHeight(...) || this.levelRenderer.isSectionCompiledAndVisible(blockPos,
chunkFadeDuration)` (ligne 304) — un test sur la **section de terrain** contenant l'entité (compilée
et visible, avec un délai de fondu enchaîné `chunkFadeDuration`), pour éviter qu'une entité
apparaisse avant le terrain qui la contient. C'est un mécanisme de **fondu d'apparition**, sans
rapport avec l'occlusion par un mur : il ne fait ni ne remplace le travail de `Shroud`. Pas de
double comptage, pas de duplication de logique.

`notes/hiz-occlusion-entites.md` (18 septembre 2026) a déjà exploré, et explicitement écarté pour
cette passe, un remplacement du raycast CPU de `Shroud` par une pyramide Hi-Z GPU — pour des
raisons de décalage d'une image et d'absence de synchronisation GPU→CPU mesurée dans ce moteur.
Rien dans cette radiographie ne remet ce verdict en cause ; il n'est pas rouvert ici.

## 4. `Shroud.clear` : correction du nom, pas un défaut

`core/Shroud.java` ne déclare **qu'une seule** méthode nommée `clear` (ligne 368, privée) :

```java
private static boolean clear(Level level, double x0, double y0, double z0,
        double x1, double y1, double z1, BlockPos.MutableBlockPos cursor) {
```

C'est la primitive de parcours voxel d'Amanatides et Woo — celle qui répond à « ce segment est-il
**dégagé** (*clear*) de tout bloc opaque » — appelée par `spotHidden` (particules/entités de bloc)
et par `reachable` (créatures, via jusqu'à cinq points sondés). Ce n'est **pas** une méthode qui
vide une collection ; l'hypothèse de la mission (« vide-t-elle une collection déjà vide la plupart
des images ? ») ne s'applique pas à ce code — il n'y a pas d'autre méthode `clear` dans la classe
qui pourrait correspondre à cette hypothèse. Les méthodes qui *vident vraiment* des collections
sont `openFrame()` (remise à zéro du compteur par image, purge conditionnelle des caches au-delà
de 8192 entrées) et `forget()` (changement de monde) — aucune des deux ne s'appelle `clear` et
aucune des deux n'apparaît dans le rapport à un coût mesurable.

0,7 % de temps propre pour la primitive de raycast elle-même, sur une session de 512 s, est un
travail **réel et déjà borné** par construction : 64 lancers par image au maximum (`budget`),
un cache de 100 ms par entité avec décalage anti-rafale (`stagger`, dont le Javadoc de la classe
cite un gain de médiane déjà mesuré : 5,03 ms contre 12,61 ms sans le module), cinq points sondés
au lieu de huit, arrêt au premier point atteint. Réduire encore ce coût demanderait d'affaiblir une
de ces garanties déjà justifiées et mesurées dans le Javadoc de la classe — pas une amélioration
« gratuite ».

---

## Conclusion

Le point d'accroche est unique et correct (avant `extractEntity`, le vrai coût). L'ordre des tests
est déjà optimal, et il l'est pour la raison inverse de celle suggérée par la mission : c'est
`Shroud`, pas vanilla, qui fait le travail coûteux, et il s'exécute déjà en second. Le test de
section qui suit est un mécanisme différent (fondu d'apparition), pas un doublon. `Shroud.clear`
n'est pas une méthode de nettoyage de collection mais la primitive de raycast elle-même, déjà
bornée par un budget, un cache et un anti-rafale mesurés. **Aucun correctif proposé** — le module
est déjà dans l'état que la mission cherchait à vérifier.

## Ce qui a été fait cette passe

- Relu en entier `core/Shroud.java`, `mixin/EntityCullMixin.java`, `mixin/ParticleVeilMixin.java`,
  `mixin/BlockEntityVeilMixin.java` (aucune modification).
- Extrait et lu `net/minecraft/client/renderer/extract/LevelExtractor.java` (sources
  décompilées officielles 26.3.0.3-beta) : `extractVisibleEntities`, `isEntityVisible`,
  `extractEntity`.
- Extrait et lu `net/minecraft/client/renderer/entity/EntityRenderDispatcher.java` et
  `EntityRenderer.java` (même source) pour vérifier le contenu réel de `shouldRender` vanilla.
- Relu `notes/hiz-occlusion-entites.md` (verdict antérieur sur une alternative GPU, non rouvert).
- Aucun fichier de production modifié.
