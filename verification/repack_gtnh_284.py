#!/usr/bin/env python3
"""Deterministically retarget the packaged SMOG 0.1.0 FML metadata and GT5U guard.

This is a binary compatibility patch, not a Gradle compilation or a game test.
It changes only four exact UTF-8 class constants in SmogMod.class.
"""
import argparse
import hashlib
import struct
import zipfile
from pathlib import Path

REPLACEMENTS = {
    "gregtech": "gregtech_nh",  # The GTNH sub-mod carries the real GT5U version.
    "5.09.54.229": "5.09.51.482",
    "SMOG requires GT5U 5.09.54.229, found ": "SMOG requires GT5U 5.09.51.482, found ",
    "required-after:gregtech;required-after:unimixins@[0.3.1,);required-after:gtnhlib":
        "required-after:gregtech;required-after:unimixins@[0.1.23,);required-after:gtnhlib",
}


def rewrite_utf8_pool(classfile):
    data = memoryview(classfile)
    assert data[:4].tobytes() == b'\xca\xfe\xba\xbe', 'not a Java class'
    count = struct.unpack_from('>H', data, 8)[0]
    dst = bytearray(data[:10])
    cursor = 10
    item = 1
    replaced = {}
    while item < count:
        tag = data[cursor]
        cursor += 1
        dst.append(tag)
        if tag == 1:
            size = struct.unpack_from('>H', data, cursor)[0]
            cursor += 2
            old = bytes(data[cursor:cursor + size])
            cursor += size
            value = old.decode('utf-8')
            if value in REPLACEMENTS:
                new = REPLACEMENTS[value].encode('utf-8')
                replaced[value] = replaced.get(value, 0) + 1
                dst += struct.pack('>H', len(new)) + new
            else:
                dst += struct.pack('>H', size) + old
        elif tag in (3, 4):
            dst += data[cursor:cursor + 4]
            cursor += 4
        elif tag in (5, 6):
            dst += data[cursor:cursor + 8]
            cursor += 8
            item += 1
        elif tag in (7, 8, 16, 19, 20):
            dst += data[cursor:cursor + 2]
            cursor += 2
        elif tag in (9, 10, 11, 12, 17, 18):
            dst += data[cursor:cursor + 4]
            cursor += 4
        elif tag == 15:
            dst += data[cursor:cursor + 3]
            cursor += 3
        else:
            raise RuntimeError('unrecognized constant pool tag %r' % tag)
        item += 1
    if any(replaced.get(k) != 1 for k in REPLACEMENTS):
        raise RuntimeError('unexpected constants: ' + str(replaced))
    return bytes(dst + data[cursor:]), replaced


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('original', type=Path)
    ap.add_argument('output', type=Path)
    args = ap.parse_args()
    new_path = 'org/smogmod/SmogMod.class'
    with zipfile.ZipFile(args.original) as original, zipfile.ZipFile(args.output, 'w') as result:
        for info in original.infolist():
            raw = original.read(info)
            if info.filename == new_path:
                raw, changes = rewrite_utf8_pool(raw)
                print('Patched:', changes)
            result.writestr(info, raw)
    with zipfile.ZipFile(args.output) as jar:
        assert jar.testzip() is None
        data = jar.read(new_path)
        # A raw substring check is invalid for 'gregtech', which is also a
        # substring of 'gregtech_nh' and of the unchanged dependency line.
        # The exact constant-pool rewrite count above enforces all four values.
        assert b'5.09.51.482' in data
        assert b'unimixins@[0.1.23,)' in data
        assert b'5.09.54.229' not in data
        assert b'unimixins@[0.3.1,)' not in data
        for entry in zipfile.ZipFile(args.original).namelist():
            if entry != new_path:
                with zipfile.ZipFile(args.original) as original:
                    assert jar.read(entry) == original.read(entry), 'unexpected mutation: ' + entry
    print('JAR:', args.output)
    print('SHA256:', hashlib.sha256(args.output.read_bytes()).hexdigest())


if __name__ == '__main__':
    main()
