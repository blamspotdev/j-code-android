# JCode Marketplace

First-party, submodule-ready packages bundled with the app. Today this holds the
**project template** extension; the directory layout is intentionally flat so each
`templates/<id>/` can later be promoted to its own git submodule with no path churn.

```
marketplace/
  extension.yaml                 # extension descriptor (id, name, version, template ids)
  templates/
    empty/template.yaml
    aspnet-vite-react-ts/template.yaml
    react-app/template.yaml
    angular-aspnet/template.yaml
```

## How it ships

The `:feature:marketplace` module bundles this directory as Android assets via an
external `assets.srcDir("../../marketplace")`. AGP merges every module's assets into
one tree, so `extension.yaml` and `templates/<id>/template.yaml` resolve at runtime
through the app's `AssetManager`. Keep new asset files namespaced under `templates/`
to avoid collisions with other modules' assets (e.g. `distro/catalog.yaml`).

## `template.yaml` schema

```yaml
id: <stable id, matches the directory name>
name: <display name>
description: <one or two lines>
requires:                        # toolchain ids from core/distro catalog.yaml
  - dotnet
  - nodejs
recipe:                          # ordered steps run on-device at create time
  - label: <progress label>
    workdir: "{{hostStaging}}"   # optional; cwd for this step (placeholders resolved)
    run: <shell snippet>
```

Placeholders resolved before each step runs:

- `{{name}}` — the sanitized project name.
- `{{projectDir}}` — the project root inside the runtime (e.g. `/workspace/<name>`).
- `{{hostStaging}}` — a scratch dir on the runtime's ext4 home
  (`$HOME/.jcode-staging/<name>`), recreated fresh before the recipe runs.

An empty `recipe:` means "just create the folder" (the `empty` template).

## The FUSE / `node_modules` wart

`/workspace` is backed by Android emulated storage (FUSE), which has **no symlink
support**, so a runnable `node_modules` cannot live there. Front-end package managers
therefore run in `{{hostStaging}}` (ext4) and only the build output / editable source
is copied into `{{projectDir}}`. For front-end-rooted templates (React App, Angular)
the dev loop (`npm run dev`) must run from an ext4 staged copy — a documented v1
limitation, not solved here.
