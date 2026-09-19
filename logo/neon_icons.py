"""Generate the four neon launcher-icon variants from the neon grid artwork."""
import os
import numpy as np
from PIL import Image, ImageDraw
import neon_extract as E

ROOT = 'D:/telegramr'
RES = ROOT + '/TMessagesProj/src/main/res'
RES_SA = ROOT + '/TMessagesProj_AppStandalone/src/main/res'
OUT = E.OUT
DENS = [('mdpi', 1.0), ('hdpi', 1.5), ('xhdpi', 2.0), ('xxhdpi', 3.0), ('xxxhdpi', 4.0)]
SS = 4
DISC = (0x0A, 0x0A, 0x0E)

# adaptive icon: 108dp canvas, tile side = 56dp (rounded corners keep it inside the 66dp safe circle)
FG_TILE_DP, FG_CANVAS_DP = 56.0, 108.0
LEG_TILE_DP, LEG_CANVAS_DP = 42.0, 48.0      # legacy square, glow may bleed to the edge
RND_TILE_DP, RND_CANVAS_DP = 36.0, 48.0      # legacy round, tile inside the full-bleed disc

VARIANTS = [('crimson', 'red'), ('emerald', 'green'), ('azure', 'blue'), ('aurora', 'rainbow')]


def resize_rgba(rgba, box, N):
    """LANCZOS resample of a float RGBA quadrant, alpha-premultiplied so nothing fringes."""
    a = rgba[..., 3] / 255.0
    planes = [rgba[..., k] * a for k in range(3)] + [a * 255.0]
    outs = []
    for p in planes:
        im = Image.fromarray(p.astype(np.float32), 'F')
        outs.append(np.asarray(im.resize((N, N), Image.LANCZOS, box=box)).astype(np.float64))
    aa = np.clip(outs[3], 0, 255)
    pm = np.clip(np.stack(outs[:3], axis=2), 0, 255)
    an = np.maximum(aa / 255.0, 1e-5)[..., None]
    rgb = np.clip(pm / an, 0, 255)
    return rgb, aa / 255.0


def place(rgba, rect, N, tile_dp, canvas_dp):
    """Scale the artwork so the tile square is tile_dp of a canvas_dp canvas rendered at N px."""
    x0, y0, x1, y1, r = rect
    side = max(x1 - x0 + 1.0, y1 - y0 + 1.0)
    S = side * canvas_dp / tile_dp                 # source pixels shown by the whole canvas
    cx, cy = (x0 + x1 + 1) / 2.0, (y0 + y1 + 1) / 2.0
    P = int(np.ceil(S / 2.0)) + 4
    pad = np.zeros((rgba.shape[0] + 2 * P, rgba.shape[1] + 2 * P, 4))
    pad[P:P + rgba.shape[0], P:P + rgba.shape[1]] = rgba
    box = (cx + P - S / 2, cy + P - S / 2, cx + P + S / 2, cy + P + S / 2)
    return resize_rgba(pad, box, N)


def to_img(rgb, a):
    out = np.concatenate([rgb, (a * 255.0)[..., None]], axis=2)
    return Image.fromarray(np.round(np.clip(out, 0, 255)).astype(np.uint8), 'RGBA')


def disc_alpha(N):
    big = Image.new('L', (N * SS, N * SS), 0)
    ImageDraw.Draw(big).ellipse([0, 0, N * SS - 1, N * SS - 1], fill=255)
    return np.asarray(big.resize((N, N), Image.BOX)).astype(np.float64) / 255.0


def round_icon(rgba, rect, N):
    rgb, a = place(rgba, rect, N, RND_TILE_DP, RND_CANVAS_DP)
    d = disc_alpha(N)
    base = np.empty((N, N, 3))
    base[:] = DISC
    out = base * (1 - a[..., None]) + rgb * a[..., None]
    return to_img(out, d)


def content_dp(a, canvas_dp, th=0.02):
    ys, xs = np.where(a > th)
    if not len(xs):
        return 0.0
    N = a.shape[0]
    w = max(xs.max() - xs.min() + 1, ys.max() - ys.min() + 1)
    return w * canvas_dp / N


def save(img, path):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path, optimize=True)
    return path


def write_text(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w', encoding='utf-8', newline='\n') as f:
        f.write(text)
    print('write', os.path.relpath(path, ROOT).replace('\\', '/'))


ADAPTIVE = ('<?xml version="1.0" encoding="utf-8"?>\n'
            '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
            '    <background android:drawable="@drawable/icon_neon_background" />\n'
            '    <foreground android:drawable="@mipmap/icon_neon_%s_foreground" />\n'
            '    <monochrome android:drawable="@drawable/icon_glyph" />\n'
            '</adaptive-icon>\n')

BACKGROUND = ('<?xml version="1.0" encoding="utf-8"?>\n'
              '<shape xmlns:android="http://schemas.android.com/apk/res/android">\n'
              '    <solid android:color="#0A0A0E" />\n'
              '</shape>\n')


def main():
    data = E.build()
    written = []
    for v, src in VARIANTS:
        rgba, rect = data[('neon', src)]
        for dn, dd in DENS:
            NA, NL = int(round(108 * dd)), int(round(48 * dd))
            rgb, a = place(rgba, rect, NA, FG_TILE_DP, FG_CANVAS_DP)
            written.append(save(to_img(rgb, a), '%s/mipmap-%s/icon_neon_%s_foreground.png' % (RES, dn, v)))
            if dd == 4.0:
                print('  %-8s foreground content %.1f dp of 108dp (safe zone 66dp), tile %.0f dp'
                      % (v, content_dp(a, 108.0), FG_TILE_DP))
            lrgb, la = place(rgba, rect, NL, LEG_TILE_DP, LEG_CANVAS_DP)
            leg = to_img(lrgb, la)
            written.append(save(leg, '%s/mipmap-%s/icon_neon_%s_launcher.png' % (RES, dn, v)))
            written.append(save(leg, '%s/mipmap-%s/icon_neon_%s_launcher_sa.png' % (RES_SA, dn, v)))
            written.append(save(round_icon(rgba, rect, NL), '%s/mipmap-%s/icon_neon_%s_launcher_round.png' % (RES, dn, v)))
        write_text('%s/mipmap-anydpi-v26/icon_neon_%s_launcher.xml' % (RES, v), ADAPTIVE % v)
        write_text('%s/mipmap-anydpi-v26/icon_neon_%s_launcher_round.xml' % (RES, v), ADAPTIVE % v)
        write_text('%s/mipmap-anydpi-v26/icon_neon_%s_launcher_sa.xml' % (RES_SA, v), ADAPTIVE % v)
    write_text(RES + '/drawable/icon_neon_background.xml', BACKGROUND)
    print('bitmaps written:', len(written))


if __name__ == '__main__':
    main()
