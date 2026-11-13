#!/usr/bin/env python3
"""Cuts the artwork in art/source/ into the pictures the apps use.

  art/source/goat-eating.webp    10 frames: the logo (frame 1) and the startup animation
  art/source/goat-offline.jpg    10 frames: the "no network" animation
  art/source/avatars-*.jpg       profile avatars (16 per sheet)

Every picture gets two layers, so the apps can recolour the pink circle (and the goat's inner ears) with the theme
or profile colour:
  <name>.png       the picture, background removed
  <name>-tint.png  the pink parts only, as brightness (grey) + coverage (alpha): the apps draw it multiplied by the
                   colour on top of the picture.
Frames of an animation are lined up on their circle so the circle doesn't jump between frames.

    pip install numpy scipy pillow;  python3 scripts/art.py      (writes art/out/, then copies into the apps)
"""
import json
import math
import os
import shutil
import sys

import numpy as np
from PIL import Image, ImageFilter
from scipy import ndimage

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "art", "source")
OUT = os.path.join(ROOT, "art", "out")


def load(name):
    return np.asarray(Image.open(os.path.join(SRC, name)).convert("RGB")).astype(np.float32)


def pink_mask(rgb):
    h, s, v = hsv(rgb)
    return ((h > 325) | (h < 14)) & (s > 0.16) & (s < 0.74) & (v > 0.5)


def runs(profile, min_len, gap=6):
    """[start, end) runs where profile > 0, joining runs separated by less than [gap]."""
    on = profile > 0
    out, start, last = [], None, None
    for k, v in enumerate(on):
        if v:
            if start is None:
                start = k
            last = k
        elif start is not None and k - last > gap:
            if last + 1 - start >= min_len:
                out.append((start, last + 1))
            start = None
    if start is not None and last + 1 - start >= min_len:
        out.append((start, last + 1))
    return out


def fit_circle(xs, ys):
    A = np.column_stack([xs, ys, np.ones_like(xs)]).astype(np.float64)
    b = xs.astype(np.float64) ** 2 + ys.astype(np.float64) ** 2
    c, *_ = np.linalg.lstsq(A, b, rcond=None)
    cx, cy = c[0] / 2, c[1] / 2
    return cx, cy, math.sqrt(max(c[2] + cx * cx + cy * cy, 1))


def find_tiles(rgb, layout):
    """The pictures on a sheet, found by their pink circles; [layout] is the number of pictures in each row."""
    pink = ndimage.binary_opening(pink_mask(rgb), iterations=2)
    circles = []
    rows = runs(pink.sum(axis=1), 80, gap=4)
    assert len(rows) == len(layout), f"found {len(rows)} rows, expected {len(layout)}"
    for (y0, y1), count in zip(rows, layout):
        ys_all, xs_all = np.nonzero(pink[y0:y1])
        ys_all = ys_all + y0
        # The row's pink pixels in [count] groups by x (1D k-means): one group per picture.
        centers = np.quantile(xs_all, (np.arange(count) + 0.5) / count)
        for _ in range(30):
            group = np.abs(xs_all[:, None] - centers[None, :]).argmin(axis=1)
            centers = np.array([xs_all[group == g].mean() for g in range(count)])
        for g in range(count):
            xs, ys = xs_all[group == g], ys_all[group == g]
            # The circle's outline is where its pink meets the white page: fit a circle to those points, robustly
            # (RANSAC), ignoring points where the drawing meets the pink.
            page = (rgb.min(axis=2) > 225)
            edge_all = pink & ndimage.binary_dilation(page, iterations=2)
            sel = np.zeros_like(pink); sel[ys, xs] = True
            ey, ex = np.nonzero(edge_all & sel)
            rng = np.random.default_rng(1)
            best, best_in = None, -1
            for _ in range(600):
                k = rng.choice(len(ex), 3, replace=False)
                try:
                    c = fit_circle(ex[k], ey[k])
                except Exception:
                    continue
                if not (90 < c[2] < 150):
                    continue
                inl = np.abs(np.hypot(ex - c[0], ey - c[1]) - c[2]) < 1.8
                if inl.sum() > best_in:
                    best, best_in = inl, inl.sum()
            circles.append(fit_circle(ex[best], ey[best]))
    # Every drawn pixel belongs to the picture whose circle is nearest (relative to its size); whole strokes stay
    # together: each connected piece of ink goes to the circle nearest to most of its pixels.
    ink = rgb.min(axis=2) < 232
    hh, ww = ink.shape
    yy, xx = np.mgrid[0:hh, 0:ww]
    d = np.stack([np.hypot(xx - cx, yy - cy) / r for cx, cy, r in circles])
    nearest = d.argmin(axis=0)
    near_enough = d.min(axis=0) < 1.75
    pieces, n = ndimage.label(ink, structure=np.ones((3, 3)))
    owner = np.full(ink.shape, -1)
    for k, sl in enumerate(ndimage.find_objects(pieces), start=1):
        m = pieces[sl] == k
        votes = np.bincount(nearest[sl][m], minlength=len(circles))
        if m.sum() > 4000 and np.count_nonzero(votes > 0.3 * m.sum()) > 1:
            o = np.where(m, nearest[sl], -1)                   # a big piece touching two pictures: split it
        else:
            o = np.where(m, votes.argmax(), -1)
        owner[sl] = np.where(m, o, owner[sl])
    # Pieces entirely outside every circle (words, stars, a flying blade): pieces close together form one group
    # (a line of text) that goes to one picture, the one most of it is nearest to.
    inside_any = d.min(axis=0) <= 1.02
    loose = np.zeros_like(ink)
    for k, sl in enumerate(ndimage.find_objects(pieces), start=1):
        m = pieces[sl] == k
        if not (inside_any[sl] & m).any():
            loose[sl] |= m
    groups, gn = ndimage.label(ndimage.binary_dilation(loose, iterations=8))
    for k, sl in enumerate(ndimage.find_objects(groups), start=1):
        m = (groups[sl] == k) & loose[sl]
        if m.any():
            votes = np.bincount(nearest[sl][m], minlength=len(circles))
            owner[sl] = np.where(m, votes.argmax(), owner[sl])
    owner = np.where(near_enough, owner, -1)
    # The sheet's title: pieces entirely above the top row's circles.
    top = min(cy - r for cx, cy, r in circles)
    for k, sl in enumerate(ndimage.find_objects(pieces), start=1):
        if sl[0].stop < top - 2:
            owner[sl] = np.where(pieces[sl] == k, -1, owner[sl])
    # Fill each picture's inside (white fur is not "ink") so it isn't cut away below.
    return circles, owner


def hsv(rgb):
    r, g, b = rgb[..., 0] / 255, rgb[..., 1] / 255, rgb[..., 2] / 255
    mx, mn = np.maximum(np.maximum(r, g), b), np.minimum(np.minimum(r, g), b)
    d = mx - mn
    h = np.zeros_like(mx)
    m = d > 1e-6
    rr = m & (mx == r); gg = m & (mx == g) & ~rr; bb = m & ~rr & ~gg
    h[rr] = ((g - b)[rr] / d[rr]) % 6
    h[gg] = (b - r)[gg] / d[gg] + 2
    h[bb] = (r - g)[bb] / d[bb] + 4
    h = h * 60
    s = np.where(mx > 1e-6, d / np.maximum(mx, 1e-6), 0)
    return h, s, mx


def cut(rgb, owner, idx, circle, pad=8):
    """The picture with a transparent background (RGBA float, 0..255) and its pink mask (0..1)."""
    ys, xs = np.nonzero(owner == idx)
    y0, y1 = max(ys.min() - pad, 0), min(ys.max() + 1 + pad, rgb.shape[0])
    x0, x1 = max(xs.min() - pad, 0), min(xs.max() + 1 + pad, rgb.shape[1])
    img = rgb[y0:y1, x0:x1]
    cx, cy, r = circle
    yy, xx = np.mgrid[0:img.shape[0], 0:img.shape[1]]
    dist = np.hypot(xx + x0 - cx, yy + y0 - cy)
    # Everything inside the circle is part of the picture (fur that runs into the page has no outline).
    mine = ndimage.binary_fill_holes(ndimage.binary_closing(owner[y0:y1, x0:x1] == idx, iterations=2)) | (dist <= r - 0.5)
    # Background: the white page, which is only outside the circle, connected to the crop's border. The outlines
    # are thickened first so the page can't leak through small gaps into the goat's white fur.
    whiteish = (img.min(axis=2) > 214) & ((img.max(axis=2) - img.min(axis=2)) < 26)
    outside = dist > r + 1.5
    barrier = ndimage.binary_dilation(img.max(axis=2) < 120, iterations=4)
    page = (whiteish & outside & ~barrier) | ~mine
    wl, _ = ndimage.label(page)
    border = set(np.unique(np.concatenate([wl[0], wl[-1], wl[:, 0], wl[:, -1]]))) - {0}
    bg = np.isin(wl, list(border)) | ~mine
    # Give back the page pixels the thickened outlines covered (two steps: too few to leak into the fur).
    for _ in range(5):
        bg |= ndimage.binary_dilation(bg) & whiteish & outside
    alpha = (~bg).astype(np.float32)
    # Soft edge: pixels next to the background become partly transparent by how white they are.
    edge = ndimage.binary_dilation(bg, iterations=2) & ~bg
    lum = img.mean(axis=2)
    a_edge = np.clip((255 - lum) / 90.0, 0, 1)
    alpha = np.where(edge, np.maximum(a_edge, 0.0), alpha)
    # Un-blend the white from the soft edge's colour.
    a = np.maximum(alpha, 1e-3)[..., None]
    color = np.clip((img - (1 - a) * 255) / a, 0, 255)
    color = np.where(alpha[..., None] > 0, color, 0)
    pink = pink_mask(img) & (alpha > 0)
    pink = ndimage.binary_opening(pink, iterations=1)
    # Small specks (a tongue, a blush) keep their colour: only the circle and the inner ears are big enough.
    pl, n = ndimage.label(pink)
    if n:
        sizes = ndimage.sum(pink, pl, range(1, n + 1))
        keep = np.isin(pl, [i + 1 for i, sz in enumerate(sizes) if sz >= 0.004 * pink.size])
    else:
        keep = pink
    soft = ndimage.gaussian_filter(keep.astype(np.float32), 0.7)
    return np.dstack([color, alpha * 255]), np.clip(soft, 0, 1) * alpha, (x0, y0)


def circle_of(rgba, pink):
    """Centre and radius of the pink circle: a least-squares fit to the circle's outer edge."""
    alpha = rgba[..., 3] > 128
    outside = ~alpha
    edge = (pink > 0.5) & ndimage.binary_dilation(outside, iterations=2)
    ys, xs = np.nonzero(edge)
    if len(xs) < 50:
        ys, xs = np.nonzero(pink > 0.5)
    A = np.column_stack([xs, ys, np.ones_like(xs)]).astype(np.float64)
    b = (xs.astype(np.float64) ** 2 + ys.astype(np.float64) ** 2)
    c, *_ = np.linalg.lstsq(A, b, rcond=None)
    cx, cy = c[0] / 2, c[1] / 2
    r = math.sqrt(c[2] + cx * cx + cy * cy)
    return cx, cy, r


def place(rgba, pink, circle, size, radius, center):
    """Scales and moves the picture so its circle has [radius] and sits at [center] on a [size] canvas."""
    cx, cy, r = circle
    k = radius / r
    pre = rgba.copy(); pre[..., :3] *= pre[..., 3:4] / 255.0           # premultiply for clean resampling
    layers = []
    for arr in (pre, np.dstack([pink * 255, pink * 255, pink * 255, pink * 255]), luminance(rgba)):
        im = Image.fromarray(np.clip(arr, 0, 255).astype(np.uint8), "RGBA" if arr.shape[2] == 4 else "L")
        w, h = im.size
        im = im.resize((max(1, round(w * k)), max(1, round(h * k))), Image.LANCZOS)
        canvas = Image.new(im.mode, (size, size), 0)
        canvas.paste(im, (round(center[0] - cx * k), round(center[1] - cy * k)))
        layers.append(np.asarray(canvas).astype(np.float32))
    pre, pk, lum = layers
    a = pre[..., 3:4]
    rgb = np.where(a > 0, pre[..., :3] * 255.0 / np.maximum(a, 1), 0)
    return np.dstack([np.clip(rgb, 0, 255), a]), pk[..., 3] / 255.0, lum[..., 0] / 255.0 if lum.ndim == 3 else lum / 255.0


def luminance(rgba):
    y = 0.3 * rgba[..., 0] + 0.59 * rgba[..., 1] + 0.11 * rgba[..., 2]
    return np.dstack([y, y, y, rgba[..., 3]])


def save(name, rgba, pink, lum, sharpen=True):
    os.makedirs(OUT, exist_ok=True)
    im = Image.fromarray(np.clip(rgba, 0, 255).astype(np.uint8), "RGBA")
    if sharpen:
        rgb = im.convert("RGB").filter(ImageFilter.UnsharpMask(radius=1.2, percent=60, threshold=2))
        im = Image.merge("RGBA", (*rgb.split(), im.split()[3]))
    im.save(os.path.join(OUT, name + ".png"), optimize=True)
    # Tint layer: the pink parts' brightness, scaled so the lightest pink is white (the colour shows at full
    # strength there and darker towards the circle's edge, like the original shading).
    sel = pink > 0.5
    ref = np.percentile(lum[sel], 92) if sel.any() else 1.0
    grey = np.clip(lum / max(ref, 1e-3), 0, 1) * 255
    tint = Image.fromarray(np.dstack([grey, grey, grey, np.clip(pink, 0, 1) * 255]).astype(np.uint8), "RGBA")
    tint.convert("LA").save(os.path.join(OUT, name + "-tint.png"), optimize=True)


def preview(name, rgba, pink, lum, color=(59, 130, 246)):
    """The picture with the tint applied (for checking, and for the static icons)."""
    sel = pink > 0.5
    ref = np.percentile(lum[sel], 92) if sel.any() else 1.0
    g = np.clip(lum / max(ref, 1e-3), 0, 1)[..., None]
    tinted = np.array(color, np.float32)[None, None, :] * g
    p = pink[..., None]
    out = rgba.copy()
    out[..., :3] = rgba[..., :3] * (1 - p) + tinted * p
    return out


def sheet(name, layout, size, radius, center, prefix, names=None, align=True):
    rgb = load(name)
    circles, owner = find_tiles(rgb, layout)
    print(f"{name}: {len(circles)} pictures")
    results = []
    for n in range(len(circles)):
        rgba, pink, (x0, y0) = cut(rgb, owner, n, circles[n])
        circle = (circles[n][0] - x0, circles[n][1] - y0, circles[n][2])
        if align:
            placed = place(rgba, pink, circle, size, radius, center)
        else:
            placed = place(rgba, pink, circle, size, circle[2], (circle[0], circle[1]))
        label = names[n] if names and n < len(names) else f"{prefix}{n + 1:02d}"
        results.append((label, *placed, circle))
    return results


def grid_tiles(rgb, rows, cols):
    """Pictures on a regular grid (avatar sheets): each cell's circle, found where its colour meets the page."""
    h, w = rgb.shape[:2]
    page = (rgb.min(axis=2) > 222) & ((rgb.max(axis=2) - rgb.min(axis=2)) < 22)
    edge_all = ~page & ndimage.binary_dilation(page, iterations=2)
    owner = np.full((h, w), -1)
    circles = []
    rng = np.random.default_rng(3)
    for r in range(rows):
        for c in range(cols):
            y0, y1 = round(r * h / rows), round((r + 1) * h / rows)
            x0, x1 = round(c * w / cols), round((c + 1) * w / cols)
            owner[y0:y1, x0:x1] = len(circles)
            ey, ex = np.nonzero(edge_all[y0:y1, x0:x1])
            ey, ex = ey + y0, ex + x0
            cell = min(y1 - y0, x1 - x0)
            best, best_in = None, -1
            for _ in range(1500):
                k = rng.choice(len(ex), 3, replace=False)
                try:
                    cc = fit_circle(ex[k], ey[k])
                except Exception:
                    continue
                if not (0.28 * cell < cc[2] < 0.5 * cell) or not (x0 < cc[0] < x1 and y0 < cc[1] < y1):
                    continue
                inl = np.abs(np.hypot(ex - cc[0], ey - cc[1]) - cc[2]) < 1.8
                if inl.sum() > best_in:
                    best, best_in = inl, inl.sum()
            circles.append(fit_circle(ex[best], ey[best]))
    return circles, owner


# Avatars: (sheet, rows, cols, names; None = a repeat of another picture, skipped).
AVATARS = [
    ("avatars-goats-colour.webp", 3, 3, ["goat-white", "goat-blue", "goat-gold", "goat-green", "goat-pink", "goat-grey",
                                         "goat-midnight", "goat-orange", "goat-coral"]),
    ("avatars-goats-style.jpg", 3, 3, [None, None, None, "goat-king", "goat-dj", "goat-cap", "goat-cowboy", "goat-professor",
                                       "goat-party"]),
    ("avatars-farm.jpg", 4, 4, ["cow", "sheep", "pig", "dog", "kitten", "cat", "duck", "chicken", "ram", "rabbit", "horse", None,
                                "donkey", "llama", "rooster", "bear"]),
    ("avatars-wild.jpg", 4, 4, ["elephant", "gorilla", "giraffe", "raccoon", None, "penguin", "fox", None, None, "red-panda", "owl",
                                "shark", None, None, None, "lion"]),
]


def avatars(size=256, radius=94):
    out = os.path.join(OUT, "avatars")
    os.makedirs(out, exist_ok=True)
    names = []
    for src, rows, cols, labels in AVATARS:
        rgb = load(src)
        circles, owner = grid_tiles(rgb, rows, cols)
        for n, label in enumerate(labels):
            if label is None:
                continue
            rgba, pink, (x0, y0) = cut(rgb, owner, n, circles[n])
            circle = (circles[n][0] - x0, circles[n][1] - y0, circles[n][2])
            # Bits of the neighbouring pictures that reach into this cell: keep only pieces that touch the circle
            # or lie close to it (a feather, a crown), not ones at the cell's edge.
            solid = rgba[..., 3] > 20
            pieces, count = ndimage.label(solid, structure=np.ones((3, 3)))
            yy, xx = np.mgrid[0:solid.shape[0], 0:solid.shape[1]]
            dist = np.hypot(xx - circle[0], yy - circle[1]) / circle[2]
            for k in range(1, count + 1):
                m = pieces == k
                if (dist[m] <= 1.0).any():
                    continue
                cy, cx = np.argwhere(m).mean(axis=0)
                if math.hypot(cx - circle[0], cy - circle[1]) / circle[2] > 1.12:
                    rgba[m, 3] = 0
            placed, _, _ = place(rgba, pink, circle, size, radius, (size / 2, size / 2 + 6))
            im = Image.fromarray(np.clip(placed, 0, 255).astype(np.uint8), "RGBA")
            rgb_s = im.convert("RGB").filter(ImageFilter.UnsharpMask(radius=1.0, percent=50, threshold=2))
            Image.merge("RGBA", (*rgb_s.split(), im.split()[3])).save(os.path.join(out, label + ".png"), optimize=True)
            names.append(label)
    print(f"{len(names)} avatars")
    return names


if __name__ == "__main__":
    only = sys.argv[1] if len(sys.argv) > 1 else None
    report = {}
    # Animations: 640 canvas, circle radius 210 at (320, 320) (props like signs and cables reach outside it).
    for src, prefix, size in (("goat-eating.webp", "eat", 640), ("goat-offline.jpg", "offline", 880)):
        if only and only not in src:
            continue
        for label, rgba, pink, lum, circle in sheet(src, [4, 3, 3], size, 205, (size / 2, size / 2 - 4), prefix):
            save(label, rgba, pink, lum)
            report[label] = [round(v, 1) for v in circle]
    if not only or only == "avatars":
        avatars()
        # Into the apps: the desktop app's resources and the Android app's assets.
        for dest in ("app/src/main/resources/ui/avatars", "android/app/src/main/assets/avatars"):
            d = os.path.join(ROOT, dest)
            shutil.rmtree(d, ignore_errors=True)
            shutil.copytree(os.path.join(OUT, "avatars"), d)
            print("copied to", dest)
    print(json.dumps(report))
