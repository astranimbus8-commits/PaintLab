# PaintLab

A drawing and photo-editing app for Android (Kotlin + Jetpack Compose), in the spirit of ibisPaint.

## Features
- **Canvas setup:** width and height in pixels, inches, centimeters, millimeters or points, plus a DPI setting. There are presets (A4, Letter, comic B5, phone, 4K, Roblox texture…), an aspect lock, a swap button, and a choice of background (white, transparent or black). The canvas size can be changed later with ⋮ → Canvas size.
- **Import a picture:** start a new canvas from a photo, or add one as a new layer with the top-bar photo button. The picture opens straight into Transform.
- **Drawing:** brush, eraser, fill bucket and color picker. Brushes have size, opacity and hardness settings, and a stylus changes line width with pressure.
- **Layers:** add, duplicate, reorder, merge down, hide/show, rename (long-press), clear, delete and set opacity.
- **Undo and redo.**
- **Transform:** works on the whole layer, or only the selected area when there is a selection. Drag to move, pinch to scale and rotate, pull the edge handles to stretch, or use flip, rotate 90° and fit.
- **Frame divider:** splits the page into comic panels. Set a grid of rows and columns, then drag lines across panels to split them further. Gutter, border and margin are adjustable, and lines can snap straight. The frames are added as a layer.
- **Smart select (Lightroom-style):** automatically selects Subject, Background, Sky, Nature, Water, Buildings, People, Vehicles, Ground or Animals. You can also draw a lasso or rectangle, and add to, subtract from or invert a selection.
- **Using a selection:** apply Adjust (exposure, contrast, saturation, temperature, tint, hue), fill it, delete it, copy or cut it to a new layer, or transform it.
- **Export:** PNG (with an optional transparent background), JPG (with a quality setting) or PDF. The PDF page size comes from the canvas DPI. Exports can be 50%, 100% or 200% size. Choose "Save to phone" (goes to Pictures/PaintLab, or Download/PaintLab for PDFs) or "Save as…" to pick a location.

## Build the APK with GitHub (no computer needed)
1. Put this folder in a GitHub repository, on the `main` branch.
2. Each push runs **Actions → Build APK**. When the run finishes, open **Releases**, tap `PaintLab.apk` and install it. You'll need to allow installing from your browser.
3. The app is signed with a fixed key (`app/debug.keystore`), so each new build installs as an update over the previous one.

## Smart select models
- **Subject / Background** uses Google ML Kit on the device. Google Play services downloads the model the first time you use it.
- **Sky, Nature, Water, Buildings, People, Vehicles, Ground, Animals** use a DeepLabV3 model trained on ADE20K (TensorFlow Lite). The build downloads it into `app/src/main/assets/scene_model.tflite` by running `scripts/fetch_scene_model.py`. If that download fails, the app still works, and you can load a `.tflite` file yourself from ⋮ → Load scene model.

## Limits
- The largest canvas is 25 megapixels, because Android can't draw a bitmap bigger than 100 MB.
- Projects aren't saved between sessions. Export your work before closing the app.
