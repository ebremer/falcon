# Falcon documentation site

This folder is Falcon's documentation website, published by GitHub Pages at
<https://ebremer.github.io/falcon/>. Read it there, or read the Markdown pages here: the links between them
work in both places.

| Page | |
|---|---|
| [index.md](index.md) | Home |
| [getting-started.md](getting-started.md) | Build Falcon, add it to a project, read a first file, install the command |
| [hdf5.md](hdf5.md) | HDF5 how-to |
| [zarr.md](zarr.md) | Zarr how-to |
| [ome-zarr.md](ome-zarr.md) | OME-Zarr how-to |
| [cloud.md](cloud.md) | S3 and HTTP |
| [cli.md](cli.md) | The falcon command |
| [reference.md](reference.md) | API at a glance |
| [troubleshooting.md](troubleshooting.md) | Troubleshooting |
| [testing.md](testing.md) | Testing and conformance |
| [development.md](development.md) | Working on Falcon |

## How the site is built

GitHub Pages builds it with Jekyll, which turns each page's Markdown into HTML inside one layout:

```
docs/
├── _config.yml              site settings: title, base URL, Markdown options
├── _data/navigation.yml     the sidebar, in order
├── _layouts/default.html    the page layout: header, sidebar, content, footer
├── assets/css/style.css     the stylesheet, light and dark
├── assets/images/falcon.svg the icon
└── *.md                     the pages
```

It uses no theme or plugin beyond what GitHub Pages provides (`jekyll-relative-links`, which turns links to
`.md` files into links to their pages). This README is left out of the site.

## Publishing it

Once, on GitHub: **Settings → Pages → Build and deployment → Source: Deploy from a branch**, then branch
**main** and folder **/docs**, and **Save**. GitHub builds the site on every push to `main`; the repository's
**Actions** tab shows each build ("pages build and deployment"), and its log says what failed if one does.

## Changing it

- **A page:** edit its `.md` file. Each starts with front matter giving its `title` (the page's heading) and
  `description`.
- **A new page:** add `<name>.md` with that front matter, and an entry in `_data/navigation.yml`.
- **Links:** link to another page by its file, `[Zarr](zarr.md)` or `[Zarr](zarr.md#writing)`; to the rest of
  the repository by its full GitHub URL (`https://github.com/ebremer/falcon/blob/main/...`).
- **Code samples:** never put two opening braces in a row, or an opening brace and a percent sign, in a page:
  Jekyll reads them as template tags, even in code. Write Java's arrays of arrays with a space:
  `new long[][] { {0, 0}, {1, 1} }`.

## Previewing it

With Ruby installed:

```bash
gem install jekyll jekyll-relative-links kramdown-parser-gfm
jekyll serve --source docs --baseurl ""
```

Then open <http://localhost:4000>. GitHub Pages uses its own versions of Jekyll and its plugins, so the
published site can differ from a preview in small ways.
