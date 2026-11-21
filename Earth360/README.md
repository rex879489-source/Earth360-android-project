# Earth360 (Android APK)

Your `pano_360_v2.html` wrapped in a native Android WebView, built automatically by GitHub Actions.

## Get the APK
1. Create a new GitHub repo and push this folder (branch `main`).
2. Open the **Actions** tab -> *Build APK* runs by itself (or press **Run workflow**).
3. Download the APK from the repo's **Releases** page (or the run's *Artifacts*) and install it.
   (Allow "Install unknown apps" for your browser/file manager.)

## What the app adds on top of the HTML
- **Background link capture:** when the page needs the Google Maps URL (Step 2), the app opens the
  link in a hidden **desktop-mode** browser, waits until the address bar contains the pano ID / lh3
  token, fills Step 2 for you and closes it. Short links (`maps.app.goo.gl`, `earth.app.goo.gl`)
  are resolved the same way.
- **Share target:** share a Maps/Earth link to "Earth360" from any app.
- **Saving:** results go to `Pictures/Earth360` (MediaStore, no storage permission needed).
- **CORS fix:** tile/image requests get a permissive CORS header so the canvas can be exported.

## Signing
`app/earth360.keystore` (password `earth360`) is a throwaway key so every build has the same
signature and updates install over each other. For a public repo, add your own key as secrets:
`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.

## Files
- `app/src/main/assets/index.html` - your original page (+1 script tag)
- `app/src/main/assets/android-bridge.js` - native integration (save, auto-capture)
- `app/src/main/java/.../MainActivity.kt` - WebView, hidden extractor, CORS proxy, saver
