# StudentZK — Android Wallet App

Mobilna aplikacija za upravljanje studentskim digitalnim vjerodajnicama temeljenim na **SD-JWT-VC** standardu (Selective Disclosure JWT Verifiable Credentials). Omogućuje studentima pohranu kriptografski zaštićenih potvrda o statusu studenta te verifikaciju tuđih vjerodajnica putem QR kôdova ili ručnog unosa.

---

## Što aplikacija trenutno radi

### 🗂 Wallet (Novčanik)
- Prikazuje popis svih pohranjenih studentskih vjerodajnica kao kartice s gradijentom
- Svaka kartica prikazuje: student ID, sveučilišni ID, status studenta, datum izdavanja i rok valjanosti
- Dugme **+ Add Credential** otvara bottom sheet za dohvat nove vjerodajnice od issuer servera
- Unosi se student ID (npr. `0036123456`), aplikacija poziva backend i sprema rezultat
- Kartica se može otvoriti za detaljan pregled

### 🔍 Detalji vjerodajnice
- Pregled svih metapodataka: tip vjerodajnice, student ID, sveučilišni ID, datum izdavanja, status indeks
- Popis selektivno razotkrivenih atributa (SD-JWT disclosures)
- **QR kôd** — prikazuje SD-JWT kao QR koji drugi mogu skenirati za verifikaciju
- **Kopiraj** — kopira cijeli SD-JWT u međuspremnik (za dijeljenje bez kamere)
- **Raw SD-JWT-VC** — prikazuje sirovi JWT string (za debugging)
- **Brisanje** s potvrdnim dijalogom; wallet se automatski osvježava pri povratku

### 📷 Scan & Verify
Dva načina verifikacije:

**Kamera (QR skeniranje)**
- CameraX + ML Kit barcode scanner
- Skenira QR kôd druge osobe i odmah verificira vjerodajnicu
- Prikazuje rezultat u overlay panelu pri dnu ekrana:
  - ✅ `Verified Student` — s rokom valjanosti i sveučilištem
  - ❌ `Verification Failed` — s razlogom greške
  - 🚫 `Credential REVOKED` — s indeksom opoziva
  - ⚠ Upozorenje ako status lista nije dostupna (fail-soft)

**Paste mode (ručni unos)**
- Ikona u gornjem desnom kutu prebacuje u paste mode
- TextField za unos SD-JWT stringa
- Gumb **Paste** automatski čita iz međuspremnika
- Gumb **Verify** pokreće isti verificacijski proces kao i kamera
- Korisno kad kamera nije dostupna ili za testiranje

### ⚙️ Settings
- Konfiguracija URL-a backend servera (zadano: `http://10.0.2.2:8080` za Android emulator)
- **Check Health** — testira dostupnost servera
- Prikazuje trenutno konfigurirani URL

---

## Kako verificacija radi (tehničke pojedinosti)

1. **Parsiranje** — SD-JWT-VC se rastavlja na JWT + disclosures (`<jwt>~<disc1>~<disc2>~`)
2. **Hash verifikacija** — SHA-256 hash svakog disclosurea mora biti u `_sd` polju JWT payloada
3. **Ekstrakcija podataka** — iz disclosurea se čitaju `is_student` i `university_id`
4. **Provjera opoziva** — dohvaća se status lista s backend servera (IETF Token Status List), dekomprimira se (zlib), provjerava se bit na poziciji `statusIdx`
   - Ako server nije dostupan → prikazuje upozorenje ali prihvaća (fail-soft)

---

## Arhitektura

```
holder-android/StudentZK/
├── data/
│   ├── local/CredentialStore.kt       # EncryptedSharedPreferences pohrana
│   ├── model/StoredCredential.kt      # @Immutable data class vjerodajnice
│   ├── model/VerificationResult.kt    # Sealed class: Valid | Invalid | Revoked | Pending
│   └── network/IssuerApiClient.kt     # OkHttp klijent za backend API
├── domain/
│   └── CredentialRepository.kt        # Orchestrira storage + network + verifikaciju
├── navigation/
│   ├── Screen.kt                      # Route definicije
│   └── AppNavigation.kt               # NavHost + BottomNavBar
├── ui/
│   ├── wallet/                        # Wallet ekran + ViewModel
│   ├── detail/                        # Detalji vjerodajnice + ViewModel
│   ├── scan/                          # Kamera/paste verifikacija + ViewModel
│   ├── settings/                      # Postavke + ViewModel
│   └── theme/                         # Material3 boje i tema
└── util/
    ├── SdJwtUtils.kt                  # SD-JWT-VC parser i verifikator
    ├── QrCodeUtils.kt                 # ZXing QR generiranje
    └── HolderKeyManager.kt            # AndroidKeyStore ES256 ključevi
```

**Stack:** Kotlin · Jetpack Compose · Material3 · Navigation Compose · CameraX · ML Kit · OkHttp · ZXing · EncryptedSharedPreferences · AndroidKeyStore

---

## Pokretanje

### Preduvjeti
- Android Studio Ladybug ili noviji
- Android emulator ili uređaj s API 29+

### Backend
Aplikacija zahtijeva pokrenuti `issuer-backend` za izdavanje vjerodajnica i provjeru opoziva. Za pokretanje backenda:

```bash
cd issuer-backend
# U IntelliJ IDEA: postaviti SPRING_PROFILES_ACTIVE=local i pokrenuti bootRun
# ili iz terminala (nakon IntelliJ sync):
SPRING_PROFILES_ACTIVE=local ./gradlew bootRun
```

Backend mora biti dostupan na `http://localhost:8080`. Android emulator pristupa hostu na `10.0.2.2:8080` (zadana konfiguracija u aplikaciji).

### Build i instalacija
```bash
cd holder-android/StudentZK
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## Poznata ograničenja

- **StrongBox** — Emulatori nemaju StrongBox HSM; aplikacija automatski pada na TEE (Trusted Execution Environment)
- **Issuer verifikacija potpisa** — Trenutno se ne verificira potpis issuera (JWT signature); provjeravaju se samo disclosure hashevi i revokacija
- **OID4VCI flow** — Implementiran je samo dev shortcut (`POST /dev/credential/{studentId}`); puni OID4VCI pre-authorized code flow (sa `/credential-offer` → `/token` → `/credential`) nije završen u UI-u
- **Offline** — Bez interneta verifikacija radi ali ne može provjeriti status opoziva (prikazuje upozorenje)
