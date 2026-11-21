# Mini-app Android « Poêle » — spécification v0.1

*2026-11-21 · statut : brouillon à valider*

## 1. Objectif

Piloter le poêle Ravelli (module Wi-Fi **WiNET**, Net Software) depuis un téléphone Android, **en local uniquement**, avec une détection du module qui fonctionne là où l'appli officielle échoue.

L'appli officielle (`it.netsoftware.winetlauncher_ravelli_temp` 1.3.3) n'est qu'un lanceur : découverte UDP puis WebView sur `management.html`. Elle n'apporte aucune fonction au-delà de la page web du module.

## 2. Périmètre

### v1 (obligatoire)
- Découverte robuste du module + saisie manuelle de l'IP en secours.
- Accueil : état, alarme, température ambiante, marche/arrêt, consigne, puissance, ventilation.
- Programmes chrono : lecture, édition, ajout, suppression, activation globale.
- Horloge du poêle : affichage, écart, synchronisation sur le téléphone.
- Écran Module & réseau : firmware, Wi-Fi, réseau, cloud (§4.6).
- Écran diagnostic : registres bruts, journal des requêtes.

### v2 (souhaitable)
- Widget écran d'accueil (état + température), tuile Réglages rapides marche/arrêt.
- ~~Notification locale sur alarme~~ : fait en v1.2 (§12).
- Courbe thermique (`key=035`).

### Hors périmètre
- Cloud Net Software, Netatmo, Telegram, mise à jour du firmware (OTA), appairage TSense / ESP-NOW.
- Réglages que le module **ne publie pas** (voir §4.4).

## 3. Exigences non fonctionnelles

| Sujet | Exigence |
|---|---|
| Réseau | Pilotage du poêle en local uniquement, aucune donnée du poêle ne sort du LAN. Pas d'analytics, pas de SDK tiers de tracking. **Seule sortie Internet** : la vérification de mise à jour (§10), une requête vers `api.github.com` au démarrage. |
| Charge module | **≤ 2 requêtes/s**, **une seule requête en vol** (file d'attente), comme l'appli web d'origine. Polling rapide suspendu quand l'appli n'est pas au premier plan ; en arrière-plan, un seul contrôle toutes les 15 min (§12). |
| Timeouts | 10 s par requête ; état « hors ligne » visible ; reprise automatique. |
| Écritures | Uniquement sur action explicite de l'utilisateur. Confirmation pour marche/arrêt, horloge, enregistrement/suppression de programmes. |
| Plateforme | Kotlin, Jetpack Compose. **Systèmes récents uniquement** : minSdk 33 (Android 13+), compile/targetSdk 36 ou 37 selon le plugin Android Gradle disponible. Pas de rétrocompatibilité à maintenir. |
| Permissions | `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, `REQUEST_INSTALL_PACKAGES` (mise à jour, §10). Rien d'autre. |
| Cleartext | Le module ne parle qu'en HTTP : `cleartextTrafficPermitted` nécessaire (IP dynamique → `base-config`). |
| Langue | Français (chaînes externalisées pour d'autres langues plus tard). |

## 4. Protocole du module (référence)

Tout est vérifié sur le poêle sauf mention contraire. Module : ESP (Espressif, OUI `EC:64:C9`), serveur `NetSoftware-httpd/0.3`, firmware `0.51`, modèle produit **8 = KTR-AIR BLDC**.

### 4.1 Découverte (UDP)

Ce que fait l'appli officielle :
- Envoie `###discover` en ASCII vers `<broadcast DHCP>:1025`, depuis une socket liée au port 1025.
- Attend **1 essai de 3 s**.
- Réponse attendue, ≤ 64 octets, jetons séparés par des espaces :
  `WiNET <ip> "<mac/nom>" [<label>]`
- Réponse observée : `WiNET 192.168.1.50 "WINET-0A1B2C3D"` (pas de label).
- Le module répond en unicast à l'expéditeur, au broadcast dirigé comme à `255.255.255.255` (vérifié depuis le PC).

Ce que la mini-app doit faire en plus (c'est le cœur de la v1) :
1. Socket sur un **port éphémère**, pas 1025 : évite le conflit de port.
2. Envoyer vers le broadcast dirigé calculé avec le vrai masque (`LinkProperties`, pas `DhcpInfo`, souvent faux), **et** vers `255.255.255.255`.
3. **3 essais × 2 s**.
4. En parallèle, unicast vers la **dernière IP connue**.
5. Si rien : **balayage unicast** du /24 (254 paquets `###discover` étalés sur ~2 s). Ça contourne l'isolation du broadcast sur les mesh/box.
6. Secours ultime : saisie manuelle de l'IP, validée par `key=019`.
7. Mémoriser IP + identifiant (`WINET-…`) ; si l'IP change mais pas l'identifiant, mise à jour silencieuse.
8. Vérifier que le téléphone est sur le Wi-Fi (`NetworkCapabilities.TRANSPORT_WIFI`) et lier les sockets à ce réseau : évite le passage par les données mobiles ou un VPN.

### 4.2 HTTP

- Requêtes `POST`, corps `application/x-www-form-urlencoded`, réponses JSON.
- Pas d'authentification pour la lecture.

| Endpoint | Paramètres | Rôle | Vérifié |
|---|---|---|---|
| `/ajax/get-registers` | `key=019` | Config : `model`, `fwUpdate`, `localWeb` | ✅ |
| `/ajax/get-registers` | `key=020&category=N` | Valeurs (voir §4.3) + méta : `signal`, `flame`, `alr`, `name`, `inetTime`, `authLevel`, `chrono` | ✅ |
| `/ajax/get-registers` | `key=022&status=1\|0` | Marche / arrêt | ✅ arrêt (8 → 0 en < 5 s) · ✅ marche (0 → 1 attente ~2 min → 2 allumage ~6 min → 4 flamme ~10 min → 5 travail) |
| `/ajax/get-registers` | `key=033` | Lire les 6 programmes chrono | ✅ |
| `/ajax/get-registers` | `key=032&…` | Enregistrer les programmes (§4.5) | ✅ ajout, modif, désactivation |
| `/ajax/get-registers` | `key=034&index=i` | Supprimer le programme i | ✅ |
| `/ajax/get-registers` | `key=035` | Courbe : `airTemp[]` (÷2 °C), `setAirTemp[]` | ✅ |
| `/ajax/set-register` | `key=002&memory=1&regId=R&value=V` | Écrire un registre → `{"result":true}` | ✅ (r187) |
| `/ajax/get-status` | — | Module : firmware, Wi-Fi, réseau, cloud (détail §4.6) | ✅ |
| `/ajax/login` / `logout` | `pin=…` | Accès technicien (`authLevel`) | ❓ PIN inconnu |

Catégories utiles : **2** (temps réel), **4** (horloge), **6** (canalisation/ventilation), **0** (r300/r301). Les catégories 13 à 255 renvoient le même contenu que la 2 ; aucune autre catégorie au niveau 0.

### 4.3 Registres (modèle 8)

| Reg | Sens | Encodage | Écriture | Statut |
|---|---|---|---|---|
| 2 | État | 0 Éteint · 1 Attente redémarrage · 2 Allumage · 3 Attente flamme · 4 Présence flamme · 5 Travail · 6 Nettoyage brasier · 7 Nettoyage final · 8 Nettoyage final Eco stop · 9 Eco stop · 10 Alarme · 11 Mémoire alarmes | — | ✅ 0, 1, 8 observés |
| 6 | Température ambiante (télécommande) | °C entier | — | ✅ probable (cohérent avec le confort climat) |
| 0 | Sonde interne | ×0,5 °C | — | ❓ vaut 0 |
| 3 | **Code d'alarme** (≠ 0 = alarme active ou à acquitter) | brut | — | 🔶 d'après [ha-ravelli-smart-wifi] ; impulsion 0→4→9→0 en 1 s observée pendant l'allumage |
| 5 | **Vitesse extracteur** | `brut × 10 + 250` tr/min, 0 = arrêté | — | 🔶 formule vérifiée par [ha-ravelli-smart-wifi] sur AIR-RDS ; 0 → 117 à l'allumage chez nous |
| 7, 10 | Températures (flamme ? autre ?) | °C ? | — | ❓ échelles à établir en chauffe |
| 8, 9, 37 | Inconnus (débit pellet ?) | | — | ❓ |
| 300, 301 | Inconnus (301−40 = 115 °C affichés « T » ?) | | — | ❓ |
| 50 | Consigne | 5–40 °C, **41 = MAN** | oui | ✅ lecture + écriture (télécommande à jour) |
| 51 | Puissance | 1–5 | oui | ✅ lecture + écriture (appliquée en ~1 s) |
| **187** | **Ventilation** (= télécommande) | 0 OFF · 1–5 · **6 AUTO** | oui | ✅ lecture + écriture, latence < 1 min |
| 59 | Jour semaine | 1 = lundi … 7 = dimanche | oui | ✅ lecture |
| 60, 61 | Heure, minutes | **BCD** | oui | ✅ lecture |
| 62, 63, 64 | Jour, mois, année | **BCD**, année + 2000 | oui | ✅ lecture |
| 175 | Type de canalisation | 0–4 (ici 3) | non | ✅ |
| 183–186, 188–193 | Autres réglages de canalisation | voir client web `REGS` | à éviter en v1 | ❓ masqués selon r175 |

### 4.4 Non publiés par le module (vérifié à la télécommande, aucun registre ne bouge)

Confort climat (on/off, delta de démarrage), retard d'extinction, type de pellet, offset de la sonde de température de la télécommande (4 → 4,3 sans effet visible ; r6 est en degrés entiers), amorçage de la vis, réarmement d'alarme. L'appli **n'affiche pas** ces réglages et renvoie vers la télécommande. Seul effet visible : les états 1/8/9 du confort climat.

### 4.5 Programmes chrono

- Lecture `033` : `programs[i] = [actif, startOn, hDébut, quartDébut, stopOn, hFin, quartFin, temp, puissance, joursBitmask, nom]`.
  - Slot vide = **nom `""`**, et c'est le seul critère fiable. Un slot supprimé garde des champs résiduels (observé : `[0,0,0,0,1,0,1,0,0,0,""]`).
- Lecture, enregistrement, désactivation et suppression ont été testés de bout en bout le 2026-11-21 depuis le client web.
- Jours : bit 0 = lundi … bit 6 = dimanche.
- Écriture `032` : `enabled` + pour chaque slot i non vide (n = i+1) :
  - `p0n1` = actif ;
  - `p0n2` = `startOn<<7 | h<<2 | quart` ;
  - `p0n3` = idem pour l'arrêt ;
  - `p0n4` = temp (7–40, 41 = MAN) ;
  - `p0n5` = puissance ;
  - `p0n6` = jours ;
  - `p0n7` = nom.
- Validation, reprise de l'interface d'origine plus nos ajouts :
  - nom non vide, ≤ 15 caractères, **ASCII uniquement, assaini à la saisie** (accents retirés : é → e ; autres caractères non ASCII supprimés). C'est un choix de prudence, même si d'autres implémentations envoient des accents ;
  - un programme actif a un démarrage ou un arrêt ;
  - arrêt > démarrage, sans passage par minuit ;
  - ≥ 1 jour ;
  - minutes au quart d'heure inférieur ;
  - avertissement non bloquant en cas de chevauchement.

### 4.6 Module Wi-Fi (`/ajax/get-status`, lecture seule)

Exemple réel (2026-11-21) : `{"status":5,"fwUpdate":false,"apConnected":0,"lastDisconnectReason":201,"lastCloudError":0,"currentApIp":"192.168.10.1","currentIp":"192.168.1.50","currentMask":"255.255.255.0","currentGw":"192.168.1.1","inetTime":"2026-11-21 12:34","inetWeekDay":6,"client":2,"network":"<SSID>","signal":3,"rssi":-64,"useTSense":0,"board":[8,0,0,0],"eNowDevs":[],"show":0,"pairing":0,"timeout":0,"fwVer":"0.51","boot":2}`

| Champ | Affichage | Décodage (tables de `status.html`) |
|---|---|---|
| `fwVer` | Version du firmware | texte |
| `fwUpdate` | Mise à jour disponible | booléen. **Affichage seul** : l'OTA est hors périmètre |
| `board[0]` | Carte du poêle | 8 = KTR-AIR BLDC (même code que `model`) |
| `status` | État Wi-Fi | 0 inactif · 1 connexion en cours · 2 mot de passe incorrect · 3 point d'accès introuvable · 4 échec · **5 connecté** · 255 pas en mode station |
| `network` | SSID | texte |
| `rssi`, `signal` | Signal | dBm ; barres 0–4 |
| `currentIp`, `currentMask`, `currentGw` | IP, masque, passerelle | texte. Le masque sert aussi au broadcast dirigé (§4.1) |
| `currentApIp` | IP du point d'accès de configuration | texte (192.168.10.1) |
| `lastDisconnectReason` | Dernière déconnexion Wi-Fi | codes du SDK Wi-Fi Espressif : 0 aucune, 1–24 (802.11), 200 perte des balises, **201 AP introuvable**, 202 échec d'authentification, 203 échec d'association, 204 délai de négociation |
| `client` | Connexion cloud | 0 inactif · 1 pause · **2 connecté** · 3 non connecté |
| `lastCloudError` | Dernière erreur cloud | brut (table inconnue) |
| `inetTime`, `inetWeekDay` | Heure Internet du module | texte ; jour 1 = lundi. Utile pour comparer à l'horloge du poêle |
| `useTSense`, `eNowDevs` | Sondes sans fil | activées / nombre appairé |
| `apConnected`, `boot`, `show`, `pairing`, `timeout` | Bruts, dans le diagnostic seulement | ❓ sens incertain |

Plus deux infos issues de la découverte (§4.1) : l'**identifiant** (`WINET-0A1B2C3D`) et la **méthode** qui a trouvé le module (broadcast dirigé, broadcast global, IP mémorisée, balayage, saisie manuelle).

- Polling : à l'ouverture de l'écran, puis toutes les 15 s tant qu'il est affiché. Jamais en arrière-plan.
- Confidentialité : le SSID et les IP restent dans l'appli. L'export du journal (écran Diagnostic) propose de les masquer.

### 4.7 Règles de sécurité
- Reprise de l'interface d'origine : refuser l'extinction si l'état ≠ 0 et `flame == 0` (phase d'allumage).
  - Sur KTR-AIR BLDC, `flame` vaut 1 dans toutes les phases, poêle éteint compris (constaté chez nous et par [ha-ravelli-smart-wifi]). Cette règle ne protège donc rien sur ce modèle : ne pas s'y fier.
- Allumage proposé **uniquement depuis l'état 0**. Tout autre code, inconnu compris, compte comme « en marche ». Le code 7 (nettoyage final) peut redémarrer de lui-même.
- Refuser l'allumage si r3 ≠ 0, ou si r3 n'a pas encore été lu (alarme possible).
- Consigne : envoi différé de ~0,7 s après le dernier appui. Afficher la valeur en attente, puis la valeur confirmée par relecture.

## 5. Écrans

1. **Connexion** : recherche en cours / trouvé / échec, avec bouton « Réessayer », saisie d'IP et aide (même Wi-Fi, VPN coupé).
2. **Accueil** : état (gros), alarme (rouge), température ambiante, consigne −/+, puissance −/+, ventilation −/+ (OFF…AUTO), bouton marche/arrêt avec confirmation.
3. **Programmes** : liste des 6 slots, édition en feuille, interrupteur global, bouton Enregistrer actif seulement si modifié et valide.
4. **Horloge** : heure du poêle, écart, « Synchroniser ».
5. **Module & réseau** : identifiant et IP du module, méthode de découverte, firmware et mise à jour disponible, carte, état Wi-Fi, SSID, signal (dBm + barres), IP/masque/passerelle, point d'accès de configuration, dernière déconnexion, cloud, heure Internet, sondes TSense (§4.6). Côté téléphone : IP et masque du téléphone, réseau utilisé (Wi-Fi / VPN / données). Le **SSID du téléphone** exige la permission de localisation (Android 8+) : affiché seulement si l'utilisateur l'accorde depuis cet écran, sinon on compare le sous-réseau du téléphone à celui du module. Ces infos servent à diagnostiquer un échec de découverte.
6. **Diagnostic** : registres bruts avec surbrillance des changements, champs bruts de `get-status`, journal des dernières requêtes et erreurs, export texte du journal (SSID/IP masquables).

## 6. Architecture

- `StoveClient` : file d'attente coroutine (un seul appel en vol), `Mutex`, timeout, compteur de requêtes/s.
- `Discovery` : stratégie §4.1, renvoie `Flow<DiscoveryEvent>`.
- `StoveRepository` : `StateFlow<StoveState>` alimenté par le polling (cat. 2 + 6 chaque seconde, cat. 4 + chrono toutes les 30 s).
- `Registers.kt` : table §4.3 (nom, bornes, libellés, encodage), seule source de vérité.
- UI Compose + ViewModels ; DataStore pour l'IP et l'identifiant mémorisés.
- Tests unitaires : BCD, encodage chrono, validation chrono, parsing de la réponse de découverte (avec et sans label), file d'attente.
- Test d'intégration contre un **faux module** : rejouer les réponses JSON capturées dans `monitor.jsonl`.

## 7. Critères d'acceptation v1
- Le module est trouvé en < 10 s sur le réseau de la maison, là où l'appli officielle échoue. Sinon, l'appli indique précisément quelle étape a échoué.
- Les valeurs de l'accueil correspondent à la télécommande (état, consigne, puissance, ventilation).
- Une modification de ventilation depuis l'appli apparaît sur la télécommande, et inversement, en < 1 min.
- Aucune requête au-delà de 2/s mesurée sur 10 min ; en arrière-plan, uniquement le contrôle périodique (§12).
- Aucune écriture sans action utilisateur (vérifiable dans le journal du diagnostic).

## 10. Mise à jour automatique

Releases publiées sur un dépôt GitHub **public**, configuré par `poele.updateRepo` dans `gradle.properties`. Procédure : `android/RELEASING.md`.

- Au démarrage, l'appli lit `releases/latest`, puis son asset `update.json` (`tag`, `versionName`, `versionCode`, `apk`, `sha256`).
- Si le `versionCode` est plus grand que le sien, un bandeau apparaît sur l'accueil. L'APK est alors téléchargé en HTTPS uniquement, son SHA-256 est vérifié, et l'installeur Android demande confirmation.
- Android garantit en plus que la mise à jour est signée par la même clé (empreinte dans `RELEASING.md`).
- Les builds de dev (`dev.ppp0.poele.debug`) ne proposent jamais de mise à jour.
- L'écran Module affiche la version installée et l'état du dépôt, avec un bouton « Vérifier maintenant ».

## 11. Arrêt programmé (expérimental, v1.2)

« Éteindre dans X » au moyen d'un programme chrono temporaire.

- **Nom** : `AUTO-OFF jj/mm`, date de l'échéance sur l'horloge du poêle (14 caractères ASCII). Visible tel quel sur la télécommande.
- **Emplacement** : celui d'une minuterie existante (nom commençant par `AUTO-OFF`, ancien nom sans date compris), sinon le premier libre. Refus si les 6 emplacements sont pris par d'autres programmes.
- **Programme** : arrêt seul (pas de démarrage), à l'échéance, sur le seul jour de l'échéance. Puissance : celle du moment ; température : 20 °C (sans effet sur un arrêt).
- **Échéance** : comptée sur l'**horloge du poêle**, qui exécute l'arrêt : heure du poêle + X, arrondie au quart d'heure supérieur. X va de 15 min à 23 h 45. Le téléphone mémorise l'échéance équivalente sur son horloge, en tenant compte de l'écart avec le poêle.
- **Refus** si :
  - le poêle est éteint ;
  - le chrono global est désactivé (l'activer réveillerait les autres programmes) ;
  - l'échéance tombe dans un programme **à venir** (pas encore commencé), sur son intervalle [démarrage, arrêt[ (sans arrêt : jusqu'à minuit). Tomber dans le programme **en cours** est permis : on écourte la chauffe programmée ;
  - l'horloge du poêle n'a pas encore été lue.
- **Fin** : dès que l'échéance est passée de 2 min, le programme est **désactivé** (nom et emplacement gardés pour la prochaine fois). C'est fait au premier contrôle, appli ouverte ou surveillance en arrière-plan ; sinon il se répéterait chaque semaine. Sans échéance mémorisée (réinstallation, autre téléphone), la surveillance désactive tout `AUTO-OFF` actif dont la date du nom est passée. « Annuler » le désactive tout de suite.

## 12. Surveillance en arrière-plan (v1.2)

- Tâche WorkManager **toutes les 15 min**, le minimum d'Android, sans notification permanente. Android choisit le moment exact et peut espacer les contrôles en veille. Désactivable dans Appareil ; activée par défaut.
- À chaque contrôle, uniquement sur le Wi-Fi, avec l'IP mémorisée :
  - lecture de l'état (catégorie 2) ;
  - fin de l'arrêt programmé échu (§11), avec une notification « Minuterie terminée » ;
  - notification « Alarme du poêle » quand une alarme **apparaît** (`alr`, r3 ≠ 0, états 10/11), une seule fois par alarme.
- Permission `POST_NOTIFICATIONS`, demandée au lancement.

## 8. Références

- **[ha-ravelli-smart-wifi]** : https://github.com/Greite/ha-ravelli-smart-wifi, intégration Home Assistant, licence MIT, version 2026.11.1 lue le 2026-11-21.
  - Même module, KTR-AIR BLDC vérifié par un utilisateur (firmware 0.51).
  - Source des lignes 🔶 ci-dessus. Elle confirme aussi : r6 = ambiante (°C entiers), r187 = ventilation en canalisation type 3, codes d'état KTR, confort climat (r73/r74) absent sur ce modèle.
  - Sa détection est un balayage HTTP du sous-réseau. **Nous gardons le broadcast UDP** (§4.1), méthode native du module et de l'appli officielle.
- Légende : ✅ vérifié sur notre poêle · 🔶 vérifié par un tiers, pas encore chez nous · ⚠️ non testé · ❓ inconnu.

## 9. Questions ouvertes
1. Pourquoi la découverte échoue chez toi : filtrage du broadcast par le Wi-Fi, VPN, autre SSID ? À diagnostiquer avec adb avant de figer §4.1.
2. ~~Écritures à valider~~ : toutes les écritures de la v1 sont validées le 2026-11-21 (consigne, puissance, ventilation, marche, arrêt, programmes).
3. Registres inconnus (3, 5, 7–10, 37, 300, 301) : séance d'observation poêle en chauffe avec relevés de la télécommande.
4. Accès technicien : PIN disponible ? Il pourrait débloquer d'autres catégories.
5. Distribution : APK installé à la main (adb / fichier), pas de Play Store ?
6. Widget, notification d'alarme : v1 ou v2 ?
