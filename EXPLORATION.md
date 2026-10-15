# Sentinel Gallery — Architecture Exploration

> Journal de découvertes pour le feature Sentinel Gallery.
> Branche: `sentinel-media-gallery`
> Dernière mise à jour: 2026-02-19

---

## Vue d'ensemble

La Sentinel Gallery est une galerie media avec filtrage par tags AI, ajoutée sur la branche `sentinel-media-gallery` du client Nextcloud Android. Elle est **intentionnellement découplée** de `FileDisplayActivity` — elle étend `FileActivity` directement pour éviter le poids du file browser.

**Commits clés:**
```
314aba2 refactor(gallery): Decouple SentinelGalleryFragment from FileDisplayActivity
5d6b50d test(gallery): Add unit tests for SentinelTagFilterHelper
6a89fc4 feat(gallery): Add Sentinel Gallery with AI tag filtering
```

**État actuel:** 850+ lignes ajoutées, 9 fichiers modifiés, changements non commités en cours.

---

## Carte des fichiers

### Fichiers source (Kotlin — nouveaux)

| Fichier | Rôle | Lignes |
|---------|------|--------|
| `ui/activity/SentinelGalleryActivity.kt` | Activity hôte, toolbar, SearchView, sort dialog | ~100 |
| `ui/fragment/SentinelGalleryFragment.kt` | Fragment principal: grille, chips tags, filtres, multi-select | ~450 |
| `ui/adapter/SentinelGalleryAdapter.kt` | RecyclerView adapter, thumbnails, DiffUtil, multi-select | ~200 |
| `ui/helpers/SentinelTagFilterHelper.kt` | Object stateless — logique de filtrage par tags (unit-testable) | ~60 |

### Fichiers modifiés (existants)

| Fichier | Modification |
|---------|-------------|
| `datamodel/VirtualFolderType.java` | Ajout `SENTINEL` à l'enum |
| `ui/preview/PreviewImageActivity.kt` | Branche SENTINEL dans `initViewPager()`, extras pour liste filtrée |
| `ui/preview/PreviewImagePagerAdapter.kt` | Nouveau constructeur 4-args pour liste pré-filtrée |
| `client/di/ComponentsModule.java` | `@ContributesAndroidInjector` pour Activity + Fragment |
| `ui/activity/DrawerActivity.java` | Nav item `nav_sentinel_gallery` + `openSentinelGallery()` |
| `AndroidManifest.xml` | Déclaration de `SentinelGalleryActivity` |

### Ressources

| Fichier | Contenu |
|---------|---------|
| `layout/activity_sentinel_gallery.xml` | DrawerLayout + toolbar + FrameLayout container |
| `layout/fragment_sentinel_gallery.xml` | Tag chips, date chips, AND/OR toggle, RecyclerView, empty state |
| `layout/item_sentinel_gallery.xml` | Thumbnail carré, badge tag, indicateur vidéo, checkbox |
| `menu/activity_sentinel_gallery.xml` | SearchView + Sort button |
| `menu/menu_sentinel_multiselect.xml` | Share, Download, Favorite, Delete |
| `menu/partial_drawer_entries.xml` | Nav item ajouté après Gallery |
| `values/strings.xml` | 14 strings ajoutées (drawer label, filtres, dates) |

### Test

| Fichier | Couverture |
|---------|-----------|
| `test/.../SentinelTagFilterHelperTest.kt` | 20 tests JUnit — AND/OR filter, extractTags, edge cases |

---

## Chaîne d'héritage

```
DrawerActivity (Java, Injectable)
  └── FileActivity (Java, abstract)
        └── SentinelGalleryActivity (Kotlin)
              └── héberge SentinelGalleryFragment
```

**Ce que SentinelGalleryActivity hérite:**
- `storageManager` (FileDataStorageManager) — via FileActivity
- `fileOperationsHelper` (share/download/delete/favorite) — via FileActivity
- `user` / account management — via FileActivity
- `setupDrawer()`, `menuItemId`, navigation drawer — via DrawerActivity

---

## Pipeline de filtrage (Data Flow)

```
1. DB QUERY (onResume)
   storageManager.allGalleryItems
   └── Même source que la Gallery standard (SQLite locale)

2. TAG EXTRACTION (loadMedia → extractTags)
   file.tags?.forEach { tag -> ... }
   → allTags: List<TagWithCount> trié par count desc

3. CONSTRUCTION DES CHIPS (buildTagChips)
   Pour chaque tag: Chip(text="$name ($count)")
   Couleur du tag via colorHex + contraste auto

4. PIPELINE DE FILTRAGE (applyFilterAndDisplay)
   ┌─ Step 1: Tag filter (AND ou OR selon toggle)
   ├─ Step 2: Text search (fileName contains query)
   ├─ Step 3: Date range (modification timestamp)
   └─ Step 4: Sort (FileSortOrder.sortCloudFiles)

5. AFFICHAGE (adapter.setFiles)
   DiffUtil.calculateDiff → mise à jour incrémentale

6. THUMBNAILS (par item, onBindViewHolder)
   Cache disque → HIT: affichage direct
                → MISS: placeholder + ThumbnailGenerationTask async

7. PREVIEW (clic sur image → PreviewImageActivity)
   Intent extras:
     EXTRA_VIRTUAL_TYPE = SENTINEL
     EXTRA_FILTERED_FILE_LIST = ArrayList(filteredImages)
   → PreviewImagePagerAdapter(4-arg) → PAS de re-query DB
   → Le swipe reflète exactement la grille filtrée
```

---

## Injection Dagger 2

**SentinelGalleryFragment** (`@Inject` fields):
- `accountManager: UserAccountManager`
- `viewThemeUtils: ViewThemeUtils`

**SentinelGalleryActivity**: Pas de `@Inject` propre — hérite de FileActivity.

**ComponentsModule.java**: Les deux sont enregistrés avec `@ContributesAndroidInjector`.

**ATTENTION:** Oublier l'entrée dans ComponentsModule = crash runtime, PAS erreur compile.

---

## Persistance de l'état

**SharedPreferences** (`"sentinel_gallery_prefs"`, MODE_PRIVATE):
| Clé | Type | Valeur |
|-----|------|--------|
| `selected_tags` | StringSet | Noms des tags sélectionnés |
| `use_and_filter` | Boolean | true=AND, false=OR |
| `date_filter` | String | Nom de l'enum DateFilterPeriod |

**Non persisté:** `customStartDate` / `customEndDate` — en mémoire seulement. Un filtre "Custom" restauré sans dates agit comme "All" jusqu'à nouvelle sélection.

---

## Décisions de design notables

1. **Découplage de FileDisplayActivity** — évite le file browser complet, garde file operations via FileActivity

2. **`storageManager` timing** — accédé dans `onResume()`, pas `onViewCreated()` (le binder peut ne pas être connecté)

3. **VirtualFolderType.SENTINEL** — bypass total des queries DB dans PreviewImagePagerAdapter. Le constructeur 4-args prend la liste filtrée directement

4. **Deux `TagWithCount` parallèles** — Le helper a `TagWithCount(tagName, tagColor, count)`, le fragment a `TagWithCount(tag: Tag, count)`. Le fragment n'utilise PAS `SentinelTagFilterHelper.extractTagsWithCounts()` — il a sa propre implémentation inline dans `extractTags()`

5. **AND par défaut** — `useAndFilter = true`. Toggle: "ALL" = AND, "ANY" = OR

---

## Graphe de dépendances

```
SentinelGalleryActivity
├── FileActivity (storageManager, fileOperationsHelper, user)
│   └── DrawerActivity (drawer, toolbar, menuItemId, nav)
├── SentinelGalleryFragment (hosted)
├── SortingOrderDialogFragment + FileSortOrder
└── ActivitySentinelGalleryBinding

SentinelGalleryFragment
├── FileDataStorageManager.allGalleryItems ← DB
├── OCFile + Tag (data models)
├── SentinelGalleryAdapter
├── SentinelTagFilterHelper (stateless, no deps)
├── PreviewImageActivity (navigation)
│   └── PreviewImagePagerAdapter (4-arg ctor)
├── PreviewMediaActivity (video/audio)
├── RemoveFilesDialogFragment
├── VirtualFolderType.SENTINEL
├── UserAccountManager, ViewThemeUtils (@Inject)
├── Log_OC, MimeTypeUtil, FileSortOrder
└── FragmentSentinelGalleryBinding

SentinelGalleryAdapter
├── ThumbnailsCacheManager (cache + async)
├── ItemSentinelGalleryBinding
├── OCFile, MimeTypeUtil, ViewThemeUtils

SentinelTagFilterHelper
├── OCFile (datamodel)
└── Tag (lib.resources.tags)
    [Aucune dépendance Android framework]
```

---

## Statut du build (2026-02-19)

**BUILD BLOQUÉ** — La branche contient des commits upstream ("Declarative UI") qui dépendent de `android-library:f4feed395e...` — cet artifact retourne **404 sur JitPack**. Ce n'est PAS lié au code Sentinel.

**Options pour débloquer:**
1. Cherry-pick les 4 commits Sentinel sur une branche fraîche depuis master
2. Cloner android-library localement avec `includeBuild`
3. Attendre que JitPack publie l'artifact

**Les unit tests (`SentinelTagFilterHelperTest`) sont aussi bloqués** — même erreur de résolution de dépendance.

---

## Points d'attention pour la suite

- [ ] **BLOQUÉ** Débloquer le build (voir section ci-dessus)
- [ ] Tester les 20 unit tests du TagFilterHelper
- [ ] Vérifier le comportement multi-select (share/download/favorite/delete)
- [ ] Tester le flow preview image avec liste filtrée
- [ ] Vérifier la persistance des filtres (kill app → reopen)
- [ ] Refactorer les deux TagWithCount en un seul?
- [ ] Persister les dates custom?
- [ ] Performance avec grande collection (1000+ images)
