import os, glob
import numpy as np
from PIL import Image, ImageDraw
from scipy.interpolate import RBFInterpolator
from scipy.ndimage import binary_erosion, binary_dilation
from scipy.spatial import ConvexHull

ROOT = 'D:/telegramr'
SRC = ROOT + '/logo/asosiy logo.png'
GRID = ROOT + '/logo/778d0559-d2fe-48c9-b3bb-fb1e39bcc87d.png'
RES = ROOT + '/TMessagesProj/src/main/res'
RES_SA = ROOT + '/TMessagesProj_AppStandalone/src/main/res'
OUT = 'C:/Users/boq/AppData/Local/Temp/claude/d--telegramr/4b7ab1a4-61fa-4b83-8b73-41768fb37a83/scratchpad'
DENS = [('mdpi', 1.0), ('hdpi', 1.5), ('xhdpi', 2.0), ('xxhdpi', 3.0), ('xxxhdpi', 4.0)]
SS = 4                 # supersampling factor
GLYPH_R_DP = 30.0      # glyph minimal-enclosing-circle radius in dp of the 108dp adaptive canvas (safe zone = 33dp)
CORNER = 0.225         # rounded-square corner radius / side (iOS-like)
PAD_SQUARE = 0.04
PAD_CIRCLE = 0.02
WRITE = os.environ.get('GEN_WRITE', '1') == '1'

# base gradient stops sampled from the supplied artwork: top-left, centre, bottom-right
PAL = {
    'blue':    [(0.0, (0, 232, 255)), (0.5, (0, 128, 248)), (1.0, (0, 72, 244))],
    'crimson': [(0.0, (255, 97, 33)), (0.5, (239, 1, 14)),  (1.0, (140, 0, 8))],
    'emerald': [(0.0, (122, 255, 64)), (0.5, (2, 166, 50)), (1.0, (2, 78, 32))],
}

os.makedirs(OUT, exist_ok=True)

# ---------------- glyph extraction ----------------
im = np.array(Image.open(SRC).convert('RGBA')).astype(np.float64)
inside = im[:, :, 3] > 200
cov = np.clip((im[:, :, 0] - 8.0) / (255.0 - 8.0), 0, 1) * inside   # red channel == white coverage on the blue tile
ys, xs = np.where(cov > 0.02)
gx0, gx1, gy0, gy1 = xs.min(), xs.max(), ys.min(), ys.max()
G = cov[gy0:gy1 + 1, gx0:gx1 + 1]
GH, GW = G.shape
pts = np.argwhere(G > 0.5)[:, ::-1].astype(np.float64)
hull = pts[ConvexHull(pts).vertices]
c = hull.mean(axis=0)
for _ in range(60000):
    d = np.linalg.norm(hull - c, axis=1)
    c = c + (hull[d.argmax()] - c) * 0.001
MEC_C = c
MEC_R = float(np.linalg.norm(hull - c, axis=1).max())
np.save(OUT + '/glyph_cov.npy', G)
with open(OUT + '/glyph_meta.txt', 'w') as f:
    f.write('%f %f %f %d %d\n' % (MEC_C[0], MEC_C[1], MEC_R, GW, GH))
G8 = Image.fromarray(np.round(G * 255).astype(np.uint8), 'L')
print('glyph', GW, 'x', GH, 'MEC centre', MEC_C.round(2).tolist(), 'radius', round(MEC_R, 2))


def glyph_alpha(N, R_px, cx=None, cy=None):
    """N x N float alpha, glyph MEC centred at (cx, cy) with MEC radius R_px."""
    cx = N / 2.0 if cx is None else cx
    cy = N / 2.0 if cy is None else cy
    k = SS
    s = k * R_px / MEC_R
    w, h = max(1, int(round(GW * s))), max(1, int(round(GH * s)))
    g = G8.resize((w, h), Image.LANCZOS)
    big = Image.new('L', (k * N, k * N), 0)
    big.paste(g, (int(round(k * cx - MEC_C[0] * s)), int(round(k * cy - MEC_C[1] * s))))
    return np.asarray(big.resize((N, N), Image.BOX)).astype(np.float64) / 255.0


def shape_alpha(N, kind, pad):
    k = SS
    big = Image.new('L', (k * N, k * N), 0)
    d = ImageDraw.Draw(big)
    a = pad * N * k
    b = k * N - a
    if kind == 'circle':
        d.ellipse([a, a, b, b], fill=255)
    else:
        d.rounded_rectangle([a, a, b, b], radius=CORNER * (b - a), fill=255)
    return np.asarray(big.resize((N, N), Image.BOX)).astype(np.float64) / 255.0


def lin_stops(t, stops):
    t = np.clip(t, 0, 1)
    ts = np.array([s[0] for s in stops])
    cs = np.array([s[1] for s in stops], dtype=np.float64)
    out = np.empty(t.shape + (3,))
    for ch in range(3):
        out[..., ch] = np.interp(t, ts, cs[:, ch])
    return out


def diag_gradient(stops):
    def fn(N, x0, y0, side):
        yy, xx = np.mgrid[0:N, 0:N].astype(np.float64) + 0.5
        return lin_stops(((xx - x0) + (yy - y0)) / (2.0 * side), stops)
    return fn


# ---------------- aurora (rainbow) field from the grid tile ----------------
grid = np.array(Image.open(GRID).convert('RGB')).astype(np.float64)


def tile_bbox(x0, y0, x1, y1):
    lum = grid[y0:y1, x0:x1].max(axis=2) > 60
    rows = np.where(lum.any(axis=1))[0]
    cols = np.where(lum.any(axis=0))[0]
    return (x0 + cols.min(), y0 + rows.min(), x0 + cols.max(), y0 + rows.max())


def build_field(bbox):
    tx0, ty0, tx1, ty1 = bbox
    sub = grid[ty0:ty1 + 1, tx0:tx1 + 1]
    H, W, _ = sub.shape
    shape = binary_erosion(sub.max(axis=2) > 60, iterations=6)
    glyph = binary_dilation(sub.min(axis=2) > 200, iterations=8)
    ok = shape & ~glyph
    ys_, xs_ = np.where(ok)
    rng = np.random.default_rng(7)
    idx = rng.choice(len(xs_), size=min(1000, len(xs_)), replace=False)
    P = np.stack([xs_[idx] / (W - 1.0), ys_[idx] / (H - 1.0)], axis=1)
    V = sub[ys_[idx], xs_[idx]]
    return RBFInterpolator(P, V, kernel='thin_plate_spline', smoothing=30.0)


def field_gradient(rbf):
    def fn(N, x0, y0, side):
        M = min(N, 128)
        yy, xx = np.mgrid[0:M, 0:M].astype(np.float64) + 0.5
        xx = xx * N / M
        yy = yy * N / M
        U = np.stack([((xx - x0) / side).ravel(), ((yy - y0) / side).ravel()], axis=1)
        U = np.clip(U, -0.3, 1.3)
        C = np.clip(rbf(U).reshape(M, M, 3), 0, 255)
        if M != N:
            C = np.stack([np.asarray(Image.fromarray(C[..., ch].astype(np.float32), 'F').resize((N, N), Image.BICUBIC)) for ch in range(3)], axis=2)
        return np.clip(C, 0, 255)
    return fn


# ---------------- composition ----------------
def compose(N, kind, pad, bg_fn, glyph=True):
    x0 = pad * N
    side = N * (1 - 2 * pad)
    mask = shape_alpha(N, kind, pad)
    rgb = bg_fn(N, x0, x0, side)
    if glyph:
        ga = glyph_alpha(N, GLYPH_R_DP / 36.0 * side / 2.0)[..., None]
        rgb = rgb * (1 - ga) + 255.0 * ga
    out = np.concatenate([rgb, mask[..., None] * 255.0], axis=2)
    return Image.fromarray(np.round(np.clip(out, 0, 255)).astype(np.uint8), 'RGBA')


def fg_layer(N):
    ga = glyph_alpha(N, N * GLYPH_R_DP / 108.0)
    out = np.zeros((N, N, 4))
    out[..., :3] = 255.0
    out[..., 3] = ga * 255.0
    return Image.fromarray(np.round(out).astype(np.uint8), 'RGBA')


def bg_layer(N, bg_fn):
    rgb = bg_fn(N, N / 6.0, N / 6.0, N * 2.0 / 3.0)
    return Image.fromarray(np.round(np.clip(rgb, 0, 255)).astype(np.uint8), 'RGB')


def hexc(col):
    return '#%02X%02X%02X' % tuple(int(round(min(255.0, max(0.0, v)))) for v in col)


def xml_gradient(stops):
    tl, cc, br = (np.array(s[1], dtype=np.float64) for s in stops)
    # only the central 2/3 of the 108dp canvas is visible: extrapolate so the visible corners keep the artwork colours
    start = 1.5 * tl - 0.5 * cc
    end = 1.5 * br - 0.5 * cc
    return ('<?xml version="1.0" encoding="utf-8"?>\n'
            '<shape xmlns:android="http://schemas.android.com/apk/res/android">\n'
            '    <gradient\n'
            '        android:type="linear"\n'
            '        android:angle="-45"\n'
            '        android:startColor="%s"\n'
            '        android:centerColor="%s"\n'
            '        android:endColor="%s" />\n'
            '</shape>\n') % (hexc(start), hexc(cc), hexc(end))


def adaptive_xml(bg):
    return ('<?xml version="1.0" encoding="utf-8"?>\n'
            '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
            '    <background android:drawable="%s" />\n'
            '    <foreground android:drawable="@mipmap/icon_foreground" />\n'
            '    <monochrome android:drawable="@drawable/icon_glyph" />\n'
            '</adaptive-icon>\n') % bg


VARIANTS = {
    'blue': diag_gradient(PAL['blue']),
    'crimson': diag_gradient(PAL['crimson']),
    'emerald': diag_gradient(PAL['emerald']),
    'aurora': field_gradient(build_field(tile_bbox(627, 627, 1254, 1254))),
}
BG_REF = {
    'blue': '@drawable/icon_background_sa',
    'crimson': '@drawable/icon_crimson_background',
    'emerald': '@drawable/icon_emerald_background',
    'aurora': '@mipmap/icon_aurora_background',
}


def save(img, path, **kw):
    if not WRITE:
        return
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path, **kw)
    print('write', os.path.relpath(path, ROOT), img.size)


def write_text(path, text):
    if not WRITE:
        return
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w', encoding='utf-8', newline='\n') as f:
        f.write(text)
    print('write', os.path.relpath(path, ROOT))


def rm(p):
    if WRITE and os.path.exists(p):
        os.remove(p)
        print('rm', os.path.relpath(p, ROOT))


# ---------------- remove the old Telegram icon set ----------------
for pat in ['mipmap-*/icon_[2-6]_*', 'mipmap-*/icon_background_clip*', 'mipmap-*/icon_foreground_round.png',
            'mipmap-*/icon_foreground_sa.png', 'drawable/icon_[2-6]_background*.xml', 'drawable/icon_background_clip*.webp',
            'drawable/icon_background.xml', 'drawable/icon_background_round.xml', 'drawable/icon_plane.xml']:
    for p in glob.glob(RES + '/' + pat):
        rm(p)
for p in glob.glob(RES_SA + '/mipmap-*/icon_[2-6]_launcher_sa.*'):
    rm(p)

# ---------------- bitmaps ----------------
for dn, dd in DENS:
    NA = int(round(108 * dd))
    NL = int(round(48 * dd))
    save(fg_layer(NA), '%s/mipmap-%s/icon_foreground.png' % (RES, dn), optimize=True)
    save(bg_layer(NA, VARIANTS['aurora']), '%s/mipmap-%s/icon_aurora_background.png' % (RES, dn), optimize=True)
    save(compose(NL, 'square', PAD_SQUARE, VARIANTS['blue']), '%s/mipmap-%s/ic_launcher.png' % (RES, dn), optimize=True)
    save(compose(NL, 'circle', PAD_CIRCLE, VARIANTS['blue']), '%s/mipmap-%s/ic_launcher_round.png' % (RES, dn), optimize=True)
    save(compose(NL, 'circle', PAD_CIRCLE, VARIANTS['blue']), '%s/drawable-%s/ic_launcher_dr.webp' % (RES, dn), lossless=True, quality=100, method=6)
    save(compose(NL, 'square', PAD_SQUARE, VARIANTS['blue']), '%s/mipmap-%s/ic_launcher_sa.png' % (RES_SA, dn), optimize=True)
    for v in ('crimson', 'emerald', 'aurora'):
        save(compose(NL, 'square', PAD_SQUARE, VARIANTS[v]), '%s/mipmap-%s/icon_%s_launcher.png' % (RES, dn, v), optimize=True)
        save(compose(NL, 'circle', PAD_CIRCLE, VARIANTS[v]), '%s/mipmap-%s/icon_%s_launcher_round.png' % (RES, dn, v), optimize=True)
        save(compose(NL, 'square', PAD_SQUARE, VARIANTS[v]), '%s/mipmap-%s/icon_%s_launcher_sa.png' % (RES_SA, dn, v), optimize=True)

# ---------------- xml drawables ----------------
write_text(RES + '/drawable/icon_background_sa.xml', xml_gradient(PAL['blue']))
write_text(RES + '/drawable/icon_crimson_background.xml', xml_gradient(PAL['crimson']))
write_text(RES + '/drawable/icon_emerald_background.xml', xml_gradient(PAL['emerald']))
write_text(RES + '/mipmap-anydpi-v26/ic_launcher.xml', adaptive_xml(BG_REF['blue']))
write_text(RES + '/mipmap-anydpi-v26/ic_launcher_round.xml', adaptive_xml(BG_REF['blue']))
write_text(RES_SA + '/mipmap-anydpi-v26/ic_launcher_sa.xml', adaptive_xml(BG_REF['blue']))
for v in ('crimson', 'emerald', 'aurora'):
    write_text('%s/mipmap-anydpi-v26/icon_%s_launcher.xml' % (RES, v), adaptive_xml(BG_REF[v]))
    write_text('%s/mipmap-anydpi-v26/icon_%s_launcher_round.xml' % (RES, v), adaptive_xml(BG_REF[v]))
    write_text('%s/mipmap-anydpi-v26/icon_%s_launcher_sa.xml' % (RES_SA, v), adaptive_xml(BG_REF[v]))

# ---------------- preview sheet ----------------
def masked_previews(N, bg_fn):
    bg = np.asarray(bg_layer(N, bg_fn)).astype(np.float64)
    fg = np.asarray(fg_layer(N)).astype(np.float64)
    ga = fg[..., 3:4] / 255.0
    rgb = bg * (1 - ga) + 255.0 * ga
    v0 = N // 6
    V = N - 2 * v0
    rgb = rgb[v0:v0 + V, v0:v0 + V]
    outs = []
    for m in (shape_alpha(V, 'circle', 0.0), shape_alpha(V, 'square', 0.0)):
        outs.append(Image.fromarray(np.round(np.concatenate([rgb, m[..., None] * 255], 2)).astype(np.uint8), 'RGBA'))
    return outs


cell = 220
names = ['blue', 'crimson', 'emerald', 'aurora']
sheet = Image.new('RGB', (cell * 6, cell * len(names) * 2), (255, 255, 255))
for r, v in enumerate(names):
    for half, bgc in enumerate([(245, 245, 247), (24, 26, 30)]):
        y = (r * 2 + half) * cell
        tiles = masked_previews(432, VARIANTS[v]) + [compose(192, 'square', PAD_SQUARE, VARIANTS[v]), compose(192, 'circle', PAD_CIRCLE, VARIANTS[v]),
                                                     compose(48, 'square', PAD_SQUARE, VARIANTS[v]).resize((192, 192), Image.NEAREST),
                                                     compose(48, 'circle', PAD_CIRCLE, VARIANTS[v]).resize((192, 192), Image.NEAREST)]
        for ci, t in enumerate(tiles):
            t = t.resize((192, 192), Image.LANCZOS) if t.size != (192, 192) else t
            back = Image.new('RGB', (cell, cell), bgc)
            back.paste(t, (14, 14), t)
            sheet.paste(back, (ci * cell, y))
sheet.save(OUT + '/preview_sheet.png')

# safe-zone check image: full 108dp canvas, with 72dp visible circle and 66dp safe zone drawn
N = 432
bg = np.asarray(bg_layer(N, VARIANTS['blue'])).astype(np.float64)
fg = np.asarray(fg_layer(N)).astype(np.float64)
ga = fg[..., 3:4] / 255.0
canvas = Image.fromarray(np.round(bg * (1 - ga) + 255.0 * ga).astype(np.uint8), 'RGB')
d = ImageDraw.Draw(canvas)
for dp, colr in ((72, (255, 255, 0)), (66, (255, 0, 0))):
    rr = dp / 108.0 * N / 2
    d.ellipse([N / 2 - rr, N / 2 - rr, N / 2 + rr, N / 2 + rr], outline=colr, width=2)
canvas.save(OUT + '/safe_zone_check.png')
print('done')
