import os
import numpy as np
from PIL import Image, ImageDraw

OUT = 'C:/Users/boq/AppData/Local/Temp/claude/d--telegramr/4b7ab1a4-61fa-4b83-8b73-41768fb37a83/scratchpad'
RES = 'D:/telegramr/TMessagesProj/src/main/res'
EPS = float(os.environ.get('TRACE_EPS', '0.7'))          # Douglas-Peucker tolerance, source px (glyph is ~683 px wide)
CORNER_DEG = float(os.environ.get('TRACE_CORNER', '38'))  # turning angle above which a vertex stays sharp
WRITE = os.environ.get('TRACE_WRITE', '1') == '1'

G = np.load(OUT + '/glyph_cov.npy')
mx, my, mr, GW, GH = [float(v) for v in open(OUT + '/glyph_meta.txt').read().split()]
GW, GH = int(GW), int(GH)
MEC_C = np.array([mx, my])


def marching_squares(A, level=0.5):
    A = np.pad(A, 1, constant_values=0.0)
    B = A >= level

    def hp(i, j):
        a, b = A[i, j], A[i, j + 1]
        return (j + (level - a) / (b - a), float(i))

    def vp(i, j):
        a, b = A[i, j], A[i + 1, j]
        return (float(j), i + (level - a) / (b - a))

    table = {1: [('l', 'b')], 2: [('b', 'r')], 3: [('l', 'r')], 4: [('t', 'r')], 6: [('t', 'b')], 7: [('l', 't')],
             8: [('t', 'l')], 9: [('t', 'b')], 11: [('t', 'r')], 12: [('l', 'r')], 13: [('b', 'r')], 14: [('l', 'b')]}

    def key(side, i, j):
        if side == 't':
            return ('h', i, j)
        if side == 'b':
            return ('h', i + 1, j)
        if side == 'l':
            return ('v', i, j)
        return ('v', i, j + 1)

    adj = {}
    idx = (B[:-1, :-1] * 8 + B[:-1, 1:] * 4 + B[1:, 1:] * 2 + B[1:, :-1] * 1).astype(int)
    ii, jj = np.nonzero((idx != 0) & (idx != 15))
    for i, j in zip(ii.tolist(), jj.tolist()):
        c = int(idx[i, j])
        if c == 5 or c == 10:
            center = (A[i, j] + A[i, j + 1] + A[i + 1, j] + A[i + 1, j + 1]) / 4.0 >= level
            if c == 5:
                pairs = [('t', 'r'), ('l', 'b')] if center else [('t', 'l'), ('b', 'r')]
            else:
                pairs = [('t', 'l'), ('b', 'r')] if center else [('t', 'r'), ('l', 'b')]
        else:
            pairs = table[c]
        for a, b in pairs:
            ka, kb = key(a, i, j), key(b, i, j)
            adj.setdefault(ka, []).append(kb)
            adj.setdefault(kb, []).append(ka)
    seen = set()
    loops = []
    for start in adj:
        if start in seen:
            continue
        loop = [start]
        seen.add(start)
        prev, cur = None, start
        while True:
            nb = adj[cur]
            nxt = nb[0] if nb[0] != prev else (nb[1] if len(nb) > 1 else None)
            if nxt is None or nxt == start or nxt in seen:
                break
            loop.append(nxt)
            seen.add(nxt)
            prev, cur = cur, nxt
        pts = [(hp(k[1], k[2]) if k[0] == 'h' else vp(k[1], k[2])) for k in loop]
        loops.append(np.array([(x - 1.0, y - 1.0) for x, y in pts]))
    return loops


def dp(P, eps):
    n = len(P)
    if n < 3:
        return P
    keep = np.zeros(n, bool)
    keep[0] = keep[-1] = True
    stack = [(0, n - 1)]
    while stack:
        a, b = stack.pop()
        if b - a < 2:
            continue
        A, Bp = P[a], P[b]
        d = Bp - A
        L = float(np.hypot(d[0], d[1]))
        seg = P[a + 1:b]
        if L < 1e-12:
            dist = np.hypot(seg[:, 0] - A[0], seg[:, 1] - A[1])
        else:
            dist = np.abs((seg[:, 0] - A[0]) * d[1] - (seg[:, 1] - A[1]) * d[0]) / L
        k = int(dist.argmax())
        if dist[k] > eps:
            keep[a + 1 + k] = True
            stack.append((a, a + 1 + k))
            stack.append((a + 1 + k, b))
    return P[keep]


def simplify_loop(P, eps):
    i1 = int(np.hypot(P[:, 0] - P[0, 0], P[:, 1] - P[0, 1]).argmax())
    A = dp(P[0:i1 + 1], eps)
    B = dp(np.vstack([P[i1:], P[:1]]), eps)
    return np.vstack([A[:-1], B[:-1]])


def loop_commands(P, corner_deg):
    """Return list of ('M'|'L'|'Q', points) in px; quadratic smoothing between non-corner vertices."""
    n = len(P)
    corner = []
    for i in range(n):
        a = P[i] - P[i - 1]
        b = P[(i + 1) % n] - P[i]
        cosang = np.dot(a, b) / (np.linalg.norm(a) * np.linalg.norm(b) + 1e-12)
        corner.append(np.degrees(np.arccos(np.clip(cosang, -1, 1))) > corner_deg)
    mid = [(P[i] + P[(i + 1) % n]) / 2.0 for i in range(n)]
    cmds = []
    if any(corner):
        s = corner.index(True)
        cmds.append(('M', [P[s]]))
        for k in range(1, n):
            i = (s + k) % n
            if corner[i]:
                cmds.append(('L', [P[i]]))
            else:
                cmds.append(('Q', [P[i], mid[i]]))
    else:
        cmds.append(('M', [mid[n - 1]]))
        for i in range(n):
            cmds.append(('Q', [P[i], mid[i]]))
    return cmds


def flatten(cmds, samples=6):
    pts = []
    cur = None
    for op, ps in cmds:
        if op == 'M':
            cur = ps[0]
            pts.append(cur)
        elif op == 'L':
            cur = ps[0]
            pts.append(cur)
        else:
            c, e = ps
            for t in np.linspace(0, 1, samples + 1)[1:]:
                pts.append((1 - t) ** 2 * cur + 2 * (1 - t) * t * c + t ** 2 * e)
            cur = e
    return np.array(pts)


def path_data(loops_cmds, transform, prec=2):
    def f(p):
        x, y = transform(p)
        return ('%.*f,%.*f' % (prec, x, prec, y))
    parts = []
    for cmds in loops_cmds:
        s = ''
        for op, ps in cmds:
            s += op + ' '.join(f(p) for p in ps)
        parts.append(s + 'Z')
    return ''.join(parts)


loops = marching_squares(G)
print('raw loops', len(loops), 'lengths', sorted([len(l) for l in loops], reverse=True)[:12], 'mask px', int((G >= 0.5).sum()))
loops = [l for l in loops if len(l) >= 8]
simp = [simplify_loop(l, EPS) for l in loops]
cmds = [loop_commands(l, CORNER_DEG) for l in simp]
nverts = sum(len(l) for l in simp)
print('loops', len(loops), 'vertices after DP', nverts, [len(l) for l in simp])

# ---- verification: rasterise (even-odd) at 2x and compare with the mask ----
K = 2
acc = np.zeros((GH * K, GW * K), bool)
for c in cmds:
    poly = flatten(c) * K
    img = Image.new('L', (GW * K, GH * K), 0)
    ImageDraw.Draw(img).polygon([tuple(p) for p in poly], fill=1)
    acc ^= np.asarray(img).astype(bool)
ref = np.asarray(Image.fromarray((G >= 0.5).astype(np.uint8) * 255, 'L').resize((GW * K, GH * K), Image.NEAREST)) > 0
inter = (acc & ref).sum()
union = (acc | ref).sum()
print('IoU', round(inter / union, 5), 'traced px', int(acc.sum()), 'ref px', int(ref.sum()))
diff = Image.fromarray(np.where(acc & ~ref, 255, 0).astype(np.uint8) + 0, 'L').convert('RGB')
vis = np.zeros((GH * K, GW * K, 3), np.uint8)
vis[ref & acc] = (255, 255, 255)
vis[ref & ~acc] = (255, 60, 60)
vis[~ref & acc] = (60, 120, 255)
Image.fromarray(vis, 'RGB').save(OUT + '/trace_check.png')

# ---- write vector drawables ----
def tf(vp_center, vp_R):
    s = vp_R / mr
    return lambda p: (vp_center + (p[0] - MEC_C[0]) * s, vp_center + (p[1] - MEC_C[1]) * s)

glyph_108 = path_data(cmds, tf(54.0, 30.0))
glyph_320 = path_data(cmds, tf(160.0, 100.0 * 30.0 / 36.0))
print('pathData chars (108dp)', len(glyph_108))

icon_glyph = ('<?xml version="1.0" encoding="utf-8"?>\n'
              '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
              '    android:width="108dp"\n'
              '    android:height="108dp"\n'
              '    android:viewportWidth="108"\n'
              '    android:viewportHeight="108">\n'
              '    <path\n'
              '        android:fillColor="#FFFFFFFF"\n'
              '        android:fillType="evenOdd"\n'
              '        android:pathData="%s" />\n'
              '</vector>\n') % glyph_108

splash = ('<?xml version="1.0" encoding="utf-8"?>\n'
          '<animated-vector\n'
          '    xmlns:android="http://schemas.android.com/apk/res/android"\n'
          '    xmlns:aapt="http://schemas.android.com/aapt">\n'
          '    <aapt:attr name="android:drawable">\n'
          '        <vector\n'
          '            android:name="splash"\n'
          '            android:width="320dp"\n'
          '            android:height="320dp"\n'
          '            android:viewportWidth="320"\n'
          '            android:viewportHeight="320">\n'
          '            <group\n'
          '                android:name="icon"\n'
          '                android:pivotX="160"\n'
          '                android:pivotY="160"\n'
          '                android:scaleX="0.82"\n'
          '                android:scaleY="0.82">\n'
          '                <path\n'
          '                    android:name="disc"\n'
          '                    android:fillAlpha="0"\n'
          '                    android:pathData="M160,60 A100,100 0 1 1 160,260 A100,100 0 1 1 160,60 Z">\n'
          '                    <aapt:attr name="android:fillColor">\n'
          '                        <gradient\n'
          '                            android:type="linear"\n'
          '                            android:startX="60"\n'
          '                            android:startY="60"\n'
          '                            android:endX="260"\n'
          '                            android:endY="260">\n'
          '                            <item android:offset="0" android:color="#FF00E8FF" />\n'
          '                            <item android:offset="0.5" android:color="#FF0080F8" />\n'
          '                            <item android:offset="1" android:color="#FF0048F4" />\n'
          '                        </gradient>\n'
          '                    </aapt:attr>\n'
          '                </path>\n'
          '                <path\n'
          '                    android:name="glyph"\n'
          '                    android:fillColor="#FFFFFFFF"\n'
          '                    android:fillAlpha="0"\n'
          '                    android:fillType="evenOdd"\n'
          '                    android:pathData="%s" />\n'
          '            </group>\n'
          '        </vector>\n'
          '    </aapt:attr>\n'
          '    <target android:name="icon">\n'
          '        <aapt:attr name="android:animation">\n'
          '            <set>\n'
          '                <objectAnimator\n'
          '                    android:propertyName="scaleX"\n'
          '                    android:duration="420"\n'
          '                    android:valueFrom="0.82"\n'
          '                    android:valueTo="1"\n'
          '                    android:valueType="floatType"\n'
          '                    android:interpolator="@android:anim/overshoot_interpolator" />\n'
          '                <objectAnimator\n'
          '                    android:propertyName="scaleY"\n'
          '                    android:duration="420"\n'
          '                    android:valueFrom="0.82"\n'
          '                    android:valueTo="1"\n'
          '                    android:valueType="floatType"\n'
          '                    android:interpolator="@android:anim/overshoot_interpolator" />\n'
          '            </set>\n'
          '        </aapt:attr>\n'
          '    </target>\n'
          '    <target android:name="disc">\n'
          '        <aapt:attr name="android:animation">\n'
          '            <objectAnimator\n'
          '                android:propertyName="fillAlpha"\n'
          '                android:duration="180"\n'
          '                android:valueFrom="0"\n'
          '                android:valueTo="1"\n'
          '                android:valueType="floatType"\n'
          '                android:interpolator="@android:interpolator/fast_out_slow_in" />\n'
          '        </aapt:attr>\n'
          '    </target>\n'
          '    <target android:name="glyph">\n'
          '        <aapt:attr name="android:animation">\n'
          '            <objectAnimator\n'
          '                android:propertyName="fillAlpha"\n'
          '                android:duration="180"\n'
          '                android:valueFrom="0"\n'
          '                android:valueTo="1"\n'
          '                android:valueType="floatType"\n'
          '                android:interpolator="@android:interpolator/fast_out_slow_in" />\n'
          '        </aapt:attr>\n'
          '    </target>\n'
          '</animated-vector>\n') % glyph_320

if WRITE:
    with open(RES + '/drawable/icon_glyph.xml', 'w', encoding='utf-8', newline='\n') as f:
        f.write(icon_glyph)
    with open(RES + '/drawable/tg_splash_320.xml', 'w', encoding='utf-8', newline='\n') as f:
        f.write(splash)
    print('wrote icon_glyph.xml and tg_splash_320.xml')
