#!/usr/bin/env python3
"""Fail closed on Eclipse source identity and, optionally, release archives."""

import argparse
import io
import posixpath
import re
import stat
import sys
import tarfile
import zipfile
import xml.etree.ElementTree as ET
from pathlib import Path, PurePosixPath

ROOT = Path(__file__).resolve().parents[1]
NS = {"m": "http://maven.apache.org/POM/4.0.0"}
RELEASE_VERSION_PATTERN = re.compile(
    r"^(?P<base>[0-9]+\.[0-9]+\.[0-9]+)(?:-(?P<qualifier>SNAPSHOT|[A-Za-z0-9_]+))?$")


def release_identity():
    """Read the release identity updated by the version maintenance scripts."""
    try:
        pom = ET.parse(ROOT / "pom.xml").getroot()
        maven_version = pom.findtext("m:properties/m:revision", default="", namespaces=NS)
    except (OSError, ET.ParseError):
        return "", ""
    match = RELEASE_VERSION_PATTERN.fullmatch(maven_version)
    if match is None:
        return maven_version, ""
    qualifier = match.group("qualifier")
    if qualifier is None:
        return maven_version, match.group("base")
    if qualifier.lower() == "qualifier":
        return maven_version, ""
    osgi_qualifier = "qualifier" if qualifier == "SNAPSHOT" else qualifier
    return maven_version, f"{match.group('base')}.{osgi_qualifier}"


def core_pin():
    """Read the single Core pin that both workflows load into the environment."""
    values = {}
    try:
        content = CORE_PIN_FILE.read_text(encoding="utf-8")
    except OSError:
        return values
    for line in content.splitlines():
        stripped = line.strip()
        if stripped.startswith("#") or "=" not in stripped:
            continue
        key, value = stripped.split("=", 1)
        values[key.strip()] = value.strip()
    return values


MAVEN_VERSION, OSGI_VERSION = release_identity()
REACTOR_MAVEN_VERSION = MAVEN_VERSION if MAVEN_VERSION.endswith("-SNAPSHOT") else OSGI_VERSION
CORE_MAVEN_VERSION = "15.2.0-neo1"
CORE_OSGI_VERSION = "15.2.0.neo1"
ECLIPSE_REPO = "https://download.eclipse.org/releases/2026-06"
JUSTJ_REPO = "https://download.eclipse.org/justj/jres/21/updates/release/21.0.11"
CORE_REPOSITORY = "Fenoman/pgcodekeeper-core"
CORE_PIN_FILE = ROOT / ".github/core-ref.env"
CORE_REPOSITORY_KEY = "PGCK_CORE_REPOSITORY"
CORE_COMMIT_KEY = "PGCK_CORE_REF"
CORE_PIN_LOAD = "grep -E '^[A-Z][A-Z0-9_]*=' .github/core-ref.env >> \"$GITHUB_ENV\""
CORE_REPOSITORY_REFERENCE = "${{ env.PGCK_CORE_REPOSITORY }}"
CORE_COMMIT_REFERENCE = "${{ env.PGCK_CORE_REF }}"
CORE_PIN = core_pin()
CORE_COMMIT = CORE_PIN.get(CORE_COMMIT_KEY, "")
TYCHO_RETRY_MARKER = "points to a non existing file"
MAVEN_REPOSITORY_ARGUMENT = '-Dmaven.repo.local="${PGCK_MAVEN_REPO}"'
AUTO_REFRESH_SETTING = "org.eclipse.core.resources/refresh.enabled=true"
STANDALONE_INITIAL_HEAP = "-Xms256m"
STANDALONE_MAX_HEAP = "-Xmx4096m"
# Tuning that has to reach both deployments lives in PreferenceInitializer, and
# project-specific tuning lives in the project's own .settings preferences.
# Product customization outranks bundle defaults, so any of these keys placed in
# plugin_customization.ini would silently apply to the standalone product alone
# and leave every Eclipse plug-in installation untuned.
SHARED_TUNING_KEYS = (
    "ru.taximaxim.codekeeper.ui/getChangesParserWorkers",
    "ru.taximaxim.codekeeper.ui/projectIndexParserWorkers",
    "ru.taximaxim.codekeeper.ui/projectIndexExcludedSchemas",
)
STANDALONE_LIFECYCLE_URI = (
    "bundleclass://ru.taximaxim.codekeeper.ui/"
    "ru.taximaxim.codekeeper.ui.workbench.StandaloneWorkbenchLifecycle")
STANDALONE_WORKBENCH_CLASSES = (
    "ru/taximaxim/codekeeper/ui/workbench/"
    "PersistedWorkbenchModelMigration.class",
    "ru/taximaxim/codekeeper/ui/workbench/"
    "StandaloneWorkbenchLifecycle.class",
)
UNSUPPORTED_STANDALONE_PLUGIN_PREFIXES = (
    "org.eclipse.jdt.ui_",
    "org.eclipse.mylyn.tasks.ui_",
)
JAVA_CLASS_MAGIC = b"\xca\xfe\xba\xbe"
# Comment prose and indentation may change; the statement order may not.
RESET_CONTRACT = re.compile(
    r"private\s+void\s+reset\(\)\s*\{\s*"
    r"getChangesJobs\.cancel\(\);\s*"
    r"closePendingUiPublication\(\);\s*"
    r"(?://[^\n]*\n\s*)*"
    r"if\s*\(getChangesAction\s*!=\s*null\)\s*\{\s*"
    r"getChangesAction\.setEnabled\(true\);\s*\}")
CORE_INSTALL = (
    "mvn -B -ntp -f pgcodekeeper-core/pom.xml clean install -DskipTests "
    + MAVEN_REPOSITORY_ARGUMENT)
MAIN = ROOT / "ru.taximaxim.codekeeper.mainapp"
UI = ROOT / "ru.taximaxim.codekeeper.ui"
PRODUCT_DIR = MAIN / "product/rcp"
PRODUCT = PRODUCT_DIR / "ru.taximaxim.codekeeper.rcp.product"
errors = []


def check(condition, message):
    if not condition:
        errors.append(message)


def read(path):
    try:
        return path.read_text(encoding="utf-8")
    except OSError as exc:
        errors.append(f"cannot read {path}: {exc}")
        return ""


def relative(path):
    """Describe a path relative to the repository whenever it lives inside it."""
    try:
        return path.relative_to(ROOT)
    except ValueError:
        return path


def tokens(path, required=(), forbidden=()):
    content = read(path)
    for token in required:
        check(token in content, f"{relative(path)} must contain {token!r}")
    for token in forbidden:
        check(token not in content, f"{relative(path)} must not contain {token!r}")


def collapsed(text):
    """Collapse whitespace runs so gates do not depend on formatting."""
    return " ".join(text.split())


def collapsed_tokens(path, required=(), forbidden=()):
    """Match tokens against a whitespace-normalized copy of the file."""
    content = collapsed(read(path))
    for token in required:
        check(collapsed(token) in content,
              f"{relative(path)} must contain {token!r} "
              "(ignoring line breaks and indentation)")
    for token in forbidden:
        check(collapsed(token) not in content,
              f"{relative(path)} must not contain {token!r} "
              "(ignoring line breaks and indentation)")


def exact_line(content, expected, context):
    count = sum(line.strip() == expected for line in content.splitlines())
    check(count == 1,
          f"{context} must contain exactly one line {expected!r}, found {count}")


def manifest_main_headers(manifest):
    headers = []
    current = None
    for line in manifest.splitlines():
        if not line:
            break
        if line.startswith(" "):
            if current is not None:
                current[1] += line[1:]
            continue
        current = None
        name, separator, value = line.partition(": ")
        if separator and name:
            current = [name, value]
            headers.append(current)
    return headers


def verify_core_manifest(manifest, context):
    headers = manifest_main_headers(manifest)
    for name, expected in (
            ("Bundle-Version", CORE_OSGI_VERSION),
            ("Implementation-Version", CORE_MAVEN_VERSION),
            ("Implementation-Build", CORE_COMMIT)):
        values = [
            value for candidate, value in headers
            if candidate.casefold() == name.casefold()
        ]
        check(len(values) == 1,
              f"{context} must contain exactly one {name!r} header, "
              f"found {len(values)}")
        if len(values) == 1:
            check(values[0] == expected,
                  f"{context} {name!r} must be exactly {expected!r}, "
                  f"found {values[0]!r}")


def exact_token(content, expected, context):
    count = content.count(expected)
    check(count == 1,
          f"{context} must contain exactly one token {expected!r}, found {count}")


def exact_property(content, key, expected_value, context):
    values = []
    for line in content.splitlines():
        stripped = line.strip()
        if "=" not in stripped:
            continue
        candidate, value = stripped.split("=", 1)
        if candidate.strip() == key:
            values.append(value.strip())
    check(values == [expected_value],
          f"{context} must set {key!r} exactly once to "
          f"{expected_value!r}, found {values}")


def absent_property(content, key, context):
    values = [
        line.strip().split("=", 1)[1].strip()
        for line in content.splitlines()
        if "=" in line.strip()
        and line.strip().split("=", 1)[0].strip() == key
    ]
    check(not values,
          f"{context} must not set {key!r}: tuning shared by both deployments "
          f"belongs to PreferenceInitializer and project-specific tuning to "
          f"the project's own preferences, found {values}")


def exact_prefixed_line(content, prefix, expected, context):
    values = [
        line.strip() for line in content.splitlines()
        if line.strip().startswith(prefix)
    ]
    check(values == [expected],
          f"{context} must contain exactly one {prefix} setting equal to "
          f"{expected!r}, found {values}")


def verify_standalone_heap(content, context):
    exact_prefixed_line(
        content, "-Xms", STANDALONE_INITIAL_HEAP, context)
    exact_prefixed_line(
        content, "-Xmx", STANDALONE_MAX_HEAP, context)


def verify_shared_tuning_is_not_product_only(content, context):
    for key in SHARED_TUNING_KEYS:
        absent_property(content, key, context)


def verify_standalone_lifecycle(content, context):
    root = ET.fromstring(content)
    all_values = [
        element.get("value")
        for element in root.iter()
        if element.tag.rsplit("}", 1)[-1] == "property"
        and element.get("name") == "lifeCycleURI"
    ]
    extensions = [
        element for element in root
        if element.tag == "extension"
        and element.get("point") == "org.eclipse.core.runtime.products"
        and element.get("id") == "product"
    ]
    products = [
        element for element in extensions[0]
        if element.tag == "product"
    ] if len(extensions) == 1 else []
    product_values = [
        element.get("value")
        for element in products[0]
        if element.tag == "property"
        and element.get("name") == "lifeCycleURI"
    ] if len(products) == 1 else []
    check(
        root.tag == "plugin"
        and len(extensions) == 1
        and len(products) == 1
        and all_values == [STANDALONE_LIFECYCLE_URI]
        and product_values == [STANDALONE_LIFECYCLE_URI],
        f"{context} must use an unqualified 'plugin' root and declare "
        f"exactly one unqualified direct runtime "
        f"product extension with id 'product' and one direct "
        f"'lifeCycleURI' equal to {STANDALONE_LIFECYCLE_URI!r}, "
        f"found root={root.tag!r}, extensions={len(extensions)}, "
        f"products={len(products)}, "
        f"all={all_values}, runtime product={product_values}")


def verify_ui_workbench_classes(jar, context):
    corrupt_entry = jar.testzip()
    check(
        corrupt_entry is None,
        f"{context}: nested UI JAR integrity check failed at "
        f"{corrupt_entry!r}")
    for class_name in STANDALONE_WORKBENCH_CLASSES:
        if class_name not in jar.namelist():
            check(
                False,
                f"{context}: required workbench class {class_name} is missing")
            continue
        class_bytes = jar.read(class_name)
        check(
            class_bytes.startswith(JAVA_CLASS_MAGIC),
            f"{context}: required workbench class {class_name} does not "
            "start with Java class magic")


def verify_standalone_bundles(archive, ui, mainapp, context):
    """Inspect the UI and mainapp payload of any archive that ships them."""
    if ui:
        with zipfile.ZipFile(io.BytesIO(archive.read(ui))) as jar:
            verify_ui_workbench_classes(jar, f"{context}: standalone UI")
    if mainapp:
        with zipfile.ZipFile(io.BytesIO(archive.read(mainapp))) as jar:
            customization = jar.read("plugin_customization.ini").decode(
                "utf-8", errors="replace")
            plugin_xml = jar.read("plugin.xml")
        exact_property(
            customization,
            "org.eclipse.core.resources/refresh.enabled", "true",
            f"{context}: standalone customization")
        verify_shared_tuning_is_not_product_only(
            customization, f"{context}: standalone customization")
        verify_standalone_lifecycle(
            plugin_xml, f"{context}: standalone plugin.xml")


def verify_standalone_plugin_inventory(
        names, plugin_folder, context, *, case_insensitive=False):
    comparison_folder = (
        plugin_folder.casefold() if case_insensitive else plugin_folder)
    bundles = {
        name[len(plugin_folder):].split("/", 1)[0]
        for name in names
        if (name.casefold() if case_insensitive else name).startswith(
            comparison_folder)
        and name[len(plugin_folder):]
    }
    for prefix in UNSUPPORTED_STANDALONE_PLUGIN_PREFIXES:
        comparison_prefix = prefix.casefold() if case_insensitive else prefix
        matches = sorted(
            bundle for bundle in bundles
            if (bundle.casefold() if case_insensitive else bundle).startswith(
                comparison_prefix))
        check(
            not matches,
            f"{context}: unsupported standalone plugin prefix "
            f"{prefix!r} matched {matches}")


def pom_version(path, revision):
    pom = ET.parse(path).getroot()
    version = pom.findtext("m:version", namespaces=NS)
    version = version or pom.findtext("m:parent/m:version", namespaces=NS)
    return revision if version == "${revision}" else version


def verify_build_retry(content, step, context):
    """The build step retries the known transient reactor failure exactly once."""
    blocks = re.findall(
        rf"(?ms)^[ \t]*-[ \t]+name:[ \t]+{re.escape(step)}[ \t]*$"
        r".*?(?=^[ \t]*-[ \t]+name:|\Z)",
        content)
    check(len(blocks) == 1, f"{context} must have one {step!r} step")
    if len(blocks) != 1:
        return
    block = blocks[0]
    definitions = re.findall(
        r"^[ \t]*([A-Za-z_][A-Za-z0-9_]*)\(\)[ \t]*\{", block, re.M)
    check(len(definitions) == 1,
          f"{context} {step!r} must define exactly one build function, "
          f"found {definitions}")
    check(TYCHO_RETRY_MARKER in block,
          f"{context} {step!r} must retry only the known transient failure "
          f"{TYCHO_RETRY_MARKER!r}")
    loops = re.findall(r"^[ \t]*(while|until|for)\b", block, re.M)
    check(not loops,
          f"{context} {step!r} must retry a bounded number of times, "
          f"found the loops {loops}")
    if len(definitions) == 1:
        invocations = len(re.findall(rf"\b{definitions[0]}\b", block)) - 1
        check(invocations == 2,
              f"{context} {step!r} must run the build twice at most "
              f"(one retry), found {invocations} invocations")


def verify_workflow(path, *, source_gate):
    content = read(path)
    check(
        "PGCK_MAVEN_REPO: ${{ github.workspace }}/.pgck-m2-"
        "${{ github.run_id }}-${{ github.run_attempt }}" in content,
        f"{relative(path)} must use a run-isolated Maven repository")
    maven_lines = [line for line in content.splitlines() if "mvn " in line]
    check(bool(maven_lines) and all(
        MAVEN_REPOSITORY_ARGUMENT in line for line in maven_lines),
        f"{relative(path)} must isolate every Maven invocation")
    blocks = re.findall(
        r"(?ms)^[ \t]*-[ \t]+name:[ \t]+checkout optimized Core[ \t]*$"
        r".*?(?=^[ \t]*-[ \t]+name:|\Z)",
        content)
    check(len(blocks) == 1,
          f"{relative(path)} must have one optimized Core checkout")
    if len(blocks) == 1:
        block = blocks[0]
        for token in (
                f"repository: {CORE_REPOSITORY_REFERENCE}",
                f"ref: {CORE_COMMIT_REFERENCE}",
                "path: pgcodekeeper-core", "persist-credentials: false"):
            check(token in block,
                  f"{relative(path)} Core checkout must contain {token!r}")
    inline_refs = [
        line.strip() for line in content.splitlines()
        if re.fullmatch(r"[ \t]*ref:[ \t]*[0-9a-f]{40}[ \t]*", line)
    ]
    check(not inline_refs,
          f"{relative(path)} must take the Core pin from "
          f"{CORE_PIN_FILE.name}, found the inline refs {inline_refs}")

    ordered = ["name: set up JDK 21", "name: resolve optimized Core pin",
               f"run: {CORE_PIN_LOAD}", "name: checkout optimized Core"]
    if source_gate:
        ordered.extend((
            "name: verify release sources",
            "python3 -B -m unittest script/test_verify_eclipse_packaging.py",
            "python3 -B script/verify-eclipse-packaging.py",
        ))
    ordered.extend(("name: install optimized Core", f"run: {CORE_INSTALL}"))
    build_step = "build with maven" if source_gate else "build"
    ordered.append(f"name: {build_step}")
    verify_build_retry(content, build_step, relative(path))
    positions = [content.find(token) for token in ordered]
    check(all(position >= 0 for position in positions),
          f"{relative(path)} is missing required Core bootstrap steps")
    check(positions == sorted(positions) and len(set(positions)) == len(positions),
          f"{relative(path)} Core bootstrap steps are out of order")
    if source_gate:
        check("branches: [ master, neo ]" in content,
              f"{relative(path)} must validate both master and neo pull requests")
        check("xvfb-run -a mvn -B -ntp verify --file pom.xml" in content,
              f"{relative(path)} must run the Eclipse UI suite under Xvfb")
        check("permissions:\n  contents: read" in content,
              f"{relative(path)} must use read-only repository permissions")
    else:
        check("permissions:\n  contents: write" in content,
              f"{relative(path)} must explicitly grant release write permission")


def verify_sources(*, standalone_plugin_xml=None):
    pom = ET.parse(ROOT / "pom.xml").getroot()
    revision = pom.findtext("m:properties/m:revision", default="", namespaces=NS)
    check(RELEASE_VERSION_PATTERN.fullmatch(revision) is not None,
          f"root revision must be X.Y.Z, X.Y.Z-SNAPSHOT or X.Y.Z-qualifier, found {revision!r}")
    check(bool(OSGI_VERSION), f"cannot derive an OSGi release version from {revision!r}")

    pin = CORE_PIN_FILE.relative_to(ROOT)
    check(CORE_PIN.get(CORE_REPOSITORY_KEY) == CORE_REPOSITORY,
          f"{pin} must set {CORE_REPOSITORY_KEY} to {CORE_REPOSITORY!r}, "
          f"found {CORE_PIN.get(CORE_REPOSITORY_KEY)!r}")
    check(re.fullmatch("[0-9a-f]{40}", CORE_COMMIT) is not None,
          f"{pin} must set {CORE_COMMIT_KEY} to a full 40-character commit, "
          f"found {CORE_COMMIT!r}")

    release_poms = (
        MAIN / "pom.xml",
        UI / "pom.xml",
        MAIN / "feature/pom.xml",
        MAIN / "updatesite/pom.xml",
        PRODUCT_DIR / "pom.xml",
    )
    for path in release_poms:
        check(pom_version(path, revision) == REACTOR_MAVEN_VERSION,
              f"{path.relative_to(ROOT)} must use reactor version {REACTOR_MAVEN_VERSION}")
    report = ET.parse(MAIN / "report/pom.xml").getroot()
    ui_dependency = report.find(
        "m:dependencies/m:dependency[m:artifactId='ru.taximaxim.codekeeper.ui']/m:version", NS)
    check(ui_dependency is not None and ui_dependency.text == REACTOR_MAVEN_VERSION,
          f"report must depend on UI {REACTOR_MAVEN_VERSION}")

    tokens(MAIN / "META-INF/MANIFEST.MF", (f"Bundle-Version: {OSGI_VERSION}",))
    tokens(UI / "META-INF/MANIFEST.MF", (
        f"Bundle-Version: {OSGI_VERSION}",
        f'org.pgcodekeeper.core;bundle-version="[{CORE_OSGI_VERSION},16.0.0)"',
    ))
    preferences_source = read(
        UI / "src/ru/taximaxim/codekeeper/ui/prefs/Preferences.java")
    collapsed_tokens(UI / "src/ru/taximaxim/codekeeper/ui/prefs/Preferences.java", (
        "Messages.GeneralPrefPage_pg_catalog_cache_rows_tooltip, true, true,",
    ))
    exact_token(
        preferences_source,
        "Messages.GeneralPrefPage_project_index_excluded_schemas_tooltip",
        "generic Eclipse schema-exclusion preference")
    exact_token(
        collapsed(preferences_source),
        'Messages.GeneralPrefPage_project_index_excluded_schemas_tooltip, "", false,',
        "generic Eclipse schema-exclusion default")
    tokens(UI / "src/ru/taximaxim/codekeeper/ui/localizations/messages.properties", (
        "Enabled by default to reduce network traffic",
        "the cache survives application restarts when the same workspace is used",
    ), ("Disabled by default",))
    tokens(UI / "src/ru/taximaxim/codekeeper/ui/settings/FieldEditorStore.java", (
        "projectPrefs.get(e.getPreferenceName(), null)",
        "e.setValue(globalPrefs)",
    ))
    tokens(UI / "src/ru/taximaxim/codekeeper/ui/properties/ProjectProperties.java", (
        "fieldEditorStore.loadProjectValues(prefs, Activator.getDefault().getPreferenceStore())",
    ))
    tokens(UI / "src/ru/taximaxim/codekeeper/ui/settings/UISettings.java", (
        "settings.setAdditionalExcludedSchemas(",
    ))
    feature = ET.parse(MAIN / "feature/feature.xml").getroot()
    check(feature.get("version") == OSGI_VERSION, f"feature version must be {OSGI_VERSION}")
    cores = feature.findall("./plugin[@id='org.pgcodekeeper.core']")
    check(len(cores) == 1 and cores[0].get("version") == CORE_OSGI_VERSION,
          f"Core feature entry must be unique and version {CORE_OSGI_VERSION}")
    tokens(MAIN / "feature/feature.xml", forbidden=("https://pgcodekeeper.org/update/",))
    tokens(MAIN / "app.target", (f"<version>{CORE_MAVEN_VERSION}</version>",), ("org.eclipse.justj",))
    tokens(PRODUCT, (
        f'version="{OSGI_VERSION}"',
        "-Declipse.log.level=WARNING",
        STANDALONE_INITIAL_HEAP,
        STANDALONE_MAX_HEAP,
        '<feature id="org.eclipse.justj.openjdk.hotspot.jre.minimal" installMode="root"/>',
        ECLIPSE_REPO,
    ), ("includeJRE=", "https://pgcodekeeper.org/update/", "/releases/latest"))
    exact_line(read(PRODUCT), "-Declipse.log.level=WARNING",
               PRODUCT.relative_to(ROOT))
    verify_standalone_heap(read(PRODUCT), PRODUCT.relative_to(ROOT))
    tokens(MAIN / "plugin_customization.ini", (AUTO_REFRESH_SETTING,))
    tokens(MAIN / "plugin.xml", (
        '<property name="preferenceCustomization" '
        'value="plugin_customization.ini"/>',
    ))
    verify_standalone_lifecycle(
        (read(MAIN / "plugin.xml") if standalone_plugin_xml is None
         else standalone_plugin_xml),
        (MAIN / "plugin.xml").relative_to(ROOT))
    tokens(MAIN / "build.properties", (
        "plugin_customization.ini",
    ))
    exact_property(read(MAIN / "plugin_customization.ini"),
                   "org.eclipse.core.resources/refresh.enabled", "true",
                   (MAIN / "plugin_customization.ini").relative_to(ROOT))
    verify_shared_tuning_is_not_product_only(
        read(MAIN / "plugin_customization.ini"),
        (MAIN / "plugin_customization.ini").relative_to(ROOT))
    tokens(PRODUCT_DIR / "pom.xml", (
        JUSTJ_REPO, ECLIPSE_REPO, "<rootFolder>pgcodekeeper</rootFolder>",
        "<macosx>pgCodeKeeper.app</macosx>",
        "<archiveFileName>pgCodeKeeper-${revision}</archiveFileName>",
        "<executionEnvironment>none</executionEnvironment>",
    ), ("temurin21-binaries", "download-maven-plugin", "Pgcodekeeper.app"))
    tokens(MAIN / "updatesite/pom.xml",
           ("<finalName>pgCodeKeeper-updatesite-${revision}</finalName>",))
    tokens(ROOT / "pom.xml", forbidden=("<tycho.strictVersions>false</tycho.strictVersions>",))
    for path in ROOT.rglob("pom.xml"):
        tokens(path, forbidden=("verify-eclipse-packaging.py", "python.executable"))

    tokens(ROOT / "script/update-version.sh", (
        "ru.taximaxim.codekeeper.updatesite", '"-DnewVersion=${OSGI_VERSION}"',
        "tycho-versions-plugin:4.0.13:set-property", "-Dproperties=revision",
        '"-DnewRevision=${MAVEN_VERSION}"', "Literal qualifier is reserved",
    ), ("versions-maven-plugin", 'OSGI_VERSION="${OSGI_VERSION//-/.}"'))
    tokens(ROOT / "script/update-version.bat", (
        "ru.taximaxim.codekeeper.updatesite", "-DnewVersion=%OSGI_VERSION%",
        "call mvn org.eclipse.tycho:tycho-versions-plugin:4.0.13:set-version",
        "call mvn org.eclipse.tycho:tycho-versions-plugin:4.0.13:set-property",
        "-Dproperties=revision", "NORMALIZED_VERSION", 'if not "%~2"==""',
        'if not "%MAVEN_VERSION:!=%"=="%MAVEN_VERSION%"',
        "Literal qualifier is reserved",
        "-DnewRevision=%MAVEN_VERSION%",
    ), ("versions-maven-plugin", 'set "OSGI_VERSION=%OSGI_VERSION:-=.%"',
        'if /i "%VERSION_QUALIFIER%"=="SNAPSHOT"'))
    tokens(ROOT / ".github/workflows/release.yml",
           ("name: verify release identity",
            'python3 script/verify-eclipse-packaging.py --release-tag "${GITHUB_REF_NAME}"',
            "verify-eclipse-packaging.py", "--artifacts", "--updatesite-dir",
            '-P "$MAC_PASS"',
            "fail_on_unmatched_files: true"),
           ("pgcodekeeper/pgcodekeeper.github.io", "API_TOKEN_GITHUB",
            "clean update folder", "git push origin HEAD"))
    verify_workflow(ROOT / ".github/workflows/pull-request.yml", source_gate=True)
    verify_workflow(ROOT / ".github/workflows/release.yml", source_gate=False)
    tokens(ROOT / "README.md", (
        "git clone --branch neo https://github.com/Fenoman/pgcodekeeper-core.git",
        'MAVEN_REPO="$(mktemp -d)"',
        f"git checkout {CORE_COMMIT}",
        'mvn -B -ntp clean install -DskipTests -Dmaven.repo.local="$MAVEN_REPO"',
        'mvn -B -ntp clean verify -DskipTests -Dmaven.repo.local="$MAVEN_REPO"',
        f"`{CORE_COMMIT}`",
        "ru.taximaxim.codekeeper.mainapp/product/rcp/target/products",
        "pgCodeKeeper-updatesite-<release-version>.zip",
        "Persistent PostgreSQL catalog row caching is enabled by default",
        "Cache errors or an excessive miss ratio safely fall",
    ), ("https://pgcodekeeper.org/update/", "using Marketplace"))
    collapsed_tokens(ROOT / "README.md", (
        "survives application restarts when the same Eclipse workspace is used",
    ))
    differ = UI / "src/ru/taximaxim/codekeeper/ui/editors/ProjectEditorDiffer.java"
    collapsed_tokens(differ, (
        "public void dispose() { releaseComparisonReferences();",
        "private void releaseComparisonReferences() {",
        "diffTable.clearComparisonReferences();",
        "diffPane.clearComparisonReferences();",
        "sp.clearSelection();",
        "if (!Objects.equals(this.currentRemote, currentRemote)) {",
        "reset(); loadedRemote = null; hideNotificationArea();",
    ))
    check(RESET_CONTRACT.search(read(differ)) is not None,
          f"{differ.relative_to(ROOT)} reset() must cancel the change jobs, close "
          "the pending publication and then restore a disabled get-changes action")


# suffix: (archive root, launcher, ini, JustJ platform, JustJ architecture,
# macOS, Unix)
PRODUCTS = {
    "linux.gtk.x86_64.tar.gz": (
        "pgcodekeeper", "pgcodekeeper", "pgcodekeeper.ini", "linux", "x86_64", False, True),
    "linux.gtk.aarch64.tar.gz": (
        "pgcodekeeper", "pgcodekeeper", "pgcodekeeper.ini", "linux", "aarch64", False, True),
    "win32.win32.x86_64.zip": (
        "pgcodekeeper", "pgcodekeeper.exe", "pgcodekeeper.ini", "win32", "x86_64", False, False),
    "macosx.cocoa.aarch64.tar.gz": (
        "pgCodeKeeper.app", "Contents/MacOS/pgcodekeeper", "Contents/Eclipse/pgcodekeeper.ini",
        "macosx", "aarch64", True, True),
    "macosx.cocoa.x86_64.tar.gz": (
        "pgCodeKeeper.app", "Contents/MacOS/pgcodekeeper", "Contents/Eclipse/pgcodekeeper.ini",
        "macosx", "x86_64", True, True),
}


class Archive:
    def __init__(self, path, *, case_insensitive=False):
        self.path = path
        self.zip = path.suffix == ".zip"
        self.handle = zipfile.ZipFile(path) if self.zip else tarfile.open(path, "r:gz")
        infos = self.handle.infolist() if self.zip else self.handle.getmembers()
        self.entries = {}
        canonical_entries = {}
        self.duplicates = []
        self.special_entries = []
        self.unsafe_entries = []
        for info in infos:
            original = info.filename if self.zip else info.name
            raw_name = original.removeprefix("./").rstrip("/")
            pure = PurePosixPath(raw_name)
            name = str(pure)
            if "\\" in raw_name or pure.is_absolute() or ".." in pure.parts:
                self.unsafe_entries.append(original)
            key = name.casefold() if case_insensitive else name
            if key in canonical_entries:
                self.duplicates.append(f"{canonical_entries[key]} / {original}")
            else:
                canonical_entries[key] = original
                self.entries[name] = info
            if self.zip:
                mode = (info.external_attr >> 16) & 0o170000
                if stat.S_ISLNK(mode):
                    self.special_entries.append(name)
            elif not (info.isfile() or info.isdir()):
                self.special_entries.append(name)

    def close(self):
        self.handle.close()

    def read(self, name):
        if self.zip:
            return self.handle.read(self.entries[name])
        stream = self.handle.extractfile(self.entries[name])
        if stream is None:
            raise OSError(f"{name} is not a readable file")
        return stream.read()

    def mode(self, name):
        info = self.entries[name]
        return ((info.external_attr >> 16) & 0o777) if self.zip else info.mode


def versioned_jar(archive, folder, artifact, version, *, root=""):
    prefix = f"{root}/{folder}/" if root else f"{folder}/"
    matches = [name for name in archive.entries
               if name.startswith(f"{prefix}{artifact}_")
               and "/" not in name[len(prefix):] and name.endswith(".jar")]
    expected = f"{prefix}{artifact}_{version}.jar"
    check(matches == [expected],
          f"{archive.path.name}: expected only {expected}, found {matches}")
    return expected if matches == [expected] else ""


def verify_core_api(jar, context):
    required_classes = (
        "org/pgcodekeeper/core/database/pg/jdbc/PgCatalogReaderPackStore.class",
    )
    for class_name in required_classes:
        check(class_name in jar.namelist(),
              f"{context}: required packed-cache class {class_name} is missing")
    fingerprint_class = (
        "org/pgcodekeeper/core/database/api/loader/"
        "ProjectInputFingerprint.class")
    check(
        fingerprint_class in jar.namelist(),
        f"{context}: required input-fingerprint class "
        f"{fingerprint_class} is missing")

    required = {
        "org/pgcodekeeper/core/settings/AbstractSettings.class":
            b"setAdditionalExcludedSchemas",
        "org/pgcodekeeper/core/settings/ISettings.class":
            b"isAdditionalSchemaExcluded",
    }
    for class_name, method_name in required.items():
        try:
            class_bytes = jar.read(class_name)
        except KeyError:
            class_bytes = b""
        check(
            method_name in class_bytes,
            f"{context}: required API {method_name.decode()} is missing "
            f"from {class_name}")
    capture_class = (
        "org/pgcodekeeper/core/database/api/loader/"
        "IProjectInputFingerprintCapture.class")
    try:
        capture_bytes = jar.read(capture_class)
    except KeyError:
        capture_bytes = b""
    for method_name in (
            b"enableInputFingerprintCapture",
            b"getCapturedInputFingerprints"):
        check(
            method_name in capture_bytes,
            f"{context}: required input-fingerprint API "
            f"{method_name.decode()} is missing from {capture_class}")


def verify_product(path, spec):
    try:
        archive = Archive(path, case_insensitive=spec[3] == "win32")
    except (OSError, tarfile.TarError, zipfile.BadZipFile) as exc:
        errors.append(f"cannot open {path.name}: {exc}")
        return
    root, launcher_name, ini_name, platform, arch, mac, unix = spec
    try:
        names = list(archive.entries)
        check(not archive.duplicates,
              f"{path.name}: duplicate archive entries {archive.duplicates}")
        check(not archive.special_entries,
              f"{path.name}: links or special archive entries {archive.special_entries}")
        check(not archive.unsafe_entries,
              f"{path.name}: unsafe archive entries {archive.unsafe_entries}")
        for name in names:
            pure = PurePosixPath(name)
            check("\\" not in name and not pure.is_absolute() and ".." not in pure.parts,
                  f"{path.name}: unsafe entry {name!r}")
        roots = {name.split("/", 1)[0] for name in names if name}
        check(roots == {root}, f"{path.name}: expected root {root!r}, found {sorted(roots)}")

        launcher, ini = f"{root}/{launcher_name}", f"{root}/{ini_name}"
        check(launcher in archive.entries, f"{path.name}: missing launcher {launcher}")
        check(ini in archive.entries, f"{path.name}: missing ini {ini}")
        eclipse_root = f"{root}/Contents/Eclipse" if mac else root
        java_name = "java.exe" if platform == "win32" else "java"
        plugin_folder = f"{eclipse_root}/plugins/"
        feature_folder = f"{eclipse_root}/features/"
        verify_standalone_plugin_inventory(
            names, plugin_folder, path.name,
            case_insensitive=platform == "win32")
        jre_root_pattern = re.compile(
            rf"^{re.escape(plugin_folder)}"
            rf"org\.eclipse\.justj\.openjdk\.hotspot\.jre\.minimal\."
            rf"{re.escape(platform)}\.{re.escape(arch)}_21\.0\.11\.(v[0-9-]+)$")
        justj_plugins = {name[len(plugin_folder):].split("/", 1)[0]
                         for name in names
                         if name.startswith(plugin_folder + "org.eclipse.justj.")}
        justj_features = {name[len(feature_folder):].split("/", 1)[0]
                          for name in names
                          if name.startswith(feature_folder + "org.eclipse.justj.")}
        platform_roots = [f"{plugin_folder}{plugin}"
                          for plugin in justj_plugins
                          if jre_root_pattern.fullmatch(f"{plugin_folder}{plugin}")]
        check(len(platform_roots) == 1,
              f"{path.name}: expected one platform-specific JustJ 21 runtime, found {platform_roots}")
        jre_root = platform_roots[0] if len(platform_roots) == 1 else ""
        qualifier = ""
        if jre_root:
            qualifier = jre_root_pattern.fullmatch(jre_root).group(1)
            expected_justj_plugins = {
                jre_root.removeprefix(plugin_folder),
                f"org.eclipse.justj.openjdk.hotspot.jre.minimal_21.0.11.{qualifier}.jar",
            }
            expected_justj_features = {
                f"org.eclipse.justj.openjdk.hotspot.jre.minimal_21.0.11.{qualifier}",
            }
            check(justj_plugins == expected_justj_plugins,
                  f"{path.name}: unexpected JustJ plugins {sorted(justj_plugins)}")
            check(justj_features == expected_justj_features,
                  f"{path.name}: unexpected JustJ features {sorted(justj_features)}")
        java = f"{jre_root}/jre/bin/{java_name}" if jre_root else ""
        java_matches = [name for name in names if name == java]
        check(len(java_matches) == 1,
              f"{path.name}: missing platform-specific JustJ Java launcher {java}")
        java = java_matches[0] if len(java_matches) == 1 else ""
        jre_home = f"{jre_root}/jre" if jre_root else ""
        release = f"{jre_home}/release" if jre_home else ""
        check(bool(release) and release in archive.entries,
              f"{path.name}: missing bundled JRE release file")
        if unix:
            if launcher in archive.entries:
                check(bool(archive.mode(launcher) & 0o111), f"{path.name}: launcher is not executable")
            if java:
                check(bool(archive.mode(java) & 0o111), f"{path.name}: bundled Java is not executable")
        if release:
            values = {}
            for line in archive.read(release).decode("utf-8", errors="replace").splitlines():
                if "=" in line:
                    key, value = line.split("=", 1)
                    values[key] = value.strip().strip('"')
            check(values.get("JAVA_VERSION", "").startswith("21.0.11"),
                  f"{path.name}: JRE release does not identify Java 21.0.11")
            modules = set(values.get("MODULES", "").split())
            required_modules = {
                "java.base", "java.desktop", "java.naming", "java.net.http",
                "java.sql", "java.xml", "jdk.unsupported", "jdk.zipfs",
            }
            check(required_modules <= modules,
                  f"{path.name}: bundled JRE lacks modules {sorted(required_modules - modules)}")
        if ini in archive.entries:
            ini_content = archive.read(ini).decode()
            lines = [line.strip() for line in ini_content.splitlines()]
            verify_standalone_heap(
                ini_content, f"{path.name}: standalone launcher")
            for arg in ("-Declipse.log.level=WARNING", "-XX:+UseG1GC",
                        "-XX:+HeapDumpOnOutOfMemoryError"):
                check(lines.count(arg) == 1, f"{path.name}: expected exactly one {arg}")
            check(lines.count("-XstartOnFirstThread") == int(mac),
                  f"{path.name}: invalid -XstartOnFirstThread count")
            vm_indexes = [index for index, line in enumerate(lines) if line == "-vm"]
            check(len(vm_indexes) == 1 and vm_indexes[0] + 1 < len(lines),
                  f"{path.name}: expected one bundled-JRE -vm entry")
            if len(vm_indexes) == 1 and vm_indexes[0] + 1 < len(lines):
                vm_value = lines[vm_indexes[0] + 1].strip('"').replace('\\', '/')
                vm_target = posixpath.normpath(
                        str(PurePosixPath(launcher).parent / vm_value))
                if platform == "win32":
                    expected_vm = f"{jre_home}/bin"
                    present = any(name.startswith(expected_vm + "/") for name in names)
                elif mac:
                    expected_vm = f"{jre_home}/lib/libjli.dylib"
                    present = expected_vm in archive.entries
                else:
                    expected_vm = java
                    present = expected_vm in archive.entries
                check(vm_target == expected_vm and present,
                      f"{path.name}: -vm does not select its bundled JRE: {vm_value!r}")

        core = versioned_jar(archive, "plugins", "org.pgcodekeeper.core", CORE_OSGI_VERSION,
                             root=eclipse_root)
        ui = versioned_jar(
            archive, "plugins", "ru.taximaxim.codekeeper.ui",
            OSGI_VERSION, root=eclipse_root)
        mainapp = versioned_jar(
            archive, "plugins", "ru.taximaxim.codekeeper.mainapp",
            OSGI_VERSION, root=eclipse_root)
        feature_root = f"{eclipse_root}/features/ru.taximaxim.codekeeper.feature_{OSGI_VERSION}"
        feature_versions = set()
        feature_folder = f"{eclipse_root}/features/"
        for name in names:
            if name.startswith(feature_folder + "ru.taximaxim.codekeeper.feature_"):
                separator = name.find("/", len(feature_folder))
                feature_versions.add(name if separator < 0 else name[:separator])
        check(feature_versions == {feature_root},
              f"{path.name}: expected only feature {feature_root}, found {sorted(feature_versions)}")
        feature = f"{feature_root}/feature.xml"
        check(feature in archive.entries, f"{path.name}: missing {feature}")
        if core:
            with zipfile.ZipFile(io.BytesIO(archive.read(core))) as jar:
                manifest = jar.read("META-INF/MANIFEST.MF").decode("utf-8", errors="replace")
                verify_core_api(jar, f"{path.name}: Core")
            verify_core_manifest(manifest, f"{path.name}: Core manifest")
        verify_standalone_bundles(archive, ui, mainapp, path.name)
        if feature in archive.entries:
            feature_xml = archive.read(feature).decode("utf-8", errors="replace")
            check("https://pgcodekeeper.org/update/" not in feature_xml,
                  f"{path.name}: feature advertises the upstream update site")
    except (KeyError, OSError, UnicodeDecodeError, ET.ParseError,
            zipfile.BadZipFile) as exc:
        errors.append(f"cannot inspect {path.name}: {exc}")
    finally:
        archive.close()


def verify_artifacts(directory):
    check(directory.is_dir(), f"artifact directory does not exist: {directory}")
    if not directory.is_dir():
        return
    p2content = read(directory.parent / "p2content.xml")
    check("name='org.eclipse.justj.openjdk.hotspot.jre.minimal.feature.group'" in p2content,
          "product p2 metadata must require the explicit JustJ minimal feature")
    check("namespace='org.eclipse.justj' name='jre'" not in p2content,
          "product p2 metadata must not contain an abstract JustJ runtime requirement")
    expected = {f"pgCodeKeeper-{MAVEN_VERSION}-{suffix}": spec for suffix, spec in PRODUCTS.items()}
    actual = {path.name for path in directory.iterdir()
              if path.is_file() and (path.suffix == ".zip" or path.name.endswith(".tar.gz"))}
    check(actual == set(expected),
          f"expected exactly {sorted(expected)}, found {sorted(actual)}")
    for name in actual & set(expected):
        verify_product(directory / name, expected[name])


def verify_updatesite(directory):
    check(directory.is_dir(), f"update-site directory does not exist: {directory}")
    if not directory.is_dir():
        return
    expected_name = f"pgCodeKeeper-updatesite-{MAVEN_VERSION}.zip"
    actual = {path.name for path in directory.glob("pgCodeKeeper-updatesite-*.zip")}
    check(actual == {expected_name},
          f"expected only update site {expected_name}, found {sorted(actual)}")
    path = directory / expected_name
    if not path.is_file():
        return
    try:
        archive = Archive(path)
    except (OSError, zipfile.BadZipFile) as exc:
        errors.append(f"cannot open {path.name}: {exc}")
        return
    try:
        check(not archive.duplicates,
              f"{path.name}: duplicate archive entries {archive.duplicates}")
        check(not archive.special_entries,
              f"{path.name}: links or special archive entries {archive.special_entries}")
        check(not archive.unsafe_entries,
              f"{path.name}: unsafe archive entries {archive.unsafe_entries}")
        check(archive.handle.testzip() is None, f"{path.name}: ZIP integrity check failed")
        for required in ("p2.index", "content.jar", "artifacts.jar"):
            check(required in archive.entries, f"{path.name}: missing {required}")
        core = versioned_jar(archive, "plugins", "org.pgcodekeeper.core", CORE_OSGI_VERSION)
        ui = versioned_jar(
            archive, "plugins", "ru.taximaxim.codekeeper.ui", OSGI_VERSION)
        mainapp = versioned_jar(
            archive, "plugins", "ru.taximaxim.codekeeper.mainapp", OSGI_VERSION)
        feature = versioned_jar(
            archive, "features", "ru.taximaxim.codekeeper.feature", OSGI_VERSION)
        justj_entries = [name for name in archive.entries if "org.eclipse.justj" in name]
        check(not justj_entries,
              f"{path.name}: plugin update site must not bundle a standalone JRE: {justj_entries}")
        if core:
            with zipfile.ZipFile(io.BytesIO(archive.read(core))) as jar:
                manifest = jar.read("META-INF/MANIFEST.MF").decode("utf-8", errors="replace")
                verify_core_api(jar, f"{path.name}: Core")
            verify_core_manifest(manifest, f"{path.name}: Core manifest")
        verify_standalone_bundles(archive, ui, mainapp, path.name)
        if feature:
            with zipfile.ZipFile(io.BytesIO(archive.read(feature))) as jar:
                feature_xml = jar.read("feature.xml").decode("utf-8", errors="replace")
            check(f'version="{OSGI_VERSION}"' in feature_xml,
                  f"{path.name}: feature metadata is not {OSGI_VERSION}")
            check("https://pgcodekeeper.org/update/" not in feature_xml,
                  f"{path.name}: feature advertises the upstream update site")
    except (KeyError, OSError, UnicodeDecodeError, ET.ParseError,
            zipfile.BadZipFile) as exc:
        errors.append(f"cannot inspect {path.name}: {exc}")
    finally:
        archive.close()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--artifacts", type=Path)
    parser.add_argument("--updatesite-dir", type=Path)
    parser.add_argument("--release-tag")
    args = parser.parse_args()
    try:
        verify_sources()
        if args.release_tag is not None:
            check(args.release_tag == f"v{MAVEN_VERSION}",
                  f"release tag must be v{MAVEN_VERSION}, found {args.release_tag!r}")
        if args.artifacts is not None:
            verify_artifacts(args.artifacts)
        if args.updatesite_dir is not None:
            verify_updatesite(args.updatesite_dir)
    except (OSError, ValueError, ET.ParseError) as exc:
        errors.append(str(exc))
    if errors:
        print("Eclipse packaging verification FAILED:", file=sys.stderr)
        for error in errors:
            print(f"  - {error}", file=sys.stderr)
        return 1
    print("Eclipse packaging verification PASSED")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
