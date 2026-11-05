# Releasing the Haxe IntelliJ Plugin (InnoGames fork)

The fork is released from `innogames/intellij-haxe` through a **custom plugin repository** on GitHub Pages, not the JetBrains Marketplace. Pushing a `v*` tag runs `.github/workflows/release.yml`, which builds the plugin, publishes a GitHub Release with the ZIP attached, and deploys `https://innogames.github.io/intellij-haxe/updatePlugins.xml`, the URL colleagues add in IntelliJ.

> **Note:** `release.yaml` (triggered by `release-X.Y.Z` tags) is the upstream marketplace flow and is unrelated. Fork releases use `v*` tags only.

## Plugin id and versioning

The fork is its own plugin: id `com.innogames.intellij.plugins.haxe`, name "Haxe Toolkit Support (InnoGames)". It declares `<incompatible-with>com.intellij.plugins.haxe</incompatible-with>`, because both register the same Haxe file types and extensions.

Because the id differs from the marketplace plugin's, a marketplace release is never offered as an update to the fork, so the version only has to sort correctly against earlier fork releases:

- **Scheme:** `<upstream version>.<n>`, all numeric, e.g. `1.8.6.1`, `1.8.6.2`.
- Bump `<n>` for fork-only changes. After merging a new upstream release, restart at `.1` (`1.8.7.1`).
- Keep it numeric. A word suffix (`-fork.1`, `-inno.1`) may be ranked as a pre-release by IntelliJ's version comparison.
- Never reuse a version or a tag. If a release fails partway, bump and tag again.

Releases up to `999.1.8.6-fork.4` used the marketplace id with a `999.` prefix to sort above upstream. IntelliJ does not offer `1.8.6.1` as an update to those, since it is a different plugin: users uninstall "Haxe Toolkit Support" once and install "Haxe Toolkit Support (InnoGames)".

## One-time repository configuration

Already done for `innogames/intellij-haxe`. Needed again only for a new repository:

1. **Pages:** Settings → Pages → Source: **GitHub Actions**. Ignore the suggested starter workflows; `release.yml` is the workflow.
2. **`github-pages` environment:** it appears after the first deploy attempt. Under Settings → Environments → `github-pages` → Deployment branches and tags, allow only the **tag** pattern `v*`, with no branch rule. The version is read from the tag name, so a run from a branch would publish a version named after the branch.
3. **Default branch:** `develop`.

## Cutting a release

1. On a branch off `develop`, bump `pluginVersion` in `gradle.properties`, put the release notes under `## Unreleased` in `CHANGELOG.md`, and run `./gradlew patchChangelog`. Check the new version section afterwards: it should contain only this release's items.
2. `./gradlew clean build verifyPlugin` must pass.
3. Commit (`Release <version>`), fast-forward `develop` to it and push `develop`.
4. Tag that commit and push **only that tag**, by name:
   ```
   git tag v1.8.6.1
   git push <org remote> v1.8.6.1
   ```
   Never `--tags` or `--follow-tags`: local clones carry old tags from the personal forks.
5. Watch **Actions → Release plugin**. On success the GitHub Release has the ZIP and `updatePlugins.xml` offers the new version.

The workflow fails early if the tag does not match `pluginVersion`.

**Re-running a failed release:** start a new run with **Run workflow**, choosing the tag under **Tags**. Don't use "Re-run jobs" on a run that already uploaded the Pages artifact: the re-run uploads a second one and `deploy-pages` fails with `Multiple artifacts named "github-pages"`.

## Verification

```
curl -s https://innogames.github.io/intellij-haxe/updatePlugins.xml
curl -fsSLI "$(curl -s https://innogames.github.io/intellij-haxe/updatePlugins.xml | sed -nE 's/.*url="([^"]+)".*/\1/p')" | head -1
```

The first shows the offered id and version, the second should end in `200`. Then in IntelliJ: Settings → Plugins → ⚙ → Check for Updates.

## Gotchas

- **First Pages deploy** can take 5–10 minutes to go live.
- **`since-build` / `until-build`** come from `gradle.properties` via `patchPluginXml`. The workflow reads id, name, version and build range from `build/tmp/patchPluginXml/plugin.xml`, so `updatePlugins.xml` always matches the ZIP.
- **The ZIP name** is discovered by the workflow. Don't hardcode it.
- **Don't add `paths:` filters** to the tag trigger.
- **Gradle cache:** `gradle/actions/setup-gradle@v4` caches by default. On a build that is flaky from a stale cache, set `cache-disabled: true` temporarily.
