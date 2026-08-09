# Chronos — mod Fabric 1.20.1

Enregistre en permanence la position/vie/faim de chaque joueur ainsi que les
blocs qu'ils cassent ou posent, et permet de remonter le temps avec un item
craftable : l'**Éclat Chronos**.

## Comment ça marche

- Chaque tick (20x/sec), le mod prend un "instantané" de chaque joueur connecté
  (position, rotation, vie, faim) et le stocke dans un buffer par joueur.
- Chaque cassage/pose de bloc par un joueur est aussi enregistré avec l'état
  du bloc avant le changement.
- Ces buffers sont bornés dans le temps (voir config ci-dessous), pour ne pas
  faire fuir la RAM du serveur.
- **Clic droit maintenu** avec l'Éclat Chronos en main = rewind. Tant que tu
  maintiens, le mod dépile ton historique tick par tick : tu es téléporté en
  arrière, ta vie/faim est restaurée, et les blocs que tu as toi-même
  cassés/posés dans cette fenêtre sont remis à leur état d'origine.
- Relâche le clic = tu t'arrêtes là. Le jeu recommence à enregistrer
  normalement à partir de ce nouveau point dans le temps (comme une vraie
  branche temporelle — ce qui a été "rembobiné" est définitivement effacé).

## Saut temporel précis (commande)

En plus de l'item (rewind visuel en temps réel), tu peux sauter direct à un
moment précis avec une commande (nécessite d'être op, niveau 2 par défaut) :

```
/chronos back 20      -> saute 20 minutes en arrière, instantanément
/chronos back 0.5      -> saute 30 secondes en arrière
/chronos status         -> combien de temps d'historique il te reste
```

Ça restaure aussi tous les blocs que tu as toi-même cassés/posés dans la
fenêtre traversée. Item et commande partagent le même historique : si tu
fais `/chronos back 5` puis que tu ressors l'Éclat Chronos, il continue
depuis là où la commande t'a laissé (pas de doublon, pas de reset).

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

- Le rewind de blocs ne capture que les cassages/poses **directs** d'un
  joueur (clic gauche/droit). Les effets en chaîne (eau qui coule, pistons,
  redstone, TNT) ne sont pas annulés automatiquement — seulement noter que
  ce n'est pas dans le scope initial, ce serait un mixin séparé si tu veux
  aller plus loin.
- En multijoueur, si deux joueurs remontent le temps en même temps sur la
  même zone, il peut y avoir des conflits d'ordre — `only_revert_own_blocks`
  limite déjà pas mal ce risque en pratique solo/petit groupe.
- Le rewind consomme définitivement l'historique traversé (pas de "avance
  rapide" après coup). Si tu veux un mode "annuler le rewind", c'est
  ajoutable mais pas fait ici.

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
