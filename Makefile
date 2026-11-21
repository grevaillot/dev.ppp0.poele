# Poêle : build et publication des releases. Voir RELEASING.md.

PACKAGE    := dev.ppp0.poele
GRADLE     := ./gradlew --console=plain
SDK        := $(or $(ANDROID_HOME),$(shell sed -n 's/^sdk.dir=//p' local.properties 2>/dev/null))
APKSIGNER  := $(lastword $(sort $(wildcard $(SDK)/build-tools/*/apksigner)))
AAPT2      := $(lastword $(sort $(wildcard $(SDK)/build-tools/*/aapt2)))
ADB        := $(SDK)/platform-tools/adb

SIGNING_DIR   := $(HOME)/.config/poele
SIGNING_PROPS := $(or $(POELE_RELEASE_SIGNING),$(SIGNING_DIR)/poele-release.properties)
KEYSTORE      := $(SIGNING_DIR)/poele-release.jks

APP_VER := $(shell sed -n 's/VERSION_MAJOR *= *//p;s/VERSION_MINOR *= *//p;s/PATCHLEVEL *= *//p' VERSION | paste -sd.)
TAG     := v$(APP_VER)
# Dépôt GitHub public lu par l'appli pour se mettre à jour (gradle.properties : poele.updateRepo).
UPDATE_REPO := $(shell sed -n 's/^poele.updateRepo=//p' gradle.properties | tr -d ' ')

RELEASE_DIR := release
REL_NAME    := poele-$(TAG)
REL_PATH    := $(RELEASE_DIR)/$(REL_NAME)
RELEASE_APK := app/build/outputs/apk/release/app-release.apk

.PHONY: help debug test install install-release keystore release release-github clean

help:
	@echo "Version $(APP_VER), tag $(TAG), dépôt de mise à jour : $(or $(UPDATE_REPO),(non configuré))"
	@echo "make debug            APK de dev ($(PACKAGE).debug)"
	@echo "make test             tests unitaires"
	@echo "make install          installe l'APK de dev sur le téléphone branché"
	@echo "make keystore         crée la clé de signature des releases (une seule fois, jamais écrasée)"
	@echo "make release          tests, build, signature, vérification, $(REL_PATH)/"
	@echo "make install-release  installe l'APK de release sur le téléphone branché"
	@echo "make release-github   publie $(TAG) sur $(or $(UPDATE_REPO),?) : LIVRE LA MISE À JOUR À TOUS LES TÉLÉPHONES"

debug:
	$(GRADLE) assembleDebug

test:
	$(GRADLE) testDebugUnitTest

install: debug
	$(ADB) install -r app/build/outputs/apk/debug/app-debug.apk

install-release:
	$(ADB) install -r $(REL_PATH)/$(REL_NAME).apk

# La clé signe toutes les mises à jour : la perdre oblige à désinstaller l'appli pour mettre à jour.
# Sauvegarder le .jks ET le .properties (il contient le mot de passe) hors de cette machine.
keystore:
	@test ! -e $(KEYSTORE) || { echo "$(KEYSTORE) existe déjà, rien n'est écrasé"; exit 1; }
	@mkdir -p -m 700 $(SIGNING_DIR)
	@umask 077; \
	export POELE_KEY_PASSWORD=$$(openssl rand -base64 33 | tr -d '/+='); \
	keytool -genkeypair -storetype PKCS12 -keystore $(KEYSTORE) -alias poele \
		-keyalg RSA -keysize 4096 -validity 10950 \
		-dname "CN=Poele Android" \
		-storepass:env POELE_KEY_PASSWORD -keypass:env POELE_KEY_PASSWORD >/dev/null && \
	printf 'storeFile=%s\nstorePassword=%s\nkeyAlias=poele\nkeyPassword=%s\n' \
		"$(KEYSTORE)" "$$POELE_KEY_PASSWORD" "$$POELE_KEY_PASSWORD" > $(SIGNING_PROPS) && \
	keytool -J-Duser.language=en -list -v -keystore $(KEYSTORE) -storepass:env POELE_KEY_PASSWORD | grep -m1 'SHA256:'
	@echo "Clé créée : $(KEYSTORE)"
	@echo "Mot de passe : $(SIGNING_PROPS) (à sauvegarder avec la clé, hors de cette machine)"

release:
	@test -f "$(SIGNING_PROPS)" || { echo "ERREUR : pas de clé $(SIGNING_PROPS) (make keystore)"; exit 1; }
	@test -n "$(APKSIGNER)" || { echo "ERREUR : apksigner introuvable dans $(SDK)/build-tools"; exit 1; }
	@grep -q '^## $(TAG)\b' CHANGELOG.md || { echo "ERREUR : CHANGELOG.md n'a pas de section '## $(TAG)'"; exit 1; }
	$(GRADLE) clean testDebugUnitTest assembleRelease
	rm -rf $(REL_PATH) && mkdir -p $(REL_PATH)
	cp $(RELEASE_APK) $(REL_PATH)/$(REL_NAME).apk
	$(APKSIGNER) verify --print-certs $(REL_PATH)/$(REL_NAME).apk > $(REL_PATH)/$(REL_NAME).cert.txt
	@grep -q 'CN=Poele Android' $(REL_PATH)/$(REL_NAME).cert.txt || { echo "ERREUR : APK non signé par la clé de release"; exit 1; }
	cd $(REL_PATH) && sha256sum $(REL_NAME).apk > $(REL_NAME).apk.sha256
	python3 -I scripts/update_json.py $(AAPT2) $(REL_PATH)/$(REL_NAME).apk $(TAG) $(PACKAGE) > $(REL_PATH)/update.json
	python3 -I scripts/release_notes.py $(TAG) CHANGELOG.md $(REL_PATH)/$(REL_NAME).apk.sha256 $(REL_PATH)/$(REL_NAME).cert.txt \
		> $(REL_PATH)/$(REL_NAME).md
	@echo "=== $(REL_PATH)/ prêt : essaie-le avec 'make install-release' avant 'make release-github' ==="

# Publier, c'est livrer la mise à jour à tous les téléphones : seulement après l'essai de install-release.
release-github:
	@test -n "$(UPDATE_REPO)" || { echo "ERREUR : poele.updateRepo vide dans gradle.properties"; exit 1; }
	@test -f $(REL_PATH)/update.json || { echo "ERREUR : pas de $(REL_PATH)/update.json, lance 'make release'"; exit 1; }
	@printf "Publier $(TAG) sur $(UPDATE_REPO) ? Tous les téléphones la recevront. [o/N] "; \
		read ans; case "$$ans" in [oOyY]*) ;; *) echo "Abandon."; exit 1;; esac
	gh release create $(TAG) -R $(UPDATE_REPO) --title "Poêle $(APP_VER)" \
		--notes-file $(REL_PATH)/$(REL_NAME).md \
		$(REL_PATH)/$(REL_NAME).apk $(REL_PATH)/update.json

clean:
	$(GRADLE) clean
	rm -rf $(RELEASE_DIR)
