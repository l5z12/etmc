#!/usr/bin/env python3
"""Generate a tiny import-satisfying ``Packet.dll`` shim for the Windows EasyTier native.

Why this exists
---------------
``easytier_ffi.dll`` (built with ``--features easytier/full``) carries a *load-time*
import of Npcap/WinPcap's ``Packet.dll``. On a machine without Npcap installed the
Windows loader fails ``easytier_ffi.dll`` outright (ERROR_MOD_NOT_FOUND) before any
etmc code runs -- which is why players used to have to drop a ``Packet.dll`` next to
the game themselves. etmc, however, always runs EasyTier in **no_tun** mode, where the
pcap code path is dormant and those functions are never actually *called* -- the import
only needs to *resolve*.

So instead of redistributing Npcap's proprietary DLL, we ship a minimal, self-authored
shim that exports exactly the symbols ``easytier_ffi.dll`` imports, each a no-op that
returns 0 (a safe "failed"/NULL for every one of them). The loader is satisfied; nothing
is ever invoked. ``NativeLoader`` pre-loads this shim by full path before opening
``easytier_ffi.dll``, so its static import binds to the already-loaded module. On a
machine that *does* have Npcap, that real module wins (same base name) and the shim is a
harmless no-op.

The exported name list is derived from the real DLL's import table, so a rebuilt
``easytier_ffi.dll`` that pulls in different ``Packet.dll`` functions is handled
automatically -- just re-run this script.

Usage:
    uv run --with pefile python tools/gen_packet_shim.py <easytier_ffi.dll> <out Packet.dll>

The output is a valid PE32+ DLL with no compiler involved: one r-x section holds a shared
return-0 stub plus a hand-built export directory. Entry point is 0 (no DllMain), and it is
marked relocatable with zero fixups (all content is base-independent), so ASLR can map it
anywhere. The shim's machine (and stub instructions) are taken from the native it sits beside,
so it is correct for x86-64 today and for arm64 the moment a ``windows-aarch64`` native exists.
"""
from __future__ import annotations

import struct
import sys

# Fallback if the import table can't be read for some reason; kept in sync with what
# `--features easytier/full` currently pulls in (Npcap low-level Packet.dll API).
FALLBACK_EXPORTS = [
    "PacketAllocatePacket", "PacketCloseAdapter", "PacketFreePacket",
    "PacketGetAdapterNames", "PacketInitPacket", "PacketOpenAdapter",
    "PacketReceivePacket", "PacketSendPacket", "PacketSetBuff",
    "PacketSetHwFilter", "PacketSetMinToCopy",
]

# COFF machine -> a "return 0" stub in that arch's instructions. The stub is never actually executed
# (no_tun never calls these) -- only its address is resolved -- but the DLL's machine MUST match the
# loading process's arch or the loader rejects it outright, so we emit a correct DLL per machine.
IMAGE_FILE_MACHINE_AMD64 = 0x8664
IMAGE_FILE_MACHINE_ARM64 = 0xAA64
STUBS = {
    IMAGE_FILE_MACHINE_AMD64: bytes((0x31, 0xC0, 0xC3)),                    # xor eax,eax ; ret
    IMAGE_FILE_MACHINE_ARM64: bytes((0x00, 0x00, 0x80, 0x52,               # mov  w0, #0
                                     0xC0, 0x03, 0x5F, 0xD6)),             # ret
}


def read_machine(dll_path: str) -> int:
    """Read the COFF ``Machine`` field of a PE so the shim matches the native beside it."""
    with open(dll_path, "rb") as fh:
        data = fh.read(0x400)
    pe = struct.unpack_from("<I", data, 0x3C)[0]
    if data[pe:pe + 4] != b"PE\x00\x00":
        raise ValueError(f"{dll_path}: not a PE file")
    return struct.unpack_from("<H", data, pe + 4)[0]


def discover_exports(dll_path: str) -> list[str]:
    """Return the function names ``dll_path`` imports from ``packet.dll`` (any case)."""
    try:
        import pefile
    except ImportError:
        print("pefile not available; using the hard-coded fallback export list", file=sys.stderr)
        return sorted(FALLBACK_EXPORTS)

    pe = pefile.PE(dll_path, fast_load=True)
    pe.parse_data_directories(
        directories=[pefile.DIRECTORY_ENTRY["IMAGE_DIRECTORY_ENTRY_IMPORT"]]
    )
    names: list[str] = []
    for entry in getattr(pe, "DIRECTORY_ENTRY_IMPORT", []) or []:
        if entry.dll.decode().lower() == "packet.dll":
            for imp in entry.imports:
                if imp.name:
                    names.append(imp.name.decode())
    pe.close()
    if not names:
        print("no packet.dll imports found; using fallback list", file=sys.stderr)
        names = list(FALLBACK_EXPORTS)
    return sorted(set(names))  # GetProcAddress binary-searches names => must be sorted


def _align(value: int, alignment: int) -> int:
    return (value + alignment - 1) & ~(alignment - 1)


def build_dll(exports: list[str], machine: int = IMAGE_FILE_MACHINE_AMD64,
              dll_name: str = "Packet.dll") -> bytes:
    stub = STUBS.get(machine)
    if stub is None:
        raise ValueError(f"unsupported machine {machine:#06x}; add a return-0 stub to STUBS")

    FILE_ALIGN = 0x200
    SECT_ALIGN = 0x1000
    IMAGE_BASE = 0x180000000
    SECT_RVA = 0x1000

    n = len(exports)

    # --- Lay out the single r-x section: stub code, export directory, arrays, strings. ---
    body = bytearray()

    def cur_rva() -> int:
        return SECT_RVA + len(body)

    # Shared function stub: every export returns 0 (== NULL/FALSE) in the arch's result register.
    stub_rva = cur_rva()
    body += stub

    # Align the export directory to 4 bytes for tidy 32-bit fields.
    while len(body) % 4:
        body += b"\x00"

    export_dir_rva = cur_rva()
    body += b"\x00" * 40  # placeholder for IMAGE_EXPORT_DIRECTORY, patched below

    eat_rva = cur_rva()
    body += b"".join(struct.pack("<I", stub_rva) for _ in range(n))  # all share the stub

    ent_rva = cur_rva()
    ent_placeholder = len(body)
    body += b"\x00" * (4 * n)  # name RVAs, patched once strings are laid out

    ord_rva = cur_rva()
    # names[i] (sorted) maps to EAT slot i
    body += b"".join(struct.pack("<H", i) for i in range(n))

    name_rva = cur_rva()
    body += dll_name.encode("ascii") + b"\x00"

    func_name_rvas: list[int] = []
    for name in exports:
        func_name_rvas.append(cur_rva())
        body += name.encode("ascii") + b"\x00"

    # Patch the ENT (AddressOfNames) now that string RVAs are known.
    struct.pack_into(
        "<" + "I" * n, body, ent_placeholder, *func_name_rvas
    )

    # Patch the export directory.
    export_dir = struct.pack(
        "<IIHHIIIIIII",
        0,            # Characteristics
        0,            # TimeDateStamp
        0, 0,         # Major/MinorVersion
        name_rva,     # Name (RVA of "Packet.dll")
        1,            # Base (ordinal base)
        n,            # NumberOfFunctions
        n,            # NumberOfNames
        eat_rva,      # AddressOfFunctions
        ent_rva,      # AddressOfNames
        ord_rva,      # AddressOfNameOrdinals
    )
    body[export_dir_rva - SECT_RVA: export_dir_rva - SECT_RVA + 40] = export_dir

    export_size = len(body) - (export_dir_rva - SECT_RVA)
    section_vsize = len(body)
    raw_body = bytes(body) + b"\x00" * (_align(section_vsize, FILE_ALIGN) - section_vsize)

    # --- Headers ---
    size_of_headers = _align(64 + 4 + 20 + 240 + 40, FILE_ALIGN)  # -> 0x200
    ptr_to_raw = size_of_headers
    size_of_image = SECT_RVA + _align(section_vsize, SECT_ALIGN)

    dos = bytearray(64)
    dos[0:2] = b"MZ"
    struct.pack_into("<I", dos, 0x3C, 64)  # e_lfanew -> PE header right after the DOS header

    coff = struct.pack(
        "<HHIIIHH",
        machine,  # Machine (matches the native this shim sits beside)
        1,        # NumberOfSections
        0,        # TimeDateStamp
        0, 0,     # symbol table (none)
        240,      # SizeOfOptionalHeader (PE32+)
        0x2022,   # DLL | EXECUTABLE_IMAGE | LARGE_ADDRESS_AWARE
    )

    data_dirs = [(0, 0)] * 16
    data_dirs[0] = (export_dir_rva, export_size)  # IMAGE_DIRECTORY_ENTRY_EXPORT
    data_dir_bytes = b"".join(struct.pack("<II", rva, sz) for rva, sz in data_dirs)

    opt = struct.pack(
        "<HBBIIIII",
        0x20B,            # Magic PE32+
        0, 0,             # linker version
        len(raw_body),    # SizeOfCode
        0,                # SizeOfInitializedData
        0,                # SizeOfUninitializedData
        0,                # AddressOfEntryPoint = 0 -> no DllMain
        SECT_RVA,         # BaseOfCode
    )
    opt += struct.pack("<Q", IMAGE_BASE)  # ImageBase
    opt += struct.pack(
        "<IIHHHHHHIIIIHH",
        SECT_ALIGN,       # SectionAlignment
        FILE_ALIGN,       # FileAlignment
        6, 0,             # OS version
        0, 0,             # image version
        6, 0,             # subsystem version
        0,                # Win32VersionValue
        size_of_image,    # SizeOfImage
        size_of_headers,  # SizeOfHeaders
        0,                # CheckSum (0 is fine for a user-mode DLL)
        3,                # Subsystem = WINDOWS_CUI
        0x0140,           # DllCharacteristics = DYNAMIC_BASE | NX_COMPAT
    )
    opt += struct.pack(
        "<QQQQII",
        0x100000, 0x1000,  # stack reserve/commit
        0x100000, 0x1000,  # heap reserve/commit
        0,                 # LoaderFlags
        16,                # NumberOfRvaAndSizes
    )
    opt += data_dir_bytes
    assert len(opt) == 240, len(opt)

    section = struct.pack(
        "<8sIIIIIIHHI",
        b".text",         # name (zero-padded)
        section_vsize,    # VirtualSize
        SECT_RVA,         # VirtualAddress
        len(raw_body),    # SizeOfRawData
        ptr_to_raw,       # PointerToRawData
        0, 0,             # relocations / line numbers
        0, 0,             # counts
        0x60000020,       # CODE | MEM_EXECUTE | MEM_READ
    )

    headers = bytes(dos) + b"PE\x00\x00" + coff + opt + section
    headers += b"\x00" * (size_of_headers - len(headers))
    return headers + raw_body


def main() -> int:
    if len(sys.argv) != 3:
        print(__doc__)
        print("error: expected <easytier_ffi.dll> <out Packet.dll>", file=sys.stderr)
        return 2
    src, out = sys.argv[1], sys.argv[2]
    machine = read_machine(src)  # match the native's arch (x64 today, arm64 if ever built)
    exports = discover_exports(src)
    data = build_dll(exports, machine)
    with open(out, "wb") as fh:
        fh.write(data)
    print(f"wrote {out} ({len(data)} bytes, machine {machine:#06x}) "
          f"exporting {len(exports)} symbols:")
    for name in exports:
        print(f"    {name}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
