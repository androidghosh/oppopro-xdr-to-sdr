# ProXDR to Punchy SDR Converter (Android)

Reads the **Android 14+ / ColorOS ProXDR Gainmap** (`android.graphics.Gainmap`) embedded inside Oppo Find X9s photos and tone-maps it directly into a punchy 8-bit SDR JPEG so WhatsApp & WhatsApp Status never wash out your photos.

## How It Works
1. **Gainmap Highlight Extraction:** Reads the secondary ProXDR gainmap layer and multiplies bright highlight pixels into the SDR image with a soft filmic roll-off.
2. **Shadow Toe Crush:** Pulls down lifted grey shadows so dark spots stay deep and dramatic.
3. **S-Curve Contrast & Saturation Lock:** Applies a smooth S-curve and strips the raw gainmap tag on export so WhatsApp doesn't misread or flatten the file.

## Building the APK on GitHub
1. Upload all files in this ZIP to a new GitHub repository (keep the `.github/workflows/build-apk.yml` folder structure intact).
2. Go to the **Actions** tab in your GitHub repository.
3. Click the **Build ProXDR to SDR APK** workflow run and download `ProXDR-to-SDR-APK` under **Artifacts**.
