# Chronos — mod Fabric 1.20.1

Enregistre en permanence la position/vie/faim de chaque joueur ainsi que les
blocs qu'ils cassent ou posent, et permet de remonter le temps avec un item
craftable : l'**Éclat Chronos**.

## Comment ça marche

- Chaque tick (20x/sec), le mod prend un "instantané" de chaque joueur connecté
  (position, rotation, vie, faim), de chaque animal/monstre du monde, et
  enregistre les blocs cassés/posés par les joueurs.
- Dès que tu commences à rembobiner, l'enregistrement de TA position se met en
  pause (sinon il s'écraserait avec les positions du rewind) — mais le monde,
  lui, continue de tourner normalement pendant ce temps.

### Contrôles de l'Éclat Chronos

**Tout se joue au clic droit uniquement** (le clic gauche a été abandonné —
Minecraft n'a pas de mécanisme fiable pour "clic gauche avec un item en main
sans viser quoi que ce soit" sans passer par un mixin réseau, ce qui s'est
avéré instable). La distinction se fait entre un **tap** (clic rapide,
relâché en moins de 0,3s) et un **maintien** :

| Action | Effet |
|---|---|
| Tap (clic rapide) | Retour au présent, en timelapse |
| Maintien | Rembobine en arrière tant que maintenu, en timelapse |
| Accroupi + tap | Pose un point de sauvegarde à l'instant présent |
| Accroupi + maintien | File vers ce point de sauvegarde, en timelapse (continue même si tu relâches en cours de route) |

Le mouvement ne démarre jamais avant que le seuil de 0,3s soit dépassé — un
simple tap ne provoque donc aucun micro-saut visuel qui casserait
l'immersion.

En reculant : les blocs que tu as cassés réapparaissent (et l'item correspondant
est retiré de ton inventaire), les blocs que tu as posés disparaissent (et
l'item te revient), les animaux/monstres tués dans cette fenêtre ressuscitent
à leur position d'origine, et les animaux encore vivants sont repositionnés
à leur emplacement d'origine au fil du rembobinage. En avançant (retour au
présent), les blocs sont rejoués dans l'autre sens (le repositionnement des
animaux ne l'est pas, pour rester simple).

### Commande de secours

```
/chronos back 20    -> saute 20 minutes en arrière, instantanément (pas en timelapse)
/chronos status      -> combien de temps d'historique il te reste
```

## Craft

```
 D
D C D
 D
```
D = Diamant, C = Horloge (clock vanilla)

## Config — remonter "autant que tu veux"

Fichier généré au premier lancement : `config/chronos.properties`

```properties
# Durée max de l'historique en secondes (5 min par défaut).
# Augmente cette valeur pour pouvoir remonter plus loin.
# Attention : plus c'est grand, plus ça consomme de RAM.
buffer_seconds=300

# Vitesse de rewind : 1.0 = temps réel, 2.0 = 2x plus vite en arrière
rewind_speed=1.0

# Restaure la vie et la faim en plus de la position
restore_health_and_hunger=true

# Si true, ne restaure QUE les blocs que TOI tu as changés (évite les
# conflits si plusieurs joueurs rewind en même temps sur le même serveur)
only_revert_own_blocks=true
```

Mets `buffer_seconds` à `3600` pour avoir 1h d'historique, `86400` pour 24h
si ton serveur a de la RAM à revendre — c'est littéralement "autant que tu
veux", juste borné pour éviter une fuite mémoire si personne n'utilise jamais
l'item.

## Limites connues (honnêtes, pas cachées)

- Les blocs affectés en chaîne (eau qui coule, pistons, redstone, TNT) ne sont
  pas suivis — seulement les cassages/poses directs d'un joueur.
- La résurrection des animaux/monstres est du "best effort" : ça recrée une
  nouvelle entité à partir du NBT sauvegardé (nom, apparence, état identiques)
  mais ce n'est techniquement pas l'entité d'origine — elle aura un nouvel ID interne.
- Le repositionnement des animaux/monstres encore vivants ne se fait que sur
  ceux qui étaient à moins de 96 blocs d'un joueur au moment de l'enregistrement
  (pour ne pas surcharger le serveur en calculant pour toute la carte).
- En multijoueur, si deux joueurs rembobinent en même temps très près l'un de
  l'autre, il peut y avoir des interférences sur les entités partagées (les
  blocs, eux, sont gérés par joueur donc sans conflit entre vous).

## Compiler SANS rien installer (méthode simple)

Le dossier `.github/workflows/build.yml` fait compiler le mod automatiquement
par GitHub, gratuitement, dans le cloud. T'as juste besoin d'un compte
GitHub et d'un navigateur :

1. Va sur github.com, crée un compte si t'en as pas (gratuit).
2. Clique "New repository", donne-lui un nom (ex: `chronos-mod`), Public,
   crée-le.
3. Sur la page du repo vide, clique "uploading an existing file". Ouvre le
   dossier `chronos-mod` décompressé sur ton PC, sélectionne TOUT
   (fichiers + dossiers, y compris ceux qui commencent par un point comme
   `.github`) et glisse-dépose dans la page GitHub. Valide le commit.
4. Va dans l'onglet "Actions" du repo. Un build démarre automatiquement
   (déclenché par ton upload). Attends 2-3 minutes que le rond jaune devienne
   vert.
5. Clique sur le build terminé, descends jusqu'à "Artifacts", télécharge
   `chronos-mod-jar`. C'est un zip qui contient ton `.jar` compilé.

Aucune installation locale, aucune ligne de commande. La seule chose que tu
dois quand même installer, c'est **Fabric Loader** dans le launcher
Minecraft officiel (fabricmc.net/use, 2 clics) + **Fabric API**
(modrinth.com/mod/fabric-api, à mettre dans `mods/`) — ça c'est
incontournable pour jouer avec n'importe quel mod Fabric, c'est pas
spécifique à ce mod.

## Compiler en local (méthode alternative)

```bash
./gradlew build
```
Le jar sort dans `build/libs/chronos-1.0.0.jar`. À mettre dans le dossier
`mods/` d'une install Fabric 1.20.1 (Fabric Loader + Fabric API requis).

## Structure du code

```
ChronosMod.java              -> point d'entrée, branche les events Fabric
ChronosConfig.java           -> lecture/écriture config
history/TimeSnapshot.java    -> état d'un joueur à un tick
history/BlockChange.java     -> un changement de bloc réversible
history/HistoryManager.java  -> les deux buffers (joueurs + blocs)
listener/PlacementTracker.java -> détecte les poses de bloc (diff avant/après)
item/ChronosShardItem.java   -> logique de l'item de rewind
```
