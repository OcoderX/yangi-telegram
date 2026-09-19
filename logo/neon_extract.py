"""Split the two logo grids into transparent PNGs and expose the neon tiles for icon generation."""
import os
import numpy as np
from PIL import Image, ImageDraw
from scipy.ndimage import (uniform_filter, binary_closing, binary_fill_holes,
                           binary_erosion, label, binary_dilation)

ROOT = 'D:/telegramr'
FLAT = ROOT + '/logo/778d0559-d2fe-48c9-b3bb-fb1e39bcc87d.png'
NEON = ROOT + '/logo/9e237fe0-82d9-4dcd-9797-dab959cdf7bf.png'
SPLIT = ROOT + '/logo/split'
OUT = ('C:/Users/boq/AppData/Local/Temp/claude/d--telegramr/'
       '4b7ab1a4-61fa-4b83-8b73-41768fb37a83/scratchpad')
SS = 4  # supersampling for mask rasterisation

# quadrant interiors (divider band: x 623..629, y 608..614 in both grids)
XQ = [(0, 623), (630, 1254)]
YQ = [(0, 608), (615, 1254)]
QUAD = {'red': (0, 0), 'green': (1, 0), 'blue': (0, 1), 'rainbow': (1, 1)}  # (col, row)


def quadrant(img, name):
    cx, cy = QUAD[name]
    x0, x1 = XQ[cx]
    y0, y1 = YQ[cy]
    return img[y0:y1, x0:x1].astype(np.float64)


def rounded_rect_alpha(shape, x0, y0, x1, y1, r):
    """Anti-aliased rounded-rect coverage via SS x supersampling."""
    H, W = shape
    big = Image.new('L', (W * SS, H * SS), 0)
    ImageDraw.Draw(big).rounded_rectangle(
        [x0 * SS, y0 * SS, (x1 + 1) * SS - 1, (y1 + 1) * SS - 1], radius=r * SS, fill=255)
    return np.asarray(big.resize((W, H), Image.BOX)).astype(np.float64) / 255.0


def largest_cc(m):
    lab, n = label(m)
    if n == 0:
        return m
    sizes = np.bincount(lab.ravel())
    sizes[0] = 0
    return lab == sizes.argmax()


def fit_tile(sub, kind):
    """Return (x0, y0, x1, y1, r) of the rounded square in the quadrant."""
    lum = sub.max(axis=2)
    bg = np.percentile(lum, 5)
    if kind == 'flat':
        m = lum > bg + 25
    else:
        # the tile carries sharp structure (rim, bevel, glyph); the halo is smooth
        mu = uniform_filter(lum, 9)
        sd = np.sqrt(np.maximum(uniform_filter(lum * lum, 9) - mu * mu, 0))
        m = sd > 6.0
    m = binary_closing(m, np.ones((3, 3)), iterations=6)
    m = binary_fill_holes(m)
    m = largest_cc(m)
    m = binary_erosion(m, np.ones((3, 3)), iterations=2)
    m = largest_cc(m)
    m = binary_fill_holes(m)
    ys, xs = np.where(m)
    x0, x1, y0, y1 = xs.min(), xs.max(), ys.min(), ys.max()
    w, h = x1 - x0 + 1, y1 - y0 + 1
    area = m.sum()
    r = float(np.sqrt(max(w * h - area, 0) / (4.0 - np.pi)))
    r = min(r, 0.5 * min(w, h))
    return x0, y0, x1, y1, r, m


def border_taper(h, w, band):
    """1 in the middle, smoothstep down to 0 on the outermost `band` pixels."""
    def ax(n):
        i = np.minimum(np.arange(n), n - 1 - np.arange(n)).astype(np.float64)
        t = np.clip(i / max(band - 1.0, 1.0), 0, 1)
        return t * t * (3 - 2 * t)
    return np.minimum(ax(h)[:, None], ax(w)[None, :])


def border_bg(sub, band=18):
    """Backdrop level from the quadrant border frame (always outside the tile)."""
    H, W, _ = sub.shape
    m = np.zeros((H, W), bool)
    m[:band] = m[-band:] = True
    m[:, :band] = m[:, -band:] = True
    # neutral level: the strongest channel wins so no coloured haze survives in the backdrop
    return np.full(3, np.percentile(sub[m], 10, axis=0).max())


def cutout(sub, kind, name):
    """RGBA float (H, W, 4) in 0..255 / 0..1 alpha for the whole quadrant."""
    H, W, _ = sub.shape
    x0, y0, x1, y1, r, rough = fit_tile(sub, kind)
    rect = rounded_rect_alpha((H, W), x0, y0, x1, y1, r)
    iou = ((rect > 0.5) & rough).sum() / float(((rect > 0.5) | rough).sum())
    bg = border_bg(sub)
    c = np.clip(sub - bg, 0, None) * (255.0 / (255.0 - bg.max()))
    if kind == 'flat':
        a = rect
    else:
        glow = np.clip(c.max(axis=2) / 255.0, 0, 1)
        # the halo runs into the quadrant border, so fade the last few pixels instead of cutting them
        side = max(x1 - x0 + 1, y1 - y0 + 1)
        a = np.maximum(rect, glow * border_taper(H, W, int(round(0.03 * side))))
    a = np.clip(a, 0, 1)
    safe = np.maximum(a, 1e-4)[..., None]
    if kind == 'flat':
        # un-premultiply against the dark backdrop so the anti-aliased edge keeps no black fringe
        col = np.clip((sub - bg * (1 - safe)) / safe, 0, 255)
    else:
        col = np.clip(c / safe, 0, 255)
        col = col * (1 - rect[..., None]) + sub * rect[..., None]
    out = np.concatenate([col, a[..., None] * 255.0], axis=2)
    print('  %-8s %-5s tile x[%d..%d] y[%d..%d] %dx%d r=%.1f (%.1f%%) iou=%.4f bg=%s'
          % (name, kind, x0, x1, y0, y1, x1 - x0 + 1, y1 - y0 + 1, r,
             100 * r / max(x1 - x0 + 1, y1 - y0 + 1), iou, bg.round(1).tolist()))
    return out, (x0, y0, x1, y1, r)


def alpha_bbox(rgba, th):
    a = rgba[..., 3] / 255.0
    ys, xs = np.where(a > th)
    return xs.min(), ys.min(), xs.max(), ys.max()


def build():
    flat = np.array(Image.open(FLAT).convert('RGB'))
    neon = np.array(Image.open(NEON).convert('RGB'))
    res = {}
    for kind, img in (('flat', flat), ('neon', neon)):
        for name in ('red', 'green', 'blue', 'rainbow'):
            sub = quadrant(img, name)
            rgba, rect = cutout(sub, kind, name)
            res[(kind, name)] = (rgba, rect)
    return res


if __name__ == '__main__':
    os.makedirs(SPLIT, exist_ok=True)
    data = build()
    for (kind, name), (rgba, rect) in data.items():
        x0, y0, x1, y1, r = rect
        side = max(x1 - x0 + 1, y1 - y0 + 1)
        m = int(round(side * (0.005 if kind == 'flat' else 0.075)))
        H, W, _ = rgba.shape
        cx0, cy0 = max(0, x0 - m), max(0, y0 - m)
        cx1, cy1 = min(W - 1, x1 + m), min(H - 1, y1 + m)
        crop = rgba[cy0:cy1 + 1, cx0:cx1 + 1].copy()
        if kind == 'neon':
            ch, cw, _ = crop.shape
            crop[..., 3] *= border_taper(ch, cw, int(round(0.055 * side)))
        img = Image.fromarray(np.round(np.clip(crop, 0, 255)).astype(np.uint8), 'RGBA')
        p = '%s/%s_%s.png' % (SPLIT, kind, name)
        img.save(p, optimize=True)
        gb = alpha_bbox(rgba, 0.02)
        print('write %s %s  glow>2%% bbox=%s  tile side=%d  glow/tile=%.3f'
              % (p, img.size, gb, side,
                 max(gb[2] - gb[0] + 1, gb[3] - gb[1] + 1) / float(side)))
