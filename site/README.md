# The page: mantramanualcamera.pages.dev

The product page of Mantra Manual Camera (Marko, 26.10.2026: "If the page for the Android camera I'm developing is
still not available, you need to make it ... make it the same as my other products"). Built on the look of
`sony_remote_controller/site/public` (candle language, MANTRA_MANIFEST language-candle.md), its donation bowl included.
Features from the repository's README and WHERE_WE_ARE; the download button points at the latest GitHub release.

Pictures: `img/hero.jpg`, `img/rack-tablet.jpg` from Nano Banana in Marko's Gemini (made by the Mac session), and the
app's own screenshots in `img/screens/01-camera.jpg … 08-remote.jpg`. A missing picture shows "photograph in the
making"; a missing screenshot simply is not shown.

Deploy (CLOUDFLARE_API_TOKEN and CLOUDFLARE_ACCOUNT_ID in the environment, never printed):

    npx --yes wrangler@3 pages deploy public --project-name mantramanualcamera --branch main --commit-dirty=true
