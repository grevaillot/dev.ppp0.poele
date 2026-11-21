# Changelog

## v1.2.2 — 2026-11-21

- Corrige l'erreur « Binding socket to network … failed » après une reconnexion Wi-Fi : l'appli reprend le réseau courant au lieu de rester hors ligne.
- Une erreur réseau pendant la recherche du module s'affiche au lieu d'interrompre la recherche.
- L'appli et la surveillance en arrière-plan n'interrogent plus jamais le module en même temps.
- Courbe : un point toutes les 15 min (≈ 18 h affichées, et non 24 h), repères aux heures réelles (06:00, 12:00, 18:00, date à minuit) ; ambiante et consigne tracées de la même façon (paliers centrés), sans décalage visuel.
- Arrêt programmé : le programme porte la date de son échéance (« AUTO-OFF 21/11 ») ; la surveillance désactive aussi un AUTO-OFF dont la date est passée, même sans l'échéance mémorisée (réinstallation, autre téléphone).

## v1.2.1 — 2026-11-21

- Arrêt programmé : permis s'il tombe dans le programme en cours (écourte la chauffe), refusé seulement s'il tombe dans un programme à venir.
- Arrêt programmé : durée réglée au −/+ seulement (raccourcis 30 min…4 h retirés), carte placée en bas de l'accueil.

## v1.2.0 — 2026-11-21

- Surveillance en arrière-plan toutes les 15 min : notification quand une alarme apparaît (désactivable dans Appareil).
- Arrêt programmé (expérimental) : « éteindre dans X » via un programme chrono temporaire AUTO-OFF, désactivé automatiquement une fois l'échéance passée. Refusé si l'échéance tombe dans un programme actif.

## v1.0.0 — 2026-11-21

Première version stable.

- Accueil : courbe ambiante / consigne des dernières 24 h.

## v0.2.0 — 2026-11-21

- Commandes : consigne, puissance, ventilation, marche/arrêt (allumage refusé en cas d'alarme).
- Programmes chrono : édition, ajout, suppression, interrupteur global.
- Horloge du poêle : affichage de l'écart et réglage sur l'heure du téléphone.
- Mise à jour automatique depuis les releases GitHub.
- Interface : thème clair simple, barre de titre avec l'état, 4 onglets, programmes compacts (jours actifs en vert), icône.

## v0.1.0 — 2026-11-21

- Découverte UDP du module WiNET (IP mémorisée, broadcast, balayage du /24, saisie manuelle).
- Accueil en lecture seule, écran Module & réseau, diagnostic.
