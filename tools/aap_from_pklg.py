#!/usr/bin/env python3
"""
Extract AirPods traffic from an Apple PacketLogger capture (.pklg) so the "Play Sound on case"
command can be identified.

Prints, in time order:
  * every AAP packet (L2CAP PSM 0x1001) in both directions, hex, with the ACL direction;
  * every ATT (BLE GATT) write/notification, so a Find-My-Network GATT command is visible too;
  * L2CAP connection setup, so you can see which PSMs were opened.

Usage:  python3 tools/aap_from_pklg.py capture.pklg [--around HH:MM:SS --window 20]

The .pklg record format (little/big endian varies by producer; both are tried):
  u32 length | u32 ts_seconds | u32 ts_microseconds | u8 type | payload
  type: 0x00 HCI cmd, 0x01 HCI event, 0x02 ACL host→controller (sent), 0x03 ACL received,
        0xFB/0xFC/0xFD/0xFE notes/config.
"""
import argparse
import datetime as dt
import struct
import sys

PSM_AAP = 0x1001
ATT_CID = 0x0004
SIG_CID = 0x0001
L2CAP_CONN_REQ, L2CAP_CONN_RSP = 0x02, 0x03
ATT_OPS = {0x12: "ATT Write Req", 0x52: "ATT Write Cmd", 0x1B: "ATT Notify", 0x1D: "ATT Indicate", 0x0B: "ATT Read Rsp", 0x0A: "ATT Read Req"}


def records(data, big_endian):
    fmt = ">III" if big_endian else "<III"
    off = 0
    while off + 13 <= len(data):
        length, secs, usecs = struct.unpack_from(fmt, data, off)
        if length < 9 or off + 4 + length > len(data):
            return
        typ = data[off + 12]
        payload = data[off + 13: off + 4 + length]
        yield secs + usecs / 1e6, typ, payload
        off += 4 + length


def detect_endian(data):
    for be in (True, False):
        n = 0
        for _ in records(data, be):
            n += 1
            if n > 50:
                return be
    return True


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("pklg")
    ap.add_argument("--around", help="local time HH:MM:SS of the Play Sound tap")
    ap.add_argument("--window", type=float, default=20.0, help="seconds either side of --around")
    args = ap.parse_args()

    data = open(args.pklg, "rb").read()
    be = detect_endian(data)

    # CID → (psm, direction label) learned from L2CAP signalling.
    pending = {}   # (handle, identifier) → psm
    cid_psm = {}   # (handle, cid) → psm
    reassembly = {}

    t_center = None
    if args.around:
        hh, mm, ss = (int(x) for x in args.around.split(":"))
        first = next(records(data, be))[0]
        day = dt.datetime.fromtimestamp(first).replace(hour=hh, minute=mm, second=ss, microsecond=0)
        t_center = day.timestamp()

    for ts, typ, p in records(data, be):
        if typ not in (0x02, 0x03) or len(p) < 4:
            continue
        if t_center and abs(ts - t_center) > args.window:
            continue
        direction = "iPhone→AirPods" if typ == 0x02 else "AirPods→iPhone"
        hc, acl_len = struct.unpack_from("<HH", p, 0)
        handle, pb = hc & 0x0FFF, (hc >> 12) & 0x3
        body = p[4:4 + acl_len]
        key = (typ, handle)
        if pb == 0x01:  # continuation fragment
            buf = reassembly.get(key)
            if buf is None:
                continue
            buf += body
        else:
            buf = bytearray(body)
        if len(buf) < 4:
            reassembly[key] = buf
            continue
        l2_len, cid = struct.unpack_from("<HH", buf, 0)
        if len(buf) < 4 + l2_len:
            reassembly[key] = buf
            continue
        reassembly.pop(key, None)
        sdu = bytes(buf[4:4 + l2_len])
        stamp = dt.datetime.fromtimestamp(ts).strftime("%H:%M:%S.%f")[:-3]

        if cid == SIG_CID and len(sdu) >= 4:
            code, ident, slen = sdu[0], sdu[1], struct.unpack_from("<H", sdu, 2)[0]
            if code == L2CAP_CONN_REQ and slen >= 4:
                psm, scid = struct.unpack_from("<HH", sdu, 4)
                pending[(handle, ident)] = psm
                print(f"{stamp} {direction} L2CAP connect request PSM=0x{psm:04x} scid=0x{scid:04x}")
            elif code == L2CAP_CONN_RSP and slen >= 8:
                dcid, scid, result, _ = struct.unpack_from("<HHHH", sdu, 4)
                psm = pending.pop((handle, ident), None)
                if psm is not None and result == 0:
                    cid_psm[(handle, dcid)] = psm
                    cid_psm[(handle, scid)] = psm
                    print(f"{stamp} {direction} L2CAP connected PSM=0x{psm:04x} cids=0x{dcid:04x}/0x{scid:04x}")
            continue

        if cid == ATT_CID and sdu:
            op = sdu[0]
            if op in ATT_OPS:
                hdl = struct.unpack_from("<H", sdu, 1)[0] if len(sdu) >= 3 else -1
                print(f"{stamp} {direction} {ATT_OPS[op]} handle=0x{hdl:04x} data={sdu[3:].hex(' ')}")
            continue

        psm = cid_psm.get((handle, cid))
        if psm == PSM_AAP or (psm is None and sdu[:4] == b"\x04\x00\x04\x00"):
            print(f"{stamp} {direction} AAP {sdu.hex(' ')}")


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)
    main()
