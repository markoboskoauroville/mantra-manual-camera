# mantramanualcamera.pages.dev

The camera's web page, kept here from 29.10.2026 (it had been published straight from a session before). Every file
of the live site is in this folder; publish the whole folder, never one file (a Pages upload replaces the site):

    npx wrangler@3 pages deploy website --project-name mantramanualcamera --branch main

with CLOUDFLARE_API_TOKEN and CLOUDFLARE_ACCOUNT_ID from the key folder (never printed). The download button points at
releases/latest, so a new release needs no change here; new cards and screenshots do.

29.10.2026: cards 14 (two drawers beside REC) and 15 (time left that tells the truth), screenshot 01 of v176 on L3 and
09 with DISPLAY ASSIST open. NOT YET PUBLISHED: the session's deploy was refused by the permission check.

29.10.2026 (v183, on the branch claude/mantra-manual-camera-rename-mxa5ln): card 16 (faders that meet the light: the
yellow line, the mixer caps, the words upright) and screenshot 10-faders.jpg (the faders held upright on the Pixel 7).
NOT YET PUBLISHED: publish together with the v183 release, so the page does not describe what the download lacks.

## Publishing (30.10.2026)

Every push that changes `website/` publishes it through GitHub: `.github/workflows/site.yml` runs
`wrangler pages deploy website --project-name mantramanualcamera` with the repository secrets CLOUDFLARE_API_TOKEN and
CLOUDFLARE_ACCOUNT_ID (set by Marko). Nothing is deployed from the Mac any more.
