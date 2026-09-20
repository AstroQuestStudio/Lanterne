# Radiographie du 20 septembre 2026 — le groupe `LambdaForm$MH/*.invoke` (~18 %)

> Même convention que `notes/hiz-occlusion-entites.md` : **VÉRIFIÉ** = commande lancée ou source
> primaire lue et citée (`javap` sur les jars réels, jamais `.mcsrc/`) ; **SUPPOSÉ** est signalé
> comme tel.

Contexte : dans la même session (512,5 s, 75179 images), neuf adresses distinctes
`java.lang.invoke.LambdaForm$MH/0x...invoke` totalisent environ 18 % de temps propre cumulé,
aucune isolément au-dessus de 1 % de temps **total** (donc absentes de l'arbre d'appel élagué).
La mission demandait de chercher, côté Lanterne d'abord, des fermetures capturantes recréées à
chaque image, et de vérifier si `net.neoforged.bus.EventBus.post` (0,8 % direct) en est le
vecteur.

---

## Verdict

**Aucun site concret isolable côté Lanterne au-delà de ce qui est déjà corrigé.** Un précédent
exact de ce même symptôme existe déjà dans ce dépôt — sur `EntityCullMixin`, corrigé avant cette
session (`@WrapOperation` → `@Redirect`, commit `96571d9` selon `notes/hiz-occlusion-entites.md`)
— et rien d'équivalent ne subsiste sur un chemin par image. `EventBus.post` a été vérifié en
bytecode réel : ce n'est **pas** un vecteur `invokedynamic`/`LambdaForm`, contrairement à
l'hypothèse de la mission. Le groupe reste dispersé et, avec les outils disponibles, non
rattachable à une ligne de code de Lanterne.

---

## 1. Le précédent déjà réglé (pas une découverte de cette passe)

`mixin/EntityCullMixin.java` documente déjà, dans son propre Javadoc (lignes 56-84), qu'il
utilisait `@WrapOperation` (MixinExtras) sur l'appel à `EntityRenderDispatcher.shouldRender`
**depuis `LevelExtractor.isEntityVisible`** — un point appelé une fois par entité par image — et
que cela produisait exactement la signature `LambdaForm$MH` observée dans une radiographie
antérieure (« scène de grange synthétique », `lab/Glass`), verifiée alors au `javap` du `.class`
tissé. Le correctif (`@Redirect`, qui appelle le gestionnaire avec des arguments typés, sans
`Operation<R>`, sans boîtage, sans `invokedynamic`) est **déjà en place** dans le fichier actuel
— vérifié en relisant le fichier tel qu'il est aujourd'hui dans ce worktree.

## 2. Balayage des mixins « client » : aucun autre `@WrapOperation` sur un chemin par image

`grep` de `@WrapOperation|@WrapWithCondition|@ModifyExpressionValue|@ModifyReturnValue|
@ModifyVariable` sur les 29 fichiers du bloc `"client"` de `lanterne.mixins.json` : les deux
seules occurrences actives de `@WrapOperation` dans **tout** le dépôt sont `PackIndexMixin`
(indexation de pack de ressources, au démarrage) et `ServerLoginPacketListenerImplMixin` (une
fois par connexion) — ni l'un ni l'autre sur un chemin par image. `UpscaleGameRendererMixin`
utilise `@ModifyVariable` sur `renderLevel` (chemin par image confirmé), mais cette annotation est
importée de `org.spongepowered.asm.mixin.injection` (Mixin de base), **pas** de MixinExtras : elle
ne passe pas par l'interface `Operation<R>` documentée dans `EntityCullMixin`, et ne devrait donc
pas produire ce type d'indirection — architecture confirmée par le même raisonnement que
`EntityCullMixin` a déjà établi pour ce dépôt, mais sans dump du bytecode tissé (`.mixin.out`)
pour ce fichier précis dans cette passe : point laissé en doute honnête, voir fin de note.

## 3. Balayage de `client/` : aucune fermeture capturante recréée par image

Comptage des `->` par fichier sous `client/` (38 fichiers avec au moins une occurrence de
`RenderLevelStageEvent`/`ClientTickEvent`/`@SubscribeEvent`, 43 fichiers au total avec des
lambdas). Les fichiers les plus denses en lambdas (`Dials.java` : 38, `screen/Console.java` : 22,
`shop/Counter.java` : 15, `regard/RegardSelfCheck.java` : 15, `ponder/Theatre.java` : 13,
`upscale/Nuancier.java` : 12…) sont, après lecture, des **écrans GUI** (ouverts seulement à la
demande du joueur), des **auto-diagnostics au démarrage** (`*SelfCheck.java`), ou de la **gestion
de packs de ressources / commandes** (`Nuancier.java` : recherche de fichiers, commandes client)
— aucun n'est sur le chemin de rendu exécuté à chaque image en jeu normal.

## 4. Lanterne n'écoute aucun évènement par image, et n'en poste aucun

- `grep` de `void on\w*\(RenderLevelStageEvent` sur tout le dépôt : **zéro résultat**. Les huit
  gestionnaires trouvés (`RegardSelfCheck`, `Bell`, `Longuevue`, `Usher`, `Hint`, `SouvenirClient`,
  `PaletteSelfCheck`, `Opener`) sont tous abonnés à `ClientTickEvent.Post` — vingt fois par
  seconde, pas par image (jusqu'à 363 images/s mesurées dans cette session).
- `grep` de `EVENT_BUS.post` sur tout le dépôt : un seul résultat, dans un commentaire Javadoc de
  `ServerLevelMixin.java` citant l'évènement vanilla `EntityTickEvent.Pre` — côté serveur, pas de
  rendu, et une citation, pas un appel réel. **Lanterne ne poste aucun évènement personnalisé.**

## 5. `EventBus.post` vérifié en bytecode réel : pas un vecteur `invokedynamic`

Extrait et `javap -p -c` sur `net/neoforged/bus/EventBus.class` et
`net/neoforged/bus/EventListenerFactory.class` (jar réel `bus-8.0.5.jar`, dépendance transitive
de NeoForge 26.3.0.3-beta) :

- `EventListenerFactory.createWrapper0` génère, **une fois par méthode d'écoute enregistrée**, une
  vraie classe cachée (`MethodHandles.Lookup.defineHiddenClassWithClassData`) qui implémente
  directement `EventListener` — ce n'est **pas** un proxy `LambdaMetafactory` lié par
  `invokedynamic` à chaque appel.
- La boucle de diffusion elle-même, `EventBus.post(T, EventListener[])` (bytecode lu en entier) :
  ```
  0: iconst_0
  ...
  12: invokevirtual  Method EventListener.invoke:(Levent;)V
  15: iinc 3, 1
  18: goto 2
  ```
  C'est un simple `for` avec un `invokevirtual` par écouteur — **aucun `invokedynamic`, aucune
  `LambdaForm`** dans ce chemin. Le 0,8 % de temps propre attribué à `EventBus.post` dans le
  rapport est donc un coût de diffusion direct, proportionnel au nombre d'écouteurs et à la
  fréquence de l'évènement posté — pas un artefact de boîtage lambda, et **pas** la source du
  groupe `LambdaForm$MH` séparé.

---

## Conclusion

Aucune ligne de Lanterne ne recrée de fermeture capturante par image, n'utilise plus
`@WrapOperation` sur un chemin par image, ne poste ni n'écoute d'évènement par image. Le seul
précédent connu et documenté de ce symptôme (`EntityCullMixin`) est déjà corrigé. Le mécanisme de
diffusion d'évènements de NeoForge, vérifié en bytecode réel, n'est pas lui-même un vecteur
`invokedynamic`. Le groupe dispersé de neuf adresses `LambdaForm$MH`, chacune sous 1 % de temps
total, provient donc très probablement d'ailleurs — le pipeline d'extraction/rendu vanilla
lui-même (fortement composé de `Stream`/`Optional`/`Predicate`/`Consumer` depuis la refonte
26.x, cf. `LevelExtractor`, `FrameGraphBuilder`) et/ou des mods compagnons (Sodium, Distant
Horizons) — hors de portée d'un mixin Lanterne sans preuve mesurée d'un site précis. Poser des
mixins uniquement pour « déslambdaïser » du code vanilla dispersé sur neuf sites sans qu'aucun ne
dépasse individuellement 1 % serait exactement le genre de changement cosmétique non mesuré que ce
dépôt refuse. **Aucun correctif proposé.**

## Doute honnête restant

`UpscaleGameRendererMixin` (`@ModifyVariable` sur `GameRenderer.renderLevel`, chemin par image
confirmé) a été écarté par raisonnement architectural (annotation Mixin de base, pas MixinExtras,
donc pas d'`Operation<R>` documenté) et non par lecture du bytecode **tissé** (celui que Mixin
produit réellement dans la classe `GameRenderer` chargée), faute d'avoir relancé le jeu avec
`-Dmixin.debug.export=true` dans cette passe. Le raisonnement s'appuie sur un mécanisme de Mixin
bien connu et déjà documenté dans ce dépôt (`EntityCullMixin`), mais ce n'est pas une vérification
bytecode directe sur ce fichier précis — à confirmer si ce point est repris.

## Ce qui a été fait cette passe

- Relu `EntityCullMixin.java` en entier (déjà corrigé, aucune modification apportée).
- `grep` ciblé sur les 29 fichiers `"client"` de `lanterne.mixins.json` pour les annotateurs
  MixinExtras à interface `Operation<R>`.
- Comptage et lecture des fichiers `client/` les plus denses en lambdas (`Dials`, `Console`,
  `Counter`, `RegardSelfCheck`, `Theatre`, `Nuancier`…) pour confirmer qu'aucun n'est sur le
  chemin de rendu par image.
- `grep` de tous les abonnements `@SubscribeEvent`/gestionnaires par tick ou par image, et de tous
  les appels `EVENT_BUS.post` dans le dépôt.
- Extrait et `javap -p -c` `net/neoforged/bus/EventBus.class` et
  `net/neoforged/bus/EventListenerFactory.class` depuis `bus-8.0.5.jar` (dépendance réelle de
  NeoForge 26.3.0.3-beta) pour vérifier le mécanisme de diffusion.
- Aucun fichier de production modifié.
