#!/usr/bin/env python3

import io
import importlib.util
import tempfile
import unittest
import zipfile
import xml.etree.ElementTree as ET
from pathlib import Path


SCRIPT = Path(__file__).with_name("verify-eclipse-packaging.py")
SPEC = importlib.util.spec_from_file_location("verify_eclipse_packaging", SCRIPT)
VERIFY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(VERIFY)


class ExactLineTest(unittest.TestCase):
    def setUp(self):
        VERIFY.errors.clear()

    def test_exact_line_accepts_one_trimmed_occurrence(self):
        VERIFY.exact_line("other\n  required=value  \n", "required=value",
                          "configuration")

        self.assertEqual([], VERIFY.errors)

    def test_exact_line_rejects_duplicates(self):
        VERIFY.exact_line("required=value\nrequired=value\n", "required=value",
                          "configuration")

        self.assertEqual(
            ["configuration must contain exactly one line 'required=value', found 2"],
            VERIFY.errors)

    def test_exact_token_rejects_duplicate_defaults(self):
        VERIFY.exact_token("default=false; default=false;", "default=false",
                           "generic default")

        self.assertEqual(
            ["generic default must contain exactly one token 'default=false', found 2"],
            VERIFY.errors)

    def test_exact_property_rejects_conflicting_assignment(self):
        VERIFY.exact_property(
            "refresh.enabled=true\nrefresh.enabled=false\n",
            "refresh.enabled", "true", "configuration")

        self.assertEqual(
            ["configuration must set 'refresh.enabled' exactly once to 'true', "
             "found ['true', 'false']"],
            VERIFY.errors)

    def test_exact_property_accepts_one_trimmed_assignment(self):
        VERIFY.exact_property(
            "other=value\n  refresh.enabled = true  \n",
            "refresh.enabled", "true", "configuration")

        self.assertEqual([], VERIFY.errors)

    def test_standalone_product_enables_workspace_auto_refresh(self):
        customization = (
            VERIFY.MAIN / "plugin_customization.ini"
        ).read_text(encoding="utf-8")

        self.assertEqual(
            1,
            sum(
                line.strip() == "org.eclipse.core.resources/refresh.enabled=true"
                for line in customization.splitlines()))

    def test_standalone_customization_is_wired_into_the_bundle(self):
        plugin_xml = (
            VERIFY.MAIN / "plugin.xml"
        ).read_text(encoding="utf-8")
        build_properties = (
            VERIFY.MAIN / "build.properties"
        ).read_text(encoding="utf-8")

        self.assertIn(
            '<property name="preferenceCustomization" '
            'value="plugin_customization.ini"/>',
            plugin_xml)
        self.assertIn("plugin_customization.ini", build_properties)

    def test_standalone_heap_policy_accepts_exact_values(self):
        VERIFY.verify_standalone_heap(
            "-Xms256m\n-Xmx4096m\n", "standalone launcher")

        self.assertEqual([], VERIFY.errors)

    def test_standalone_heap_policy_rejects_invalid_xmx(self):
        invalid_configurations = {
            "missing": "-Xms256m\n",
            "duplicate": "-Xms256m\n-Xmx4096m\n-Xmx4096m\n",
            "conflicting": "-Xms256m\n-Xmx4096m\n-Xmx2048m\n",
            "old value": "-Xms256m\n-Xmx3072m\n",
        }

        for case, content in invalid_configurations.items():
            with self.subTest(case=case):
                VERIFY.verify_standalone_heap(content, "standalone launcher")

                self.assertTrue(
                    any("-Xmx4096m" in error for error in VERIFY.errors),
                    VERIFY.errors)
                VERIFY.errors.clear()

    def test_standalone_heap_policy_rejects_invalid_xms(self):
        invalid_configurations = {
            "missing": "-Xmx4096m\n",
            "duplicate": "-Xms256m\n-Xms256m\n-Xmx4096m\n",
            "conflicting": "-Xms256m\n-Xms512m\n-Xmx4096m\n",
            "wrong value": "-Xms512m\n-Xmx4096m\n",
        }

        for case, content in invalid_configurations.items():
            with self.subTest(case=case):
                VERIFY.verify_standalone_heap(content, "standalone launcher")

                self.assertTrue(
                    any("-Xms256m" in error for error in VERIFY.errors),
                    VERIFY.errors)
                VERIFY.errors.clear()

    def test_shared_tuning_accepts_a_customization_without_it(self):
        VERIFY.verify_shared_tuning_is_not_product_only(
            "org.eclipse.core.resources/refresh.enabled=true\n"
            "org.eclipse.ui/DOCK_PERSPECTIVE_BAR=topRight\n",
            "standalone customization")

        self.assertEqual([], VERIFY.errors)

    def test_shared_tuning_rejects_product_only_values(self):
        # Any one of these in the product customization leaves the generic
        # Eclipse plug-in untuned, because only the standalone product reads it.
        invalid_configurations = {
            "get changes workers": (
                "ru.taximaxim.codekeeper.ui/getChangesParserWorkers=6\n"),
            "project index workers": (
                "ru.taximaxim.codekeeper.ui/projectIndexParserWorkers=6\n"),
            "project specific schemas": (
                "ru.taximaxim.codekeeper.ui/"
                "projectIndexExcludedSchemas=dummy_tmp\n"),
            "generic default repeated": (
                "ru.taximaxim.codekeeper.ui/getChangesParserWorkers=2\n"
                "ru.taximaxim.codekeeper.ui/projectIndexParserWorkers=2\n"),
            "indented": (
                "  ru.taximaxim.codekeeper.ui/getChangesParserWorkers = 6  \n"),
        }

        for case, content in invalid_configurations.items():
            with self.subTest(case=case):
                VERIFY.verify_shared_tuning_is_not_product_only(
                    content, "standalone customization")

                self.assertTrue(VERIFY.errors)
                VERIFY.errors.clear()

    def test_current_standalone_sources_follow_runtime_policy(self):
        VERIFY.verify_standalone_heap(
            VERIFY.PRODUCT.read_text(encoding="utf-8"),
            VERIFY.PRODUCT.relative_to(VERIFY.ROOT))
        VERIFY.verify_shared_tuning_is_not_product_only(
            (VERIFY.MAIN / "plugin_customization.ini").read_text(
                encoding="utf-8"),
            (VERIFY.MAIN / "plugin_customization.ini").relative_to(
                VERIFY.ROOT))

        self.assertEqual([], VERIFY.errors)

    def test_core_pin_has_a_single_source_of_truth(self):
        expected_commit = "c106361b9223cac67f66785f0c3ff6cd9fe21c2a"

        self.assertEqual(expected_commit, VERIFY.CORE_COMMIT)
        self.assertEqual(
            "Fenoman/pgcodekeeper-core",
            VERIFY.CORE_PIN["PGCK_CORE_REPOSITORY"])
        for relative_path in (
                ".github/workflows/pull-request.yml",
                ".github/workflows/release.yml"):
            content = (VERIFY.ROOT / relative_path).read_text(encoding="utf-8")
            self.assertNotIn(expected_commit, content, relative_path)
            self.assertIn(VERIFY.CORE_PIN_LOAD, content)
            self.assertIn(
                f"ref: {VERIFY.CORE_COMMIT_REFERENCE}", content)
            self.assertIn(
                f"repository: {VERIFY.CORE_REPOSITORY_REFERENCE}", content)

    def test_core_pin_reader_ignores_comments_and_blank_lines(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "core-ref.env"
            path.write_text(
                "# pinned Core\n"
                "\n"
                "PGCK_CORE_REPOSITORY = Fenoman/pgcodekeeper-core \n"
                "PGCK_CORE_REF=0123456789abcdef0123456789abcdef01234567\n",
                encoding="utf-8")
            original = VERIFY.CORE_PIN_FILE
            try:
                VERIFY.CORE_PIN_FILE = path
                pin = VERIFY.core_pin()
            finally:
                VERIFY.CORE_PIN_FILE = original

        self.assertEqual(
            {"PGCK_CORE_REPOSITORY": "Fenoman/pgcodekeeper-core",
             "PGCK_CORE_REF": "0123456789abcdef0123456789abcdef01234567"},
            pin)

    def test_workflow_gate_accepts_the_current_workflows(self):
        VERIFY.verify_workflow(
            VERIFY.ROOT / ".github/workflows/pull-request.yml",
            source_gate=True)
        VERIFY.verify_workflow(
            VERIFY.ROOT / ".github/workflows/release.yml", source_gate=False)

        self.assertEqual([], VERIFY.errors)

    def test_workflow_gate_rejects_a_reinlined_core_ref(self):
        content = (
            VERIFY.ROOT / ".github/workflows/pull-request.yml"
        ).read_text(encoding="utf-8")
        reinlined = content.replace(
            f"ref: {VERIFY.CORE_COMMIT_REFERENCE}",
            f"ref: {VERIFY.CORE_COMMIT}")

        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "pull-request.yml"
            path.write_text(reinlined, encoding="utf-8")

            VERIFY.verify_workflow(path, source_gate=True)

        self.assertTrue(
            any("must take the Core pin from" in error
                for error in VERIFY.errors),
            VERIFY.errors)

    def test_workflows_use_an_isolated_maven_repository(self):
        repository_argument = '-Dmaven.repo.local="${PGCK_MAVEN_REPO}"'

        for relative_path in (
                ".github/workflows/pull-request.yml",
                ".github/workflows/release.yml"):
            content = (VERIFY.ROOT / relative_path).read_text(encoding="utf-8")
            self.assertIn(
                "PGCK_MAVEN_REPO: "
                "${{ github.workspace }}/.pgck-m2-"
                "${{ github.run_id }}-${{ github.run_attempt }}",
                content)
            maven_lines = [
                line for line in content.splitlines() if "mvn " in line
            ]
            self.assertTrue(maven_lines)
            self.assertTrue(all(
                repository_argument in line for line in maven_lines),
                maven_lines)

    def test_build_retry_accepts_one_guarded_retry(self):
        VERIFY.verify_build_retry(
            "    - name: build\n"
            "      run: |\n"
            "        run_build() {\n"
            "          mvn verify | tee log\n"
            "        }\n"
            "        if run_build; then\n"
            "          exit 0\n"
            "        fi\n"
            "        if grep -q 'points to a non existing file' log; then\n"
            "          run_build\n"
            "        fi\n",
            "build", "workflow")

        self.assertEqual([], VERIFY.errors)

    def test_build_retry_rejects_unbounded_and_ungated_retries(self):
        header = (
            "    - name: build\n"
            "      run: |\n"
            "        run_build() {\n"
            "          mvn verify | tee log\n"
            "        }\n")
        gate = "        if grep -q 'points to a non existing file' log; then\n"
        invalid_steps = {
            "loop": header + "        for i in 1 2 3; do\n"
                             "          run_build && exit 0\n"
                             "        done\n"
                             + gate + "          run_build\n        fi\n",
            "ungated": header + "        run_build || run_build\n",
            "three attempts": header + "        if run_build; then\n"
                                       "          exit 0\n"
                                       "        fi\n"
                              + gate + "          run_build || run_build\n"
                                       "        fi\n",
            "no retry": header + gate + "          echo skip\n        fi\n"
                                        "        run_build\n",
        }

        for case, content in invalid_steps.items():
            with self.subTest(case=case):
                VERIFY.verify_build_retry(content, "build", "workflow")

                self.assertTrue(VERIFY.errors, case)
                VERIFY.errors.clear()

    def test_release_workflows_retry_the_known_transient_failure_once(self):
        for relative_path, step in (
                (".github/workflows/pull-request.yml", "build with maven"),
                (".github/workflows/release.yml", "build")):
            with self.subTest(workflow=relative_path):
                content = (
                    VERIFY.ROOT / relative_path
                ).read_text(encoding="utf-8")

                VERIFY.verify_build_retry(content, step, relative_path)

                self.assertEqual([], VERIFY.errors)

    def test_pull_request_runs_packaging_unit_tests_before_source_gate(self):
        content = (
            VERIFY.ROOT / ".github/workflows/pull-request.yml"
        ).read_text(encoding="utf-8")
        unit_tests = (
            "python3 -B -m unittest script/test_verify_eclipse_packaging.py")
        source_gate = "python3 -B script/verify-eclipse-packaging.py"

        self.assertGreaterEqual(content.find(unit_tests), 0)
        self.assertGreater(
            content.find(source_gate), content.find(unit_tests))

    @staticmethod
    def _core_manifest(version="15.2.0-neo1", commit=None, separator="\n"):
        commit = VERIFY.CORE_COMMIT if commit is None else commit
        return separator.join((
            "Manifest-Version: 1.0",
            "Bundle-Version: 15.2.0.neo1",
            f"Implementation-Version: {version}",
            f"Implementation-Build: {commit}",
            ""))

    def test_core_manifest_verification_requires_exact_versions(self):
        VERIFY.verify_core_manifest(
            "Manifest-Version: 1.0\n"
            "Bundle-Version: 15.2.0.neo1\n",
            "Core manifest")

        self.assertEqual(
            ["Core manifest must contain exactly one "
             "'Implementation-Version' header, found 0",
             "Core manifest must contain exactly one "
             "'Implementation-Build' header, found 0"],
            VERIFY.errors)

        VERIFY.errors.clear()
        VERIFY.verify_core_manifest(self._core_manifest(), "Core manifest")

        self.assertEqual([], VERIFY.errors)

    def test_core_manifest_verification_rejects_a_foreign_build_commit(self):
        stale = "0123456789abcdef0123456789abcdef01234567"
        VERIFY.verify_core_manifest(
            self._core_manifest(commit=stale), "Core manifest")

        self.assertEqual(
            ["Core manifest 'Implementation-Build' must be exactly "
             f"'{VERIFY.CORE_COMMIT}', found {stale!r}"],
            VERIFY.errors)

    def test_core_manifest_verification_rejects_a_build_without_git_metadata(self):
        VERIFY.verify_core_manifest(
            "Manifest-Version: 1.0\n"
            "Bundle-Version: 15.2.0.neo1\n"
            "Implementation-Version: 15.2.0-neo1\n",
            "Core manifest")

        self.assertEqual(
            ["Core manifest must contain exactly one "
             "'Implementation-Build' header, found 0"],
            VERIFY.errors)

    def test_core_manifest_verification_rejects_orphan_continuation(self):
        VERIFY.verify_core_manifest(
            " Implementation-Version: 15.2.0-neo1\r\n"
            "Manifest-Version: 1.0\r\n"
            "Bundle-Version: 15.2.0.neo1\r\n"
            f"Implementation-Build: {VERIFY.CORE_COMMIT}\r\n",
            "Core manifest")

        self.assertEqual(
            ["Core manifest must contain exactly one "
             "'Implementation-Version' header, found 0"],
            VERIFY.errors)

    def test_core_manifest_verification_rejects_value_whitespace(self):
        for value in (" 15.2.0-neo1", "15.2.0-neo1 "):
            with self.subTest(value=value):
                VERIFY.verify_core_manifest(
                    self._core_manifest(version=value), "Core manifest")

                self.assertEqual(
                    ["Core manifest 'Implementation-Version' must be exactly "
                     f"'15.2.0-neo1', found {value!r}"],
                    VERIFY.errors)
                VERIFY.errors.clear()

    def test_core_manifest_verification_rejects_case_insensitive_duplicates(self):
        VERIFY.verify_core_manifest(
            self._core_manifest()
            + "implementation-version: 15.2.0-neo1\n",
            "Core manifest")

        self.assertEqual(
            ["Core manifest must contain exactly one "
             "'Implementation-Version' header, found 2"],
            VERIFY.errors)

    def test_core_manifest_verification_accepts_crlf_and_continuations(self):
        VERIFY.verify_core_manifest(
            "Manifest-Version: 1.0\r\n"
            "Bundle-Version: 15.2.0.neo1\r\n"
            "Implementation-Version: 15.2.0-\r\n"
            " neo1\r\n"
            f"Implementation-Build: {VERIFY.CORE_COMMIT[:20]}\r\n"
            f" {VERIFY.CORE_COMMIT[20:]}\r\n",
            "Core manifest")

        self.assertEqual([], VERIFY.errors)

    def test_core_manifest_verification_ignores_per_entry_sections(self):
        VERIFY.verify_core_manifest(
            "Manifest-Version: 1.0\r\n"
            "Bundle-Version: 15.2.0.neo1\r\n"
            "\r\n"
            "Name: org/pgcodekeeper/core/utils/Utils.class\r\n"
            "Implementation-Version: 15.2.0-neo1\r\n"
            f"Implementation-Build: {VERIFY.CORE_COMMIT}\r\n",
            "Core manifest")

        self.assertEqual(
            ["Core manifest must contain exactly one "
             "'Implementation-Version' header, found 0",
             "Core manifest must contain exactly one "
             "'Implementation-Build' header, found 0"],
            VERIFY.errors)

    def test_core_api_verification_rejects_same_version_jar_without_methods(self):
        complete = self._core_jar(
            b"setAdditionalExcludedSchemas",
            b"isAdditionalSchemaExcluded",
            include_packed_cache=True,
            include_input_fingerprints=True)
        with zipfile.ZipFile(io.BytesIO(complete)) as jar:
            VERIFY.verify_core_api(jar, "complete Core")
        self.assertEqual([], VERIFY.errors)

        incomplete = self._core_jar(
            b"old AbstractSettings", b"old ISettings",
            include_packed_cache=True,
            include_input_fingerprints=True)
        with zipfile.ZipFile(io.BytesIO(incomplete)) as jar:
            VERIFY.verify_core_api(jar, "stale Core")
        self.assertEqual(2, len(VERIFY.errors))
        self.assertTrue(all("required API" in error for error in VERIFY.errors))

    def test_core_api_verification_rejects_jar_without_packed_cache(self):
        incomplete = self._core_jar(
            b"setAdditionalExcludedSchemas",
            b"isAdditionalSchemaExcluded",
            include_packed_cache=False,
            include_input_fingerprints=True)

        with zipfile.ZipFile(io.BytesIO(incomplete)) as jar:
            VERIFY.verify_core_api(jar, "unpacked Core")

        self.assertEqual(1, len(VERIFY.errors))
        self.assertIn("required packed-cache class", VERIFY.errors[0])

    def test_core_api_verification_rejects_jar_without_inline_fingerprints(self):
        incomplete = self._core_jar(
            b"setAdditionalExcludedSchemas",
            b"isAdditionalSchemaExcluded",
            include_packed_cache=True,
            include_input_fingerprints=False)

        with zipfile.ZipFile(io.BytesIO(incomplete)) as jar:
            VERIFY.verify_core_api(jar, "old Core")

        self.assertEqual(3, len(VERIFY.errors))
        self.assertTrue(all(
            "input-fingerprint" in error for error in VERIFY.errors))

    @staticmethod
    def _core_jar(abstract_settings, settings_interface, *,
                  include_packed_cache, include_input_fingerprints):
        output = io.BytesIO()
        with zipfile.ZipFile(output, "w") as jar:
            jar.writestr(
                "org/pgcodekeeper/core/settings/AbstractSettings.class",
                abstract_settings)
            jar.writestr(
                "org/pgcodekeeper/core/settings/ISettings.class",
                settings_interface)
            if include_packed_cache:
                jar.writestr(
                    "org/pgcodekeeper/core/database/pg/jdbc/"
                    "PgCatalogReaderPackStore.class",
                    b"packed cache")
            if include_input_fingerprints:
                jar.writestr(
                    "org/pgcodekeeper/core/database/api/loader/"
                    "ProjectInputFingerprint.class",
                    b"input fingerprint")
                jar.writestr(
                    "org/pgcodekeeper/core/database/api/loader/"
                    "IProjectInputFingerprintCapture.class",
                    b"enableInputFingerprintCapture "
                    b"getCapturedInputFingerprints")
        return output.getvalue()


class FormattingTolerantSourceGateTest(unittest.TestCase):
    """The source gate reads structure, not indentation or comment prose."""

    RESET = ("private void reset() {\n"
             "        getChangesJobs.cancel();\n"
             "        closePendingUiPublication();\n"
             "        // A cancelled stale job is no longer allowed to publish "
             "its done callback.\n"
             "        if (getChangesAction != null) {\n"
             "            getChangesAction.setEnabled(true);\n"
             "        }\n")

    def setUp(self):
        VERIFY.errors.clear()

    def test_collapsed_tokens_ignore_wrapping_but_not_values(self):
        token = ("Messages.GeneralPrefPage_pg_catalog_cache_rows_tooltip, "
                 "true, true,")
        accepted = {
            "wrapped": "        Messages.GeneralPrefPage_pg_catalog_cache_rows"
                       "_tooltip,\n                    true, true,\n",
            "single line": "    Messages.GeneralPrefPage_pg_catalog_cache_rows"
                           "_tooltip, true, true, Set.of()\n",
        }
        rejected = {
            "disabled default": "Messages.GeneralPrefPage_pg_catalog_cache_"
                                "rows_tooltip,\n                    false, "
                                "true,\n",
            "hidden preference": "Messages.GeneralPrefPage_pg_catalog_cache_"
                                 "rows_tooltip,\n                    true, "
                                 "false,\n",
        }

        for case, content in accepted.items():
            with self.subTest(accepted=case):
                self._collapsed_tokens(content, token)

                self.assertEqual([], VERIFY.errors)
        for case, content in rejected.items():
            with self.subTest(rejected=case):
                self._collapsed_tokens(content, token)

                self.assertEqual(1, len(VERIFY.errors), VERIFY.errors)
                VERIFY.errors.clear()

    def test_reset_contract_tolerates_reformatting_and_reworded_comments(self):
        accepted = {
            "current source": self.RESET,
            "reworded comments": self.RESET.replace(
                "A cancelled stale job is no longer allowed to publish its "
                "done callback.", "Stale jobs may not publish."),
            "no comments": "private void reset() { getChangesJobs.cancel(); "
                           "closePendingUiPublication(); "
                           "if (getChangesAction != null) { "
                           "getChangesAction.setEnabled(true); } }",
            "reindented": self.RESET.replace("        ", "  "),
        }

        for case, content in accepted.items():
            with self.subTest(case=case):
                self.assertIsNotNone(
                    VERIFY.RESET_CONTRACT.search(content), case)

    def test_reset_contract_still_rejects_the_original_regressions(self):
        rejected = {
            "jobs not cancelled": self.RESET.replace(
                "        getChangesJobs.cancel();\n", ""),
            "publication not closed": self.RESET.replace(
                "        closePendingUiPublication();\n", ""),
            "action left disabled": self.RESET.replace(
                "getChangesAction.setEnabled(true);",
                "getChangesAction.setEnabled(false);"),
            "action restore not guarded": self.RESET.replace(
                "        if (getChangesAction != null) {\n"
                "            getChangesAction.setEnabled(true);\n"
                "        }\n", ""),
            "statement inserted before the restore": self.RESET.replace(
                "        if (getChangesAction != null) {\n",
                "        rollbackComparisonPublication();\n"
                "        if (getChangesAction != null) {\n"),
        }

        for case, content in rejected.items():
            with self.subTest(case=case):
                self.assertIsNone(VERIFY.RESET_CONTRACT.search(content), case)

    @staticmethod
    def _collapsed_tokens(content, token):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "Preferences.java"
            path.write_text(content, encoding="utf-8")
            VERIFY.collapsed_tokens(path, (token,))


class StandaloneWorkbenchPackagingTest(unittest.TestCase):
    LIFECYCLE_URI = (
        "bundleclass://ru.taximaxim.codekeeper.ui/"
        "ru.taximaxim.codekeeper.ui.workbench.StandaloneWorkbenchLifecycle")
    PERSISTED_CLASS = (
        "ru/taximaxim/codekeeper/ui/workbench/"
        "PersistedWorkbenchModelMigration.class")
    LIFECYCLE_CLASS = (
        "ru/taximaxim/codekeeper/ui/workbench/"
        "StandaloneWorkbenchLifecycle.class")
    CLASS_MAGIC = b"\xca\xfe\xba\xbe"

    def setUp(self):
        VERIFY.errors.clear()

    def _verify_lifecycle(self, content):
        VERIFY.verify_standalone_lifecycle(
            content, "standalone plugin.xml")

    def _verify_ui_jar(self, content):
        with zipfile.ZipFile(io.BytesIO(content)) as jar:
            VERIFY.verify_ui_workbench_classes(jar, "standalone UI")

    def _verify_plugin_inventory(self, names):
        VERIFY.verify_standalone_plugin_inventory(
            names, "pgcodekeeper/plugins/", "standalone product")

    def _verify_plugin_inventory_case(self, names, *, case_insensitive):
        VERIFY.verify_standalone_plugin_inventory(
            names, "pgcodekeeper/plugins/", "standalone product",
            case_insensitive=case_insensitive)

    def test_lifecycle_property_accepts_one_exact_value(self):
        self._verify_lifecycle(
            '<plugin><extension id="product" '
            'point="org.eclipse.core.runtime.products">'
            "<product>"
            f'<property name="lifeCycleURI" value="{self.LIFECYCLE_URI}"/>'
            "</product></extension></plugin>")

        self.assertEqual([], VERIFY.errors)

    def test_lifecycle_property_rejects_missing_duplicate_and_wrong_value(self):
        invalid_documents = {
            "missing": (
                '<plugin><extension id="product" '
                'point="org.eclipse.core.runtime.products">'
                "<product/></extension></plugin>"),
            "duplicate": (
                '<plugin><extension id="product" '
                'point="org.eclipse.core.runtime.products">'
                "<product>"
                f'<property name="lifeCycleURI" '
                f'value="{self.LIFECYCLE_URI}"/>'
                f'<property name="lifeCycleURI" '
                f'value="{self.LIFECYCLE_URI}"/>'
                "</product></extension></plugin>"),
            "wrong": (
                '<plugin><extension id="product" '
                'point="org.eclipse.core.runtime.products">'
                "<product>"
                '<property name="lifeCycleURI" value="bundleclass://wrong"/>'
                "</product></extension></plugin>"),
        }

        for case, content in invalid_documents.items():
            with self.subTest(case=case):
                self._verify_lifecycle(content)

                self.assertEqual(1, len(VERIFY.errors), VERIFY.errors)
                self.assertIn("lifeCycleURI", VERIFY.errors[0])
                VERIFY.errors.clear()

    def test_lifecycle_property_rejects_malformed_xml(self):
        with self.assertRaises(ET.ParseError):
            self._verify_lifecycle("<plugin>")

    def test_lifecycle_property_rejects_decoy_when_real_product_is_missing_it(
            self):
        self._verify_lifecycle(
            '<plugin><extension id="product" '
            'point="org.eclipse.core.runtime.products"><product/></extension>'
            '<extension id="decoy" '
            'point="org.eclipse.core.runtime.products"><product>'
            f'<property name="lifeCycleURI" value="{self.LIFECYCLE_URI}"/>'
            "</product></extension></plugin>")

        self.assertEqual(1, len(VERIFY.errors), VERIFY.errors)
        self.assertIn("lifeCycleURI", VERIFY.errors[0])

    def test_lifecycle_property_rejects_foreign_namespace_lookalike(self):
        self._verify_lifecycle(
            '<plugin xmlns:x="urn:foreign"><x:extension id="product" '
            'point="org.eclipse.core.runtime.products"><x:product>'
            f'<x:property name="lifeCycleURI" value="{self.LIFECYCLE_URI}"/>'
            "</x:product></x:extension></plugin>")

        self.assertEqual(1, len(VERIFY.errors), VERIFY.errors)
        self.assertIn("lifeCycleURI", VERIFY.errors[0])

    def test_lifecycle_property_rejects_foreign_namespace_root(self):
        self._verify_lifecycle(
            '<x:plugin xmlns:x="urn:foreign"><extension id="product" '
            'point="org.eclipse.core.runtime.products"><product>'
            f'<property name="lifeCycleURI" value="{self.LIFECYCLE_URI}"/>'
            "</product></extension></x:plugin>")

        self.assertEqual(1, len(VERIFY.errors), VERIFY.errors)
        self.assertIn("lifeCycleURI", VERIFY.errors[0])

    def test_lifecycle_property_rejects_wrong_extension_id(self):
        self._verify_lifecycle(
            '<plugin><extension id="not-the-product" '
            'point="org.eclipse.core.runtime.products"><product>'
            f'<property name="lifeCycleURI" value="{self.LIFECYCLE_URI}"/>'
            "</product></extension></plugin>")

        self.assertEqual(1, len(VERIFY.errors), VERIFY.errors)
        self.assertIn("lifeCycleURI", VERIFY.errors[0])

    def test_lifecycle_property_rejects_additional_namespaced_occurrence(self):
        self._verify_lifecycle(
            '<plugin xmlns:x="urn:foreign"><extension id="product" '
            'point="org.eclipse.core.runtime.products"><product>'
            f'<property name="lifeCycleURI" value="{self.LIFECYCLE_URI}"/>'
            "</product></extension><x:property "
            f'name="lifeCycleURI" value="{self.LIFECYCLE_URI}"/>'
            "</plugin>")

        self.assertEqual(1, len(VERIFY.errors), VERIFY.errors)
        self.assertIn("lifeCycleURI", VERIFY.errors[0])

    def test_ui_jar_requires_both_workbench_classes(self):
        complete = self._ui_jar(
            self.PERSISTED_CLASS,
            self.LIFECYCLE_CLASS)
        self._verify_ui_jar(complete)
        self.assertEqual([], VERIFY.errors)

        VERIFY.errors.clear()
        incomplete = self._ui_jar(self.PERSISTED_CLASS)
        self._verify_ui_jar(incomplete)

        self.assertEqual(1, len(VERIFY.errors), VERIFY.errors)
        self.assertIn(
            "StandaloneWorkbenchLifecycle.class", VERIFY.errors[0])

    def test_ui_jar_rejects_required_entry_without_class_magic(self):
        invalid = self._ui_jar_entries({
            self.PERSISTED_CLASS: self.CLASS_MAGIC,
            self.LIFECYCLE_CLASS: b"not Java bytecode",
        })

        self._verify_ui_jar(invalid)

        self.assertEqual(1, len(VERIFY.errors), VERIFY.errors)
        self.assertIn(self.LIFECYCLE_CLASS, VERIFY.errors[0])
        self.assertIn("class magic", VERIFY.errors[0])

    def test_ui_jar_reads_required_entry_and_propagates_bad_crc(self):
        corrupt = self._corrupt_ui_jar()
        with zipfile.ZipFile(io.BytesIO(corrupt)) as jar:
            with self.assertRaises(zipfile.BadZipFile):
                jar.read(self.LIFECYCLE_CLASS)

        with zipfile.ZipFile(io.BytesIO(corrupt)) as jar:
            with self.assertRaises(zipfile.BadZipFile):
                VERIFY.verify_ui_workbench_classes(jar, "standalone UI")

        self.assertTrue(any("integrity" in error for error in VERIFY.errors),
                        VERIFY.errors)

    def test_plugin_inventory_rejects_unsupported_exact_prefixes(self):
        self._verify_plugin_inventory((
            "pgcodekeeper/plugins/org.eclipse.jdt.ui_3.35.0.jar",
            "pgcodekeeper/plugins/org.eclipse.mylyn.tasks.ui_4.3.0.jar",
        ))

        self.assertEqual(2, len(VERIFY.errors), VERIFY.errors)
        self.assertTrue(any("org.eclipse.jdt.ui_" in error
                            for error in VERIFY.errors))
        self.assertTrue(any("org.eclipse.mylyn.tasks.ui_" in error
                            for error in VERIFY.errors))

    def test_plugin_inventory_keeps_near_misses_and_other_folders(self):
        self._verify_plugin_inventory((
            "pgcodekeeper/plugins/org.eclipse.jdt.ui.tests_3.35.0.jar",
            "pgcodekeeper/plugins/org.eclipse.mylyn.tasks.ui.tests_4.3.0.jar",
            "pgcodekeeper/features/org.eclipse.jdt.ui_3.35.0/feature.xml",
            "other/plugins/org.eclipse.mylyn.tasks.ui_4.3.0.jar",
        ))

        self.assertEqual([], VERIFY.errors)

    def test_plugin_inventory_is_case_insensitive_on_windows(self):
        self._verify_plugin_inventory_case((
            "PGCODEKEEPER/PLUGINS/Org.Eclipse.Jdt.Ui_3.35.0.jar",
            "PGCODEKEEPER/PLUGINS/ORG.ECLIPSE.MYLYN.TASKS.UI_4.3.0.jar",
            "PGCODEKEEPER/PLUGINS/"
            "ORG.ECLIPSE.JDT.UI.TESTS_3.35.0.jar",
            "PGCODEKEEPER/PLUGINS/"
            "ORG.ECLIPSE.MYLYN.TASKS.UI.TESTS_4.3.0.jar",
        ), case_insensitive=True)

        self.assertEqual(2, len(VERIFY.errors), VERIFY.errors)

    def test_plugin_inventory_remains_case_sensitive_on_unix(self):
        self._verify_plugin_inventory_case((
            "pgcodekeeper/plugins/Org.Eclipse.Jdt.Ui_3.35.0.jar",
            "pgcodekeeper/plugins/ORG.ECLIPSE.MYLYN.TASKS.UI_4.3.0.jar",
        ), case_insensitive=False)

        self.assertEqual([], VERIFY.errors)

    def test_verify_sources_rejects_injected_decoy_lifecycle(self):
        VERIFY.verify_sources(standalone_plugin_xml=(
            '<plugin><extension id="product" '
            'point="org.eclipse.core.runtime.products"><product/></extension>'
            '<extension id="decoy" '
            'point="org.eclipse.core.runtime.products"><product>'
            f'<property name="lifeCycleURI" value="{self.LIFECYCLE_URI}"/>'
            "</product></extension></plugin>").encode())

        lifecycle_errors = [
            error for error in VERIFY.errors if "lifeCycleURI" in error]
        self.assertEqual(1, len(lifecycle_errors), VERIFY.errors)

    def test_verify_product_checks_linux_workbench_payload(self):
        plugin_xml = (
            '<plugin><extension id="product" '
            'point="org.eclipse.core.runtime.products"><product/></extension>'
            '<extension id="decoy" '
            'point="org.eclipse.core.runtime.products"><product>'
            f'<property name="lifeCycleURI" value="{self.LIFECYCLE_URI}"/>'
            "</product></extension></plugin>").encode()

        self._verify_product_fixture(
            "linux-product.zip",
            VERIFY.PRODUCTS["linux.gtk.x86_64.tar.gz"],
            "pgcodekeeper/plugins/",
            ui_classes=(
                "ru/taximaxim/codekeeper/ui/workbench/"
                "StandaloneWorkbenchLifecycle.class",),
            plugin_xml=plugin_xml,
            forbidden_bundle="org.eclipse.jdt.ui_3.35.0.jar")

        self._assert_error_contains(
            "linux-product.zip", "PersistedWorkbenchModelMigration.class")
        self._assert_no_error_contains(
            "linux-product.zip", "StandaloneWorkbenchLifecycle.class",
            "is missing")
        self._assert_error_contains(
            "linux-product.zip", "lifeCycleURI")
        self._assert_error_contains(
            "linux-product.zip", "org.eclipse.jdt.ui_")
        self._assert_no_error_contains(
            "cannot inspect linux-product.zip")

    def test_verify_product_checks_macos_workbench_payload(self):
        plugin_xml = (
            '<plugin><extension id="product" '
            'point="org.eclipse.core.runtime.products"><product>'
            '<property name="lifeCycleURI" value="bundleclass://wrong"/>'
            "</product></extension></plugin>").encode()

        self._verify_product_fixture(
            "mac-product.zip",
            VERIFY.PRODUCTS["macosx.cocoa.aarch64.tar.gz"],
            "pgCodeKeeper.app/Contents/Eclipse/plugins/",
            ui_classes=(
                "ru/taximaxim/codekeeper/ui/workbench/"
                "PersistedWorkbenchModelMigration.class",),
            plugin_xml=plugin_xml,
            forbidden_bundle="org.eclipse.mylyn.tasks.ui_4.3.0.jar")

        self._assert_error_contains(
            "mac-product.zip", "StandaloneWorkbenchLifecycle.class")
        self._assert_no_error_contains(
            "mac-product.zip", "PersistedWorkbenchModelMigration.class",
            "is missing")
        self._assert_error_contains(
            "mac-product.zip", "lifeCycleURI")
        self._assert_error_contains(
            "mac-product.zip", "org.eclipse.mylyn.tasks.ui_")
        self._assert_no_error_contains(
            "cannot inspect mac-product.zip")

    def test_verify_product_uses_windows_case_insensitive_inventory(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "windows-product.zip"
            with zipfile.ZipFile(path, "w") as archive:
                archive.writestr(
                    "pgcodekeeper/plugins/"
                    "Org.Eclipse.Jdt.Ui_3.35.0.jar",
                    b"bundle")

            VERIFY.verify_product(
                path, VERIFY.PRODUCTS["win32.win32.x86_64.zip"])

        self._assert_error_contains(
            "windows-product.zip", "Org.Eclipse.Jdt.Ui_3.35.0.jar")

    def test_verify_product_rejects_ui_class_without_magic(self):
        invalid = self._ui_jar_entries({
            self.PERSISTED_CLASS: self.CLASS_MAGIC,
            self.LIFECYCLE_CLASS: b"not Java bytecode",
        })
        self._verify_product_fixture(
            "invalid-class-product.zip",
            VERIFY.PRODUCTS["linux.gtk.x86_64.tar.gz"],
            "pgcodekeeper/plugins/",
            ui_jar=invalid,
            plugin_xml=self._valid_plugin_xml())

        self._assert_error_contains(
            "invalid-class-product.zip", self.LIFECYCLE_CLASS, "class magic")

    def test_verify_product_reports_corrupt_ui_class_crc(self):
        self._verify_product_fixture(
            "corrupt-class-product.zip",
            VERIFY.PRODUCTS["linux.gtk.x86_64.tar.gz"],
            "pgcodekeeper/plugins/",
            ui_jar=self._corrupt_ui_jar(),
            plugin_xml=self._valid_plugin_xml())

        self._assert_error_contains(
            "corrupt-class-product.zip", "integrity", self.LIFECYCLE_CLASS)
        self._assert_error_contains(
            "cannot inspect corrupt-class-product.zip", "Bad CRC-32")

    def test_updatesite_rejects_a_stale_ui_jar(self):
        self._verify_updatesite_fixture(
            ui_jar=self._ui_jar(self.PERSISTED_CLASS),
            plugin_xml=self._valid_plugin_xml())

        self._assert_error_contains(
            "standalone UI", self.LIFECYCLE_CLASS, "missing")

    def test_updatesite_rejects_a_stale_mainapp_jar(self):
        self._verify_updatesite_fixture(
            ui_jar=self._ui_jar(self.PERSISTED_CLASS, self.LIFECYCLE_CLASS),
            plugin_xml=(
                '<plugin><extension id="product" '
                'point="org.eclipse.core.runtime.products"><product>'
                '<property name="lifeCycleURI" value="bundleclass://wrong"/>'
                "</product></extension></plugin>").encode())

        self._assert_error_contains("standalone plugin.xml", "lifeCycleURI")

    def test_updatesite_rejects_a_ui_class_without_magic(self):
        self._verify_updatesite_fixture(
            ui_jar=self._ui_jar_entries({
                self.PERSISTED_CLASS: self.CLASS_MAGIC,
                self.LIFECYCLE_CLASS: b"not Java bytecode",
            }),
            plugin_xml=self._valid_plugin_xml())

        self._assert_error_contains(
            "standalone UI", self.LIFECYCLE_CLASS, "class magic")

    def test_updatesite_accepts_a_complete_payload(self):
        self._verify_updatesite_fixture(
            ui_jar=self._ui_jar(self.PERSISTED_CLASS, self.LIFECYCLE_CLASS),
            plugin_xml=self._valid_plugin_xml())

        self.assertEqual([], VERIFY.errors)

    def _verify_updatesite_fixture(self, *, ui_jar, plugin_xml):
        version = VERIFY.OSGI_VERSION
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            name = f"pgCodeKeeper-updatesite-{VERIFY.MAVEN_VERSION}.zip"
            with zipfile.ZipFile(directory / name, "w") as archive:
                archive.writestr("p2.index", b"version = 1")
                archive.writestr("content.jar", b"content")
                archive.writestr("artifacts.jar", b"artifacts")
                archive.writestr(
                    "plugins/org.pgcodekeeper.core_"
                    f"{VERIFY.CORE_OSGI_VERSION}.jar",
                    self._core_bundle())
                archive.writestr(
                    f"plugins/ru.taximaxim.codekeeper.ui_{version}.jar",
                    ui_jar)
                archive.writestr(
                    f"plugins/ru.taximaxim.codekeeper.mainapp_{version}.jar",
                    self._mainapp_jar(plugin_xml))
                archive.writestr(
                    f"features/ru.taximaxim.codekeeper.feature_{version}.jar",
                    self._feature_jar())

            VERIFY.verify_updatesite(directory)

    @staticmethod
    def _core_bundle():
        output = io.BytesIO()
        with zipfile.ZipFile(output, "w") as jar:
            jar.writestr("META-INF/MANIFEST.MF", ExactLineTest._core_manifest())
            jar.writestr(
                "org/pgcodekeeper/core/settings/AbstractSettings.class",
                b"setAdditionalExcludedSchemas")
            jar.writestr(
                "org/pgcodekeeper/core/settings/ISettings.class",
                b"isAdditionalSchemaExcluded")
            jar.writestr(
                "org/pgcodekeeper/core/database/pg/jdbc/"
                "PgCatalogReaderPackStore.class", b"packed cache")
            jar.writestr(
                "org/pgcodekeeper/core/database/api/loader/"
                "ProjectInputFingerprint.class", b"input fingerprint")
            jar.writestr(
                "org/pgcodekeeper/core/database/api/loader/"
                "IProjectInputFingerprintCapture.class",
                b"enableInputFingerprintCapture getCapturedInputFingerprints")
        return output.getvalue()

    @staticmethod
    def _feature_jar():
        output = io.BytesIO()
        with zipfile.ZipFile(output, "w") as jar:
            jar.writestr(
                "feature.xml",
                f'<feature version="{VERIFY.OSGI_VERSION}"/>')
        return output.getvalue()

    def _verify_product_fixture(
            self, filename, spec, plugin_folder, *, ui_classes=(),
            ui_jar=None, plugin_xml, forbidden_bundle=None):
        if ui_jar is None:
            ui_jar = self._ui_jar(*ui_classes)
        mainapp_jar = self._mainapp_jar(plugin_xml)
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / filename
            with zipfile.ZipFile(path, "w") as archive:
                archive.writestr(
                    f"{plugin_folder}ru.taximaxim.codekeeper.ui_"
                    f"{VERIFY.OSGI_VERSION}.jar",
                    ui_jar)
                archive.writestr(
                    f"{plugin_folder}ru.taximaxim.codekeeper.mainapp_"
                    f"{VERIFY.OSGI_VERSION}.jar",
                    mainapp_jar)
                if forbidden_bundle is not None:
                    archive.writestr(
                        f"{plugin_folder}{forbidden_bundle}", b"bundle")

            VERIFY.verify_product(path, spec)

    def _assert_error_contains(self, *tokens):
        self.assertTrue(
            any(all(token in error for token in tokens)
                for error in VERIFY.errors),
            VERIFY.errors)

    def _assert_no_error_contains(self, *tokens):
        self.assertFalse(
            any(all(token in error for token in tokens)
                for error in VERIFY.errors),
            VERIFY.errors)

    @staticmethod
    def _ui_jar(*class_names):
        return StandaloneWorkbenchPackagingTest._ui_jar_entries({
            class_name: StandaloneWorkbenchPackagingTest.CLASS_MAGIC
            for class_name in class_names
        })

    @staticmethod
    def _ui_jar_entries(entries):
        output = io.BytesIO()
        with zipfile.ZipFile(output, "w") as jar:
            for class_name, class_bytes in entries.items():
                jar.writestr(class_name, class_bytes)
        return output.getvalue()

    @classmethod
    def _corrupt_ui_jar(cls):
        lifecycle_bytes = cls.CLASS_MAGIC + b"LIFECYCLE"
        archive = cls._ui_jar_entries({
            cls.PERSISTED_CLASS: cls.CLASS_MAGIC + b"PERSISTED",
            cls.LIFECYCLE_CLASS: lifecycle_bytes,
        })
        replacement = cls.CLASS_MAGIC + b"CORRUPTED"
        cls._assert_equal_length(lifecycle_bytes, replacement)
        return archive.replace(lifecycle_bytes, replacement, 1)

    @staticmethod
    def _assert_equal_length(left, right):
        if len(left) != len(right):
            raise AssertionError(
                f"CRC fixture payload lengths differ: {len(left)} != "
                f"{len(right)}")

    @classmethod
    def _valid_plugin_xml(cls):
        return (
            '<plugin><extension id="product" '
            'point="org.eclipse.core.runtime.products"><product>'
            f'<property name="lifeCycleURI" value="{cls.LIFECYCLE_URI}"/>'
            "</product></extension></plugin>").encode()

    @staticmethod
    def _mainapp_jar(plugin_xml):
        customization = "org.eclipse.core.resources/refresh.enabled=true\n"
        output = io.BytesIO()
        with zipfile.ZipFile(output, "w") as jar:
            jar.writestr("plugin_customization.ini", customization)
            jar.writestr("plugin.xml", plugin_xml)
        return output.getvalue()


if __name__ == "__main__":
    unittest.main()
