#!/usr/bin/env python3
"""Süzgeç pre-build validation: resources, references, manifest, versions."""
import os
import re
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
APP = os.path.join(ROOT, "app", "src", "main")
RES = os.path.join(APP, "res")

errors = []
warnings = []


def err(msg):
    errors.append(msg)


def warn(msg):
    warnings.append(msg)


ANDROID = "{http://schemas.android.com/apk/res/android}"


def tag(el):
    return el.tag.split("}")[-1]


def android_attr(el, name):
    return el.get(ANDROID + name)


def walk_res():
    for base, _, files in os.walk(RES):
        for name in files:
            if name.endswith(".xml"):
                yield os.path.join(base, name)


def parse_xml(path):
    try:
        return ET.parse(path).getroot()
    except Exception as exc:
        err("XML hatası: %s -> %s" % (os.path.relpath(path, ROOT), exc))
        return None


def collect_definitions():
    strings, colors, styles, layouts, drawables, menus, ids = (set() for _ in range(7))
    for path in walk_res():
        root = parse_xml(path)
        if root is None:
            continue
        rel = os.path.relpath(path, RES)
        folder = rel.split(os.sep)[0]
        base = os.path.splitext(os.path.basename(path))[0]

        if folder.startswith("layout"):
            layouts.add(base)
        elif folder.startswith("menu"):
            menus.add(base)
        elif folder.startswith("drawable"):
            drawables.add(base)
        elif folder.startswith("color"):
            if tag(root) == "selector":
                colors.add(base)
        elif folder.startswith("xml"):
            pass

        for el in root.iter():
            t = tag(el)
            if t == "string":
                name = el.get("name")
                if name:
                    strings.add(name)
            elif t == "color":
                name = el.get("name")
                if name:
                    colors.add(name)
            elif t == "style":
                name = el.get("name")
                if name:
                    styles.add(name)
            elif android_attr(el, "id"):
                ident = android_attr(el, "id").split("/")[-1]
                ids.add(ident)

    for path in walk_res():
        if not os.path.relpath(path, RES).startswith("layout"):
            continue
        root = parse_xml(path)
        if root is None:
            continue
        for el in root.iter():
            if android_attr(el, "id"):
                ids.add(android_attr(el, "id").split("/")[-1])

    return strings, colors, styles, layouts, drawables, menus, ids


def check_duplicates():
    for name in ("values", "values-night"):
        path = os.path.join(RES, name)
        if not os.path.isdir(path):
            continue
        for fname in sorted(os.listdir(path)):
            if not fname.endswith(".xml"):
                continue
            root = parse_xml(os.path.join(path, fname))
            if root is None:
                continue
            seen = {}
            for el in root:
                key = el.get("name")
                if key is None:
                    continue
                ident = (tag(el), key)
                if ident in seen:
                    err("Yinelenen kaynak: %s/%s içinde %s %s" % (name, fname, tag(el), key))
                seen[ident] = True


def check_references(strings, colors, styles, layouts, drawables, menus, ids):
    pattern = re.compile(r"@(string|color|style|layout|drawable|menu|id)/([A-Za-z0-9_.]+)")
    for path in walk_res():
        root = parse_xml(path)
        if root is None:
            continue
        rel = os.path.relpath(path, ROOT)
        for el in root.iter():
            for attr, value in el.attrib.items():
                if not value.startswith("@"):
                    continue
                for kind, name in pattern.findall(value):
                    name = name.split(":")[0]
                    if name.startswith("android:"):
                        continue
                    pool = {
                        "string": strings,
                        "color": colors,
                        "style": styles,
                        "layout": layouts,
                        "drawable": drawables,
                        "menu": menus,
                        "id": ids,
                    }[kind]
                    if name not in pool and not name.startswith("android"):
                        err("Çözümlenemeyen kaynak: %s -> @%s/%s" % (rel, kind, name))

    manifest = os.path.join(APP, "AndroidManifest.xml")
    root = parse_xml(manifest)
    if root is not None:
        for el in root.iter():
            for attr, value in el.attrib.items():
                for kind, name in pattern.findall(value):
                    name = name.split(":")[0]
                    pool = {
                        "string": strings,
                        "color": colors,
                        "style": styles,
                        "layout": layouts,
                        "drawable": drawables,
                        "menu": menus,
                        "id": ids,
                    }[kind]
                    if name not in pool and not name.startswith("android"):
                        err("Manifestte çözümlenemeyen kaynak: @%s/%s" % (kind, name))


def check_r_refs(strings, colors, styles, layouts, drawables, menus, ids):
    pool = {
        "string": strings,
        "color": colors,
        "style": styles,
        "layout": layouts,
        "drawable": drawables,
        "menu": menus,
        "id": ids,
    }
    pattern = re.compile(r"\bR\.(string|color|style|layout|drawable|menu|id)\.([A-Za-z0-9_]+)")
    for base, _, files in os.walk(os.path.join(APP, "java")):
        for fname in files:
            if not fname.endswith(".kt"):
                continue
            path = os.path.join(base, fname)
            with open(path, encoding="utf-8") as fh:
                text = fh.read()
            for kind, name in pattern.findall(text):
                if name not in pool[kind]:
                    err("Eksik R referansı: %s -> R.%s.%s" % (
                        os.path.relpath(path, ROOT), kind, name))


def check_manifest(strings):
    path = os.path.join(APP, "AndroidManifest.xml")
    root = parse_xml(path)
    if root is None:
        return
    raw = open(path, encoding="utf-8").read()

    required_permissions = [
        "android.permission.INTERNET",
        "android.permission.FOREGROUND_SERVICE",
        "android.permission.FOREGROUND_SERVICE_SPECIAL_USE",
        "android.permission.POST_NOTIFICATIONS",
        "android.permission.ACCESS_NETWORK_STATE",
    ]
    for perm in required_permissions:
        if perm not in raw:
            err("Manifestte izin yok: %s" % perm)

    if '<uses-permission android:name="android.permission.BIND_VPN_SERVICE"' in raw:
        warn("BIND_VPN_SERVICE uses-permission olarak istenmemeli; servis özniteliği yeterli")

    app = root.find("application")
    if app is None:
        err("Manifestte <application> yok")
        return
    if android_attr(app, "allowBackup") != "false":
        err("allowBackup=false olmalı")
    if android_attr(app, "usesCleartextTraffic") != "false":
        err("usesCleartextTraffic=false olmalı")

    service_found = False
    vpn_found = False
    activity_found = False
    for el in app:
        t = tag(el)
        if t == "service" and android_attr(el, "name") == ".FilterService":
            service_found = True
            fgs = android_attr(el, "foregroundServiceType")
            if fgs != "specialUse":
                err("FilterService foregroundServiceType=specialUse olmalı")
            if "PROPERTY_SPECIAL_USE_FGS_SUBTYPE" not in ET.tostring(el, encoding="unicode"):
                err("specialUse alt tip açıklaması eksik")
        if t == "service" and android_attr(el, "name") == ".SuzgecVpnService":
            vpn_found = True
            if android_attr(el, "permission") != "android.permission.BIND_VPN_SERVICE":
                err("SuzgecVpnService BIND_VPN_SERVICE izniyle korunmalı")
            fgs = android_attr(el, "foregroundServiceType")
            if fgs != "specialUse":
                err("SuzgecVpnService foregroundServiceType=specialUse olmalı")
            body = ET.tostring(el, encoding="unicode")
            if "android.net.VpnService" not in body:
                err("SuzgecVpnService android.net.VpnService intent-filter içermeli")
            if "PROPERTY_SPECIAL_USE_FGS_SUBTYPE" not in body:
                err("SuzgecVpnService specialUse alt tip açıklaması eksik")
        if t == "activity" and android_attr(el, "name") == ".MainActivity":
            activity_found = True
    if not service_found:
        err("Manifestte FilterService yok")
    if not vpn_found:
        err("Manifestte SuzgecVpnService yok")
    if not activity_found:
        err("Manifestte MainActivity yok")


def check_gradle():
    path = os.path.join(ROOT, "app", "build.gradle.kts")
    if not os.path.isfile(path):
        err("app/build.gradle.kts yok")
        return
    text = open(path, encoding="utf-8").read()
    for required in ("versionCode", "versionName", "compileSdk = 34", "minSdk = 24", "targetSdk = 34"):
        if required not in text:
            err("build.gradle.kts içinde yok: %s" % required)
    if "signingConfigs" not in text:
        err("release imza yapılandırması yok")
    if "okhttp" not in text:
        err("OkHttp bağımlılığı yok")
    m = re.search(r'versionName\s*=\s*"([^"]+)"', text)
    if m and not re.match(r"^\d+\.\d+(\.\d+)?$", m.group(1)):
        warn("versionName biçimi beklenmedik: %s" % m.group(1))


def check_sources():
    required = [
        "MainActivity.kt",
        "FilterService.kt",
        "SuzgecVpnService.kt",
        "FilterStore.kt",
        "Store.kt",
        "Stats.kt",
        "VpnStatus.kt",
        "Protection.kt",
        "BootReceiver.kt",
        "core/DnsServer.kt",
        "core/DnsWire.kt",
        "core/DnsEngine.kt",
        "core/IpPacket.kt",
        "core/RuleSet.kt",
        "core/Upstream.kt",
    ]
    base = os.path.join(APP, "java", "com", "wendigo", "suzgec")
    for rel in required:
        if not os.path.isfile(os.path.join(base, rel)):
            err("Kaynak dosya yok: %s" % rel)
    tests = os.path.join(ROOT, "app", "src", "test", "java", "com", "wendigo", "suzgec", "core")
    for rel in ("DnsWireTest.kt", "RuleSetTest.kt", "DnsEngineTest.kt", "IpPacketTest.kt"):
        if not os.path.isfile(os.path.join(tests, rel)):
            err("Test dosyası yok: %s" % rel)


def check_assets():
    path = os.path.join(APP, "assets", "filter_core.txt")
    if not os.path.isfile(path):
        err("assets/filter_core.txt yok")
        return
    with open(path, encoding="utf-8") as fh:
        lines = [ln.strip() for ln in fh if ln.strip()]
    rules = [ln for ln in lines if not ln.startswith("!")]
    if len(rules) < 300:
        err("Yerleşik listede en az 300 kural olmalı (şu an %d)" % len(rules))
    for rule in rules[:400]:
        if not rule.startswith("||") or not rule.endswith("^"):
            err("Beklenmeyen liste satırı: %s" % rule)
            break


def check_workflow():
    path = os.path.join(ROOT, ".github", "workflows", "build.yml")
    if not os.path.isfile(path):
        err(".github/workflows/build.yml yok")
        return
    text = open(path, encoding="utf-8").read()
    if "testDebugUnitTest" not in text:
        err("CI test adımı içermiyor")
    if "assembleRelease" not in text:
        err("CI release derlemiyor")
    if "Suzgec" not in text:
        err("CI artifact adı 'Suzgec' değil")


def main():
    strings, colors, styles, layouts, drawables, menus, ids = collect_definitions()
    check_duplicates()
    check_references(strings, colors, styles, layouts, drawables, menus, ids)
    check_r_refs(strings, colors, styles, layouts, drawables, menus, ids)
    check_manifest(strings)
    check_gradle()
    check_sources()
    check_assets()
    check_workflow()

    for msg in warnings:
        print("UYARI: %s" % msg)
    if errors:
        print("\n%d HATA:" % len(errors))
        for msg in errors:
            print("  - %s" % msg)
        return 1
    print("Doğrulama başarılı: %d string, %d renk, %d stil, %d layout, %d drawable" % (
        len(strings), len(colors), len(styles), len(layouts), len(drawables)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
