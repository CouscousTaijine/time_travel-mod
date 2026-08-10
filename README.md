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

## Config

Fichier généré au premier lancement : `config/chronos.properties`

```properties
# Fenêtre d'historique FLUIDE (20x/sec), gardée en RAM. Quelques minutes
# suffisent, c'est elle qui donne un rewind bien lisse dans la période récente.
buffer_seconds=300

# Vitesse de rewind : 1.0 = temps réel, 2.0 = 2x plus vite en arrière
rewind_speed=1.0

# Restaure la vie et la faim en plus de la position
restore_health_and_hunger=true

# Sauvegarde l'historique sur le disque (survit aux redémarrages du serveur)
persistence_enabled=true

# Nombre de jours d'historique gardés sur le disque, à 1 point/seconde.
# ~2-4 Mo par jour par joueur compressé. persist_days=30 reste raisonnable
# (~100 Mo/joueur). C'est ÇA qui te permet de remonter des jours en arrière.
persist_days=3
```

### Remonter des jours en arrière : c'est possible, voici comment

Oui, tu peux quitter la partie et revenir des jours après en arrière. Concrètement :
- Les **5 dernières minutes** de jeu sont gardées en pleine fluidité (20 positions/sec)
  en mémoire, pour un rewind bien lisse dans le passé récent.
- **Au-delà**, un point de position est sauvegardé sur le disque **une fois par
  seconde**, dans `<ton monde>/chronos/<uuid-du-joueur>.dat`, compressé comme
  n'importe quelle sauvegarde Minecraft. C'est ça qui permet de remonter loin
  sans exploser le disque : ~2-4 Mo/jour/joueur, donc même un mois d'historique
  reste sous les 150 Mo par joueur — rien à voir avec des gigas.
- Quand tu rembobines au-delà de la fenêtre récente, le timelapse devient un
  peu moins fluide (1 saut par seconde au lieu de 20), mais ça reste un
  timelapse progressif, pas un téléport instantané.
- **Limite honnête** : seule TA position/vie/faim est gardée à ce niveau de
  persistance. Les blocs cassés/posés et les animaux tués ne sont annulables
  qu'au sein de la même session de jeu (pas après un redémarrage du serveur)
  — sinon il aurait fallu tout re-sauvegarder en continu, ce qui aurait
  vraiment fait grossir les fichiers. Si tu veux ça aussi plus tard, c'est
  faisable, dis-le-moi.

## Limites connues (honnêtes, pas cachées)

- Les blocs affectés en chaîne (eau qui coule, pistons, redstone, TNT) ne sont
  pas suivis — seulement les cassages/poses directs d'un joueur.
- La résurrection des animaux/monstres est du "best effort" : ça recrée une
  nouvelle entité à partir du NBT sauvegardé (nom, apparence, état identiques)
  mais ce n'est techniquement pas l'entité d'origine — elle aura un nouvel ID interne.
- Le repositionnement des animaux/monstres encore vivants ne se fait que sur
  ceux qui étaient à moins de 96 blocs d'un joueur au moment de l'enregistrement.
- Les blocs/animaux ne sont annulables/ressuscitables que dans la session de
  jeu en cours (voir ci-dessus) — seule la position/vie/faim survit à un
  redémarrage du serveur.
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
