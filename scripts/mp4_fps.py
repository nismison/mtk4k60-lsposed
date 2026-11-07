#!/usr/bin/env python3
"""Print the *real* stream frame rate of an MP4 file (stts based).

Usage: python3 scripts/mp4_fps.py /path/to/file.mp4

A "120 fps" label in MIUI/Gallery info can be bogus: MediaRecorder/MTK sometimes
writes a nominal rate into the container while the sensor still delivers 30 fps.
This script counts actual samples and their spacing, so 202 samples / 6.745 s
prints 29.95 instead of 120.
"""
import glob
import os
import struct
import sys


def boxes(buf, start=0, end=None):
    end = len(buf) if end is None else end
    i = start
    while i + 8 <= end:
        size = struct.unpack('>I', buf[i:i + 4])[0]
        typ = buf[i + 4:i + 8].decode('latin1')
        hdr = 8
        if size == 1:
            size = struct.unpack('>Q', buf[i + 8:i + 16])[0]
            hdr = 16
        elif size == 0:
            size = end - i
        yield typ, i, hdr, size
        i += size


def track_info(trak):
    info = {'timescale': None, 'duration': None, 'samples': 0, 'stts': [], 'handler': None}

    def walk(b):
        for typ, off, hdr, size in boxes(b):
            if typ in ('mdia', 'minf', 'stbl'):
                walk(b[off + hdr:off + size])
            elif typ == 'mdhd':
                if b[off + hdr] == 0:
                    ts, dur = struct.unpack('>II', b[off + hdr + 12:off + hdr + 20])
                else:
                    ts, dur = struct.unpack('>IQ', b[off + hdr + 20:off + hdr + 32])
                info['timescale'], info['duration'] = ts, dur
            elif typ == 'hdlr':
                info['handler'] = b[off + hdr + 8:off + hdr + 12].decode('latin1')
            elif typ == 'stts':
                count = struct.unpack('>I', b[off + hdr + 4:off + hdr + 8])[0]
                p = off + hdr + 8
                for _ in range(count):
                    c, d = struct.unpack('>II', b[p:p + 8])
                    info['stts'].append((c, d))
                    info['samples'] += c
                    p += 8

    walk(trak)
    return info


def main(path):
    data = open(path, 'rb').read()
    out = []
    for typ, off, hdr, size in boxes(data):
        if typ != 'moov':
            continue
        moov = data[off + hdr:off + size]
        for t2, o2, h2, s2 in boxes(moov):
            if t2 != 'trak':
                continue
            info = track_info(moov[o2 + h2:o2 + s2])
            if not info['timescale'] or not info['duration']:
                continue
            sec = info['duration'] / info['timescale']
            fps = info['samples'] * info['timescale'] / info['duration']
            out.append((info['handler'], sec, info['samples'], fps, info['stts'][:2]))
    if not out:
        print('no moov/trak found (file still being written?)')
        return 2
    rc = 0
    for handler, sec, samples, fps, stts in out:
        print('%-4s  %.2fs  %5d samples  %6.2f fps  stts=%s'
              % (handler, sec, samples, fps, stts))
        if handler == 'vide' and fps < 55:
            print('     ! video track is not 60fps')
            rc = 1
    return rc


if __name__ == '__main__':
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(2)
    target = sys.argv[1]
    if any(ch in target for ch in '*?['):
        target = sorted(glob.glob(target))[-1]
    if not os.path.isfile(target):
        print('no such file: %s' % target)
        sys.exit(2)
    sys.exit(main(target))
