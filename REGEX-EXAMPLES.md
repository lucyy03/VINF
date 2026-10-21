# REGEX extrakcia uloženého MusicBrainz HTML

Extrakcia beží lokálne, bez HTTP požiadaviek a bez čítania vloženého JSON.
Pred spracovaním sa odstránia komentáre, skripty a štýly. Nadpis musí
identifikovať entitu zo zdrojovej URL; overovacie a neúplné stránky sa odmietnu.

## Skladby a dĺžky

Na stránke konkrétneho vydania sa spracujú riadky `<tr>`. Bunka `td.pos`
obsahuje odkaz `/track/UUID`, bunka `td.title` odkaz `/recording/UUID` a názov.
Dĺžka sa číta z `td.treleases`, prípadne `td.length`. Trieda sa hľadá ako celý
token, takže sa podporuje aj `class="title wrap-anywhere"`.

Zjednodušený vzor riadku: `(?is)<tr\b[^>]*>(.*?)</tr\s*>`.
UUID: `[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}`.
Vzor podporovanej dĺžky: `\d+:[0-5]\d(?:\.\d{1,3})?`.

Overený príklad z `2026-10-10_23-08-15_7424884074887970765/html/0009.html`:

| Pozícia | Skladba | Dĺžka | Milisekundy |
| --- | --- | --- | --- |
| A1 | Airbag | 4:44 | 284000 |
| A2 | Paranoid Android | zachovaná z HTML | prepočítaná z HTML |

Táto edícia má 12 extrahovaných skladieb. Track ID identifikuje výskyt skladby
na vydaní; recording ID identifikuje nahrávku. Názvy vzťahov a mená producentov
vnorené v bunke názvu sa nepridávajú do názvu skladby.

## Vydavateľstvo

Extrakcia je obmedzená na sekciu `h2.labels` v bočnom paneli a jej zoznam `ul`.
Z odkazu `/label/UUID` sa získa ID a názov; zo `span.catalog-number` katalógové číslo.
Rovnaká kazetová edícia: **Parlophone**, **TCNODATA 02**.

## Hodnotenie a tagy

Hodnota `span.current-rating` sa číta výhradne z bočného panela, nie z riadkov
skladieb. Táto edícia zobrazuje hodnotenie svojej release group **4.55**;
prvá skladba má vlastné hodnotenie **3.85**, ktoré sa ako hodnotenie albumu nepoužije.
Výstup preto obsahuje `rating_scope=release-group`.

Ďalšie tagy sa čítajú z odkazov `/tag/…` v `div#sidebar-tag-list`.
Release group OK Computer má medzi viditeľnými tagmi napríklad `atmospheric`.
Žánre zostávajú v pôvodnom stĺpci `genres`.

## Dátum a obmedzenia

`earliest_listed_release_date` zachováva najstaršiu viditeľnú hodnotu
`span.release-date` v HTML release group. Pri OK Computer obsahuje zoznam aj
hodnotu `1997` bez mesiaca a dňa; extraktor k nej nevymýšľa presnejší dátum.
Výsledok nie je potvrdeným prvým vydaním celej release group. Úplnosť zoznamu
vydaní ani správnosť dát zadaných používateľmi MusicBrainz sa týmto neoveruje.

REGEX je prispôsobený uloženému MusicBrainz DOM. Nie je univerzálny HTML parser;
pri zmene štruktúry stránky treba doplniť podporu aj regresné prípady.
`DetailsChecks` kontroluje pevné hodnoty vyššie a neplatné/chýbajúce dĺžky.
`RegexExtractorChecks` navyše kontroluje 20 reálnych stránok a 180 pevných hodnôt
pôvodných polí; nejde o 20-stránkový zlatý štandard pre všetky nové polia.
