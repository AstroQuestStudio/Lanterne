# Le profil d'allocations, et la troisième règle d'instrument

Session du 14 septembre 2026, suite du journal du cerveau. Cible : ce qui alloue, parce que sur la
machine visée — un cœur unique — le ramasse-miettes ne tourne pas *à côté* du tick, il le **vole**.

## Ce que le profileur d'allocations a désigné

Charge : six cents villageois, mod complet, après l'accélération du cerveau.

```
17,99 Go échantillonné au total
 10,5 %  MemoryCondition$Registered.createAccessor → OptionalBox            1,88 Go
  4,3 %  Brain.getRunningBehaviors (original, phases témoins) → iterator      796 Mo
  3,0 %  SerializableChunkData.write → CompoundTag                            545 Mo
  2,0 %  VoxelShape.move → OffsetDoubleList                                   374 Mo
  1,9 %  ShufflingList.stream → ReferencePipeline$Head                        349 Mo
  ~7,5 % PoiManager.getInChunk (cinq pipelines distincts)                   ~1,37 Go
```

**Le moteur de lumière n'y figure pas.** La feuille de route le portait depuis longtemps comme
« 13 % des allocations, jamais attaqué » ; ce chiffre ne se vérifie pas sur cette charge. Starlight
— 5 537 lignes, 22 fichiers, remplacement complet du moteur — **n'est donc pas porté**. Une lumière
fausse fait apparaître des créatures en plein jour dans une base éclairée ; on ne prend pas ce
risque pour un poste que la mesure ne désigne pas.

## La boîte vide — module écrit, mesuré, gardé, non prouvé

Les comportements déclaratifs de 26.1 emballent la réponse du cerveau à chaque tentative de
démarrage :

```java
return new MemoryAccessor<>(brain, this.memory, OptionalBox.create(value));
```

Pour la condition `REGISTERED`, `value` est très souvent `Optional.empty()` : une mémoire enregistrée
mais vide est le cas ordinaire. On fabriquait donc une boîte neuve pour y ranger un vide, on la
consultait, et on la jetait.

`javap` sur DataFixerUpper 9.0.19 : classe `final`, champ `value` `final`, constructeur privé,
aucune mutation. **Immuable au sens strict**, donc partageable. La vérification n'était pas
facultative — ce projet a déjà payé une supposition sur une bibliothèque qu'il n'avait pas lue.

**Résultat : ×1,04, non prouvé.** 1 198 076 boîtes partagées, pour **vingt mégaoctets** en moins
par fenêtre.

### La troisième règle d'instrument

Vingt mégaoctets, quand le profileur annonçait 1,88 Go. L'écart n'est pas une erreur de mesure,
c'est une erreur de lecture. Le profileur d'allocations classe par **volume cumulé depuis
l'ouverture de l'enregistrement** — chargement du serveur, décantation, et les *deux* phases
entrelacées. Les 17,99 Go de son total ne sont pas le travail d'un tick.

> **Un pourcentage n'a de sens que rapporté à ce qui a été totalisé.** « Dix pour cent des
> allocations » désignait un poste réel, et ce poste pèse quatre pour cent d'une fenêtre de mesure.

Elle rejoint les deux précédentes : l'échantillonneur de temps fait monter les méthodes courtes que
le compilateur a incorporées, et le profileur d'allocations sur-pondère les objets minuscules et
fréquents d'un facteur soixante-dix.

Le module est **gardé** — il ne coûte qu'une comparaison `isEmpty()` sur un `Optional` déjà en main,
et vingt mégaoctets de moins toutes les vingt-cinq secondes sont du bon côté sur une machine où le
ramasse-miettes vole le tick. Il est marqué non prouvé, et le restera jusqu'à ce qu'une charge le
sorte de la dérive.

## `PoiManager.getInChunk` — examiné, non touché

Deuxième poste (~1,37 Go), et le code est franchement mauvais :

```java
return IntStream.rangeClosed(minSectionY, maxSectionY)   // IntPipeline$Head
    .boxed()                                              // IntPipeline$1  — emballe chaque entier
    .map(sectionY -> this.getOrLoad(...))                 // ReferencePipeline$3
    .filter(Optional::isPresent)                          // ReferencePipeline$2
    .flatMap(section -> section.get().getRecords(...));   // ReferencePipeline$7
```

Cinq pipelines par appel — les cinq entrées du profil, à la ligne près. La méthode est même marquée
`@VisibleForDebug`, et `getInSquare` l'appelle en production.

**Non attaqué, et délibérément.** Le remplacement évident — construire une liste puis la rendre en
flux — détruit la **paresse**. Les appelants de recherche de points d'intérêt s'arrêtent tôt
(`findFirst`, limites de comptage) ; matérialiser l'ensemble ferait travailler davantage sur les
grands rayons, précisément là où c'est le plus cher. Le gain en allocations se paierait en temps, et
la leçon de la boîte vide venait d'établir que ce gain-là est plus petit qu'il n'en a l'air.

Une version correcte devrait rester paresseuse tout en évitant les pipelines — c'est-à-dire écrire
un `Spliterator` à la main. C'est faisable, ce n'est pas anodin, et cela mérite sa propre session.

## Bilan de la séance

| Cible | Décision | Raison |
|---|---|---|
| Boîte vide des conditions de mémoire | **gardée**, non prouvée | gratuite, 20 Mo/fenêtre, immuabilité vérifiée |
| `PoiManager.getInChunk` | reportée | casserait la paresse ; demande un `Spliterator` écrit à la main |
| Moteur de lumière (Starlight) | **écartée** | absente du profil d'allocations ; 5 537 lignes et un risque de lumière fausse |
| `bitstorage` (Moonrise) | écartée | supprime la validation des bornes |
| Noisium | écartée | sans objet en 26.1.2, et son compteur de fluides est faux |
| `ai/pathing` (Lithium) | écartée | désactivée par ses propres auteurs |
