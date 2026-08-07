[![Apache 2.0](https://img.shields.io/github/license/pgcodekeeper/pgcodekeeper.svg)](http://www.apache.org/licenses/LICENSE-2.0)

# pgCodeKeeper

A tool for easier PostgreSQL development.

* Comparing database code is very easy now. Compare live DB instances, pg_dump output, as well as pgCodeKeeper projects.
* Generate migration scripts via a user-friendly interface. You can use both live DB instances and DB dumps as initial data. You can also compare pgCodeKeeper projects — useful when working with versions control systems.
* pgCodeKeeper is an [Eclipse IDE](http://www.eclipse.org) plug-in. The DB code is saved as an Eclipse project, and the project can be tracked under any of the versions control systems supported by Eclipse — Git, SVN, Mercurial, CVS and many others.

<a href="https://pgcodekeeper.org/github-images/main-view.png"><img src="https://pgcodekeeper.org/github-images/main-view.png" width="450"/></a>
<a href="https://pgcodekeeper.org/github-images/sql-editor.png"><img src="https://pgcodekeeper.org/github-images/sql-editor.png" width="450"></a>

## Download

pgCodeKeeper requires Java (JRE) 21+ to run.

The standalone archives include a Java 21 runtime and do not require a
separate Java installation.

Use a standalone `neo` archive from this repository's release artifacts. To
install into an existing Eclipse IDE, use the locally built
`pgCodeKeeper-updatesite-<release-version>.zip`. The public Marketplace and upstream
update site distribute the upstream plugin without the `neo` optimizations.

### Low-traffic PostgreSQL comparisons

Persistent PostgreSQL catalog row caching is enabled by default in the Eclipse
plugin to minimize the network traffic and the number of round trips repeated
comparisons need. That is what dominates the wall-clock time of a comparison
over a slow or high-latency link, a VPN among them. The first comparison
populates the local cache and may take longer. The cache survives application
restarts when the same Eclipse workspace is used. With a valid warm cache,
later comparisons normally transfer a compact hash list and only catalog rows
that are new or changed. Cache errors or an excessive miss ratio safely fall
back to loading the full catalog rows.

Parser worker defaults scale with the machine and are identical in the
standalone application and in the plug-in installed into another Eclipse:
half of the available processors, never fewer than two and never more than
eight. On the twelve-CPU machine used for the measurements that is six workers
for PostgreSQL Get Changes and six for project indexing, which is exactly the
tuning the standalone product used to hard-code; eight workers were slower in
that workload. The standalone JVM starts with a 256 MiB heap and may grow up to
the 4 GiB `-Xmx` cap; the cap does not eagerly allocate 4 GiB.

The cache may be disabled in `Window -> Preferences -> pgCodeKeeper` or in the
overriding project properties. This Eclipse default does not change the generic
Core and CLI defaults.

The same preference page shows the persistent catalog cache folder and its
size on disk, and clears it on demand. The size is measured in the background,
and clearing removes only pgCodeKeeper's own per-database cache folders, under
the same lock the automatic maintenance uses, so it is safe while another
workspace is running.

### Where a setting comes from

Three levels are read, from the weakest to the strongest:

1. **Plug-in defaults** - computed in `PreferenceInitializer`, shared by every
   deployment. All performance tuning lives here.
2. **Workspace preferences** - `Window -> Preferences -> pgCodeKeeper`. A value
   entered here wins over the default for every project in the workspace.
3. **Project preferences** - `Project -> Properties -> pgCodeKeeper`, stored in
   the project's own `.settings/ru.taximaxim.codekeeper.ui.prefs`. They are
   ignored until *Enable project settings* is checked in those properties
   (`prefEnableProjPrefRoot=true`); once enabled, every key present in that file
   wins over the workspace value, and keys that are absent keep inheriting it.

The standalone product's `plugin_customization.ini` is deliberately not a fourth
level for pgCodeKeeper tuning. Product customization outranks the plug-in
defaults, so a value placed there would silently apply to the standalone
application only - which is exactly how the tuning used to miss everyone running
pgCodeKeeper inside their own Eclipse. The file now carries only
`org.eclipse.core.resources/refresh.enabled`, a workspace-wide switch of another
bundle that the standalone product may set for its own workspace but a plug-in
must not set inside somebody else's IDE.

Project-specific settings belong in the project file, not in the defaults. The
schema exclusion list is the typical case: a scratch schema that one DDL
repository generates must not be excluded from indexing for every other user.
Committing `.settings/ru.taximaxim.codekeeper.ui.prefs` with
`prefEnableProjPrefRoot=true` and `projectIndexExcludedSchemas=<schema>` gives
the whole team the same setting, and because the resolved exclusion list is part
of the project-index identity, every checkout that receives the file rebuilds
its index once and then converges.

The standalone application also persists its packed background project index
inside the Eclipse workspace. After one successful full build, the same
workspace can reopen that index after an application restart and reparse only a
safe single-file change. Workspace auto-refresh is enabled so changes made by
Git or external editors reach the incremental builder. Unsupported layouts,
stale inputs, analysis errors, or damaged cache data fall back to a full build.

Index publication is atomic and checksum covered on every platform. Windows
does not expose directory handles to Java, so the directory entry of a freshly
published index cannot be forced to disk there. After an operating-system
crash or power loss on Windows the workspace may therefore rebuild the project
index from scratch on the next start. That is expected behavior, not a damaged
index: a partially published generation can never be opened.

## Documentation

* [User manual](https://pgcodekeeper.readthedocs.io/en/latest/)
* [Issue tracker](https://github.com/pgcodekeeper/pgcodekeeper/issues)

## Build

Build requires Java (JDK) 21+ and Apache Maven 3.9+. The optimized Eclipse
bundle uses Core `15.2.0-neo1`, so install that artifact first.

```sh
MAVEN_REPO="$(mktemp -d)"

git clone --branch neo https://github.com/Fenoman/pgcodekeeper-core.git
cd pgcodekeeper-core
git checkout c106361b9223cac67f66785f0c3ff6cd9fe21c2a
mvn -B -ntp clean install -DskipTests -Dmaven.repo.local="$MAVEN_REPO"
cd ..

git clone --branch neo https://github.com/Fenoman/pgcodekeeper.git
cd pgcodekeeper
mvn -B -ntp clean verify -DskipTests -Dmaven.repo.local="$MAVEN_REPO"
```

CI pins Core to immutable commit
`c106361b9223cac67f66785f0c3ff6cd9fe21c2a`; changing the `neo` branch does
not silently change a plugin build. Standalone archives for Linux, Windows,
macOS x86_64, and macOS aarch64 are created in
`ru.taximaxim.codekeeper.mainapp/product/rcp/target/products`.

## Notes

- If you have any questions, suggestions, ideas, etc - contact us in our [Telegram chat](https://t.me/pgcodekeeper) or create an issue.
- Pull requests are welcome.
- Visit https://pgcodekeeper.org for more information.
- Thanks for using pgCodeKeeper!

## Contributing

### Project Structure

The project consists of several modules that implement Eclipse RCP plugins (and, by extension, OSGi bundles).

#### Main Modules

- `pom.xml` - first, root pom.xml defines Maven build order and environment. Not required for application development.
- `ru.taximaxim.codekeeper.ui` - GUI module, Eclipse integration.

#### Test Modules

- `ru.taximaxim.codekeeper.ui.tests` - tests for pieces of logic that were implemented in UI module instead of the core module.

#### Misc Modules

- `ru.taximaxim.codekeeper.mainapp` - "branding plugin", provides Eclipse with feature information, images, etc. Also contains development Target platform files.
- `ru.taximaxim.codekeeper.mainapp/report` - tests coverage report module.
- `ru.taximaxim.codekeeper.mainapp/feature` - Eclipse feature module.
- `ru.taximaxim.codekeeper.mainapp/updatesite` - Eclipse p2 repository/update site module.
- `ru.taximaxim.codekeeper.mainapp/product/rcp` - Eclipse product description, used to build pgCodeKeeper packages.
- `ru.taximaxim.codekeeper.mainapp/deploy` - GitHub deploy module.

### Module Contents

#### ru.taximaxim.codekeeper.ui

Majority of code in this module implements Eclipse integration, and also wraps core logic into a more UI-suitable forms.

- `plugin.xml` - main Eclipse integration file. All integration code is referenced from here.
- `ru.taximaxim.codekeeper.ui.editors` - main project editor and its supporting classes. Notable classes:
  - `ProjectEditorDiffer` - main project editor. Loads and compares databases, allows user to select changes and saves the into project or generates and diff script for the database.
- `ru.taximaxim.codekeeper.ui.differ` - classes for diff creation. Notable classes:
  - `DbSource` - an abstraction, factory, and implementations for various database loaders.
  - `TreeDiffer`, `Differ` - first creates a diff tree, second creates a diff script based on the tree and user selection.
  - `DiffTableViewer` - GUI element responsible for showing tree diff elements. Part of `ProjectEditorDiffer` separated for reuse in other UIs.
- `ru.taximaxim.codekeeper.ui.pgdbproject` - project classes and various project wizards (New, Import, etc). Notable classes:
  - `PgDbProject` - utility wrapper for Eclipse's `IProject`.
- `ru.taximaxim.codekeeper.ui.pgdbproject.parser` - UI-specific parts of database loaders. Notable classes:
  - `PgDbParser` - stores, serializes and updates project indices (object metadata and references).
  - `UIProjectLoader` - loads a database from project structure in Eclipse Workspace. **(!)** Keep in sync with `ProjectLoader`.
  - `PgUIDumpLoader` - loads objects into a database structure from an Eclipse's `IFile`. **(!)** Keep in sync with `PgDumpLoader`.
- `ru.taximaxim.codekeeper.ui.builders` - Project Builders launch project index updates (metadata, errors etc) when an IDE build is triggered.
- `ru.taximaxim.codekeeper.ui.sqledit` - SQL code editor. Notable classes:
  - `SQLEditorSourceViewerConfiguration` - main editor configuration class, ties in most other editor classes.
- `ru.taximaxim.codekeeper.ui.dbstore` - database connection storage and GUI selection classes. Notable classes:
  - `DbInfo` - database connection parameters.
- `ru.taximaxim.codekeeper.ui.handlers` - handlers for commands (IDE actions) registered with Eclipse in `plugin.xml`.
- `ru.taximaxim.codekeeper.ui.job` - singleton wrapper for Eclipse `Job`s.
- `ru.taximaxim.codekeeper.ui.dialogs` - various dialogs. Notable classes:
  - `ExceptionNotifier` - reports and logs errors using Eclipse's `StatusManager`.
- `ru.taximaxim.codekeeper.ui.externalcalls` - logic for calling external utilities (pg_dump etc).
- `ru.taximaxim.codekeeper.ui.generators` - value generator for dummy data generation wizard (`MockDataWizard`).
- `ru.taximaxim.codekeeper.ui.prefs` - Eclipse Preferences integration - global program settings.
- `ru.taximaxim.codekeeper.ui.properties` - per-project settings.
- `ru.taximaxim.codekeeper.ui.reports` - Google Analytics reports.
- `ru.taximaxim.codekeeper.ui.views` - views showing misc info. Notable classes:
  - `DepcyGraphView` - shows dependency graph for objects currently selected in Project Editor.
  - `ProjectOverrideView` - shows library objects overridden by the project in current Project Editor.
  - `ResultSetView` - shows rows returned by SELECT or other commands executed in a script in SQL Editor.
- `ru.taximaxim.codekeeper.ui.views.navigator` - Eclipse Project Explorer integration, see also `plugin.xml`
- `ru.taximaxim.codekeeper.ui` - main package containing general stuff: e.g. string constants, utils. Notable classes:
  - `UiSync` - exception safety wrapper around SWT's `Display.asyncExec()`. **(!)** Use this instead of calling `getDisplay().asyncExec()`.
  - `UIConsts` - majority of string IDs and other constants commonly used in UI.

#### ru.taximaxim.codekeeper.ui.tests

Majority of tests are here.

- `ru.taximaxim.codekeeper.ui.dbstore` - these test cases are generated url from the assumed state of DbInfo and the obtained result is compared with the expected one.
- `ru.taximaxim.codekeeper.ui.differ.filters` - these test cases check the operation of filters.
- `ru.taximaxim.codekeeper.ui.generators` - these test cases check the data generation.
- `ru.taximaxim.codekeeper.ui.pgdbproject.parser` - these test cases check deserializing.
- `ru.taximaxim.codekeeper.ui.reports` - these test cases check what returns UsageEvent.

Simple tests for core logic wrappers: `DbSource`, `Differ`, `ProjectUpdater`.

### Program Lifecycle

General program lifecycle goes as follows:
1. `ISettings` object is filled with operation parameters.
2. `AbstractDatabase`s are loaded from requested sources, including their libraries and privilege overrides. Ignored schemas are skipped at this step.
   1. During the load dependencies of each object are found and recorded. Expressions are also analyzed to extract their dependencies including overloaded function calls.
   2. All parser and expression analysis operations are run in parallel using `AntlrParser.ANTLR_POOL` thread pool to speed up the process. Parallel operations are serialized by calling `finishLoaders` at the end of each loading process.
3. The diff tree (represented by root `TreeElement`) is created by comparing two `AbstractDatabase`s. In GUI this is then shown to the user to assess the situation and choose objects to work with.
4. The diff tree, now containing "user selection", is used to selectively update project files on disk, or to generate a migration script.
5. In latter case, each "selected" TreeElement is passed to `DepcyResolver` to generate script actions fulfilling the requested change, including actions on dependent objects. To do this, JGraphT object dependency graphs are built using dependency information found at the loading stage.
6. Generated actions are now converted into SQL code with some last-moment post-processing and filtering.
7. Generated SQL script is written to a file/stdout or shown in the GUI for user to review and run on their database.
