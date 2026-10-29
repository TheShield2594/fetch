# Bundled profile avatars

The 40 avatars in `app/src/main/assets/avatars/` (copied to
`web/apps/web/public/avatars/` for the web panel) are original artwork made for
Fetch with [Higgsfield](https://higgsfield.ai). Their ids, names, categories and
background colours are listed in `BundledAvatars.kt` and `builtinAvatars.ts`.

Each file is a 512x512 transparent WebP. The apps draw it on the avatar's
background colour (or the profile's own colour) and clip it to a circle, so the
subject sits inside that circle with some room around it.

## Adding or replacing one

1. **Render** with the Nano Banana Pro model, 1:1, 1k, using the popcorn render
   (Higgsfield job `9fa22a39-d803-4e88-9acf-187b92ae4edc`) as an
   `image_references` input so the new one matches the set. The prompt we used:

   > Premium streaming-service profile avatar of *a friendly fox*. Glossy stylized
   > 3D render like a modern animated feature film, soft studio lighting with gentle
   > rim light, smooth polished materials, big friendly expressive eyes. Show only
   > *the fox's head*, complete and floating in the center with generous empty
   > space on all sides; nothing touches or is cut off by the image edges. Plain
   > solid *#F4511E* background, perfectly flat, no glow, no rings, no shadow on the
   > background, no text, no border. Match the rendering style, materials and
   > lighting of the reference image, but not its subject.

   Animals and characters are shown as heads, and objects whole. Use the
   avatar's background colour from `BundledAvatars.kt`.
2. **Cut out** the background with Higgsfield's background remover, and save the
   PNG as `<id>.png` (the part after `fetch:`).
3. **Size and export** to the app's assets, then copy to the web panel:

   ```bash
   python3 scripts/avatars/prepare_avatars.py <folder of cut-outs> app/src/main/assets/avatars
   cp app/src/main/assets/avatars/*.webp web/apps/web/public/avatars/
   ```

A new avatar also needs an entry in `BundledAvatars.kt`, `builtinAvatars.ts` and
an `avatar_name_<id>` string.
