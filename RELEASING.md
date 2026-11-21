# Publier une version

## Versions

- **`VERSION`** (`VERSION_MAJOR`, `VERSION_MINOR`, `PATCHLEVEL`) est la source de la version.
  - Gradle en tire `versionName` (`X.Y.Z`) et `versionCode` (`X×10000 + Y×100 + Z`). Ce dernier est toujours croissant, pour qu'Android accepte la mise à jour.
- Le tag de release est `vX.Y.Z`.
- Dans un dépôt git, hors du commit taggé ou avec des modifications, la version porte le commit : `0.2.0+a1b2c3d4[.dirty]`. `make release` refuse un tel APK.
- Les builds de dev (`make debug`, `make install`) s'installent sous `dev.ppp0.poele.debug`, « Poêle (dev) », à côté de la version normale. Ils ne proposent jamais de mise à jour.

## Clé de signature

Toutes les releases sont signées avec **la même clé**. Android refuse une mise à jour signée par une autre clé : perdre la clé oblige à désinstaller l'appli (et ses réglages) pour installer la suivante.

- Créée une seule fois par `make keystore` (RSA 4096, 30 ans) dans `~/.config/poele/` :
  - `poele-release.jks` : la clé ;
  - `poele-release.properties` : le mot de passe.
  - Ni l'un ni l'autre ne doit aller dans le dépôt.
- **Sauvegarder les deux fichiers hors de cette machine** (gestionnaire de mots de passe, support physique).
- Autre emplacement possible : la variable `POELE_RELEASE_SIGNING`, pointant sur le `.properties`.
- Empreinte SHA-256 du certificat : `CC:F7:2C:FA:68:EF:EA:C3:0E:D1:CC:79:97:6D:38:B7:C4:31:1C:9A:10:5A:34:C6:D1:91:89:F2:E5:F5:37:7F`. Elle est aussi reprise dans les notes de chaque release.

## Procédure

1. Monter la version dans `VERSION`, et ajouter la section `## vX.Y.Z — AAAA-MM-JJ` en tête de `CHANGELOG.md`.
2. `make release` enchaîne :
   - les tests ;
   - le build release et sa signature ;
   - la vérification du certificat ;
   - la génération de `release/poele-vX.Y.Z/` : APK, `.sha256`, `.cert.txt`, `update.json` et notes `.md`.
3. `make install-release` sur le téléphone, puis un essai rapide avec le poêle.
4. `make release-github` crée la release `vX.Y.Z` sur le dépôt `poele.updateRepo` (`gradle.properties`), avec l'APK et `update.json`. Il demande confirmation avant.

## Mise à jour automatique

- Le dépôt GitHub doit être **public**, pour être lisible sans compte. L'appli n'embarque aucun jeton.
- Au démarrage, l'appli :
  - lit `https://api.github.com/repos/<dépôt>/releases/latest`, puis l'asset `update.json` ;
  - si le `versionCode` publié est plus grand que le sien, affiche un bandeau sur l'accueil ;
  - à la demande, télécharge l'APK et vérifie son SHA-256 ;
  - ouvre l'installeur Android, qui demande confirmation.
  - La première fois, Android demande d'autoriser Poêle à installer des applications.
- **Publier une release, c'est la livrer à tous les téléphones** : ne le faire qu'après l'essai de l'étape 3.
- Prérequis sur ce PC : `gh auth login`, une fois.
