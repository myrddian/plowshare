# Client appearance assets

The console and Desktop copy these assets into their own bundles. `themes/print.css`
owns the mid-century print palette and type families; `fonts/` contains the
unmodified, licensed fonts and their notices. Clients own layout and semantic
state colours. Fonts remain same-origin and require no external font service.

Both build owners declare this directory as an input. Edit sources here, rather
than either client's generated bundle. The website uses the same public palette
and typefaces; its deployment and content are owned separately.
