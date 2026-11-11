# Mattekamp for iPhone

En liten iOS-app som viser `mattekamp.html` og bruker iOS sin egen norske talegjenkjenning (nb-NO).

## Få tak i .ipa
GitHub Actions-jobben «Bygg iOS-app (usignert .ipa)» bygger appen på en Mac-maskin hos GitHub.
Den kjører ved hver push som endrer `mattekamp.html` eller `ios/`, og kan også startes manuelt under **Actions**.
Last ned `Mattekamp-unsigned-ipa` fra jobben og signer `Mattekamp-unsigned.ipa` med det verktøyet du bruker
(Sideloadly, AltStore, ESign osv.).

## Bygge selv på Mac
```sh
brew install xcodegen
cd ios && xcodegen generate && open Mattekamp.xcodeproj
```

Første gang du trykker Start, spør appen om tilgang til mikrofon og talegjenkjenning.
