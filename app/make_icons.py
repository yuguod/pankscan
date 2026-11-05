import zlib, struct, os

BG = (24, 95, 165)
FG = (255, 255, 255)
LASER = (255, 90, 90)
PATTERN = [1,1,2,1,3,2,1,2,1,1,2,1,3,1,2,1,1,2,2,1,1,3,1,1,2,2,1,2,2,1,2]


def make(size, path):
    px = [[BG + (255,) for _ in range(size)] for _ in range(size)]

    quiet = int(size * 0.16)
    total = sum(PATTERN)
    module = (size - quiet * 2) / float(total)
    bar_h = int(size * 0.40)
    top = (size - bar_h) // 2

    x = float(quiet)
    for i, w in enumerate(PATTERN):
        wpx = module * w
        if i % 2 == 0:
            x0 = int(round(x))
            x1 = int(round(x + wpx))
            for yy in range(top, top + bar_h):
                for xx in range(x0, min(x1, size)):
                    px[yy][xx] = FG + (255,)
        x += wpx

    ly = size // 2
    lt = max(2, int(size * 0.013))
    for yy in range(ly - lt // 2, ly - lt // 2 + lt):
        for xx in range(quiet, size - quiet):
            px[yy][xx] = LASER + (235 if lt > 2 else 255,)

    raw = b''
    for row in px:
        raw += b'\x00' + b''.join(struct.pack('BBBB', *c) for c in row)

    def chunk(tag, data):
        c = struct.pack('>I', len(data)) + tag + data
        return c + struct.pack('>I', zlib.crc32(tag + data) & 0xffffffff)

    png = b'\x89PNG\r\n\x1a\n'
    png += chunk(b'IHDR', struct.pack('>IIBBBBB', size, size, 8, 6, 0, 0, 0))
    png += chunk(b'IDAT', zlib.compress(raw, 9))
    png += chunk(b'IEND', b'')
    with open(path, 'wb') as f:
        f.write(png)


base = os.path.dirname(os.path.abspath(__file__))
for s in (192, 512):
    make(s, os.path.join(base, 'icons', 'icon-%d.png' % s))
    print('icon-%d.png' % s)
