#!/usr/bin/env python3
"""Verify the offline 7748/8565 ARM64 FRB contract from an actual launcher ELF.

Uses only Python's standard library and a caller-selected llvm-nm executable.
This never loads the shared library, talks to a device, or infers a new runtime profile.
"""

import argparse
import hashlib
import json
import lzma
from pathlib import Path
import re
import struct
import subprocess
import sys
import tempfile


class ContractError(ValueError):
    pass


def require(condition, message):
    if not condition:
        raise ContractError(message)


class Elf64:
    def __init__(self, data):
        self.data = data
        require(len(data) >= 64 and data[:7] == b"\x7fELF\x02\x01\x01",
                "ELF64 little-endian file expected")
        header = self.unpack("<HHIQQQIHHHHHH", 16)
        _, machine, version, _, phoff, shoff, _, ehsize, phsize, phnum, shsize, shnum, names = header
        require(machine == 183 and version == 1 and ehsize == 64, "AArch64 ELF header expected")
        require(phnum != 0xffff and shnum != 0 and names != 0xffff,
                "Extended or absent ELF tables are not supported by this baseline verifier")
        require((not phnum or phsize == 56) and shsize == 64, "Invalid ELF table entry size")
        self.read(phoff, phnum * phsize)
        self.read(shoff, shnum * shsize)
        self.loads = []
        for index in range(phnum):
            kind, flags, offset, address, _, size, memory_size, _ = self.unpack(
                "<IIQQQQQQ", phoff + index * phsize)
            self.read(offset, size)
            if kind == 1:
                require(size <= memory_size and address + memory_size < 1 << 64,
                        "Invalid PT_LOAD size")
                self.loads.append((offset, address, size, flags))
        sections = [self.unpack("<IIQQQQIIQQ", shoff + i * shsize) for i in range(shnum)]
        require(names < len(sections) and sections[names][1] == 3,
                "Invalid ELF section name table")
        name_table = self.read(sections[names][4], sections[names][5])
        self.sections = {}
        for section in sections:
            name_offset, kind, _, _, offset, size, *_ = section
            require(name_offset < len(name_table), "Invalid ELF section name offset")
            end = name_table.find(b"\x00", name_offset)
            require(end >= 0, "Unterminated ELF section name")
            name = name_table[name_offset:end].decode("ascii", errors="strict")
            if kind != 8:  # SHT_NOBITS has no file-backed payload.
                self.read(offset, size)
            require(name not in self.sections, "Duplicate ELF section name: " + name)
            self.sections[name] = (offset, size, kind)

    def read(self, offset, size):
        require(0 <= offset <= len(self.data) and 0 <= size <= len(self.data) - offset,
                f"ELF range outside file: offset={offset:#x}, size={size:#x}")
        return self.data[offset:offset + size]

    def unpack(self, form, offset):
        return struct.unpack(form, self.read(offset, struct.calcsize(form)))

    def virtual_bytes(self, address, size, executable=False):
        matches = [(offset + address - start, flags)
                   for offset, start, length, flags in self.loads
                   if start <= address and size <= length - (address - start)]
        require(len(matches) == 1, f"No unique file-backed PT_LOAD for {address:#x}+{size:#x}")
        offset, flags = matches[0]
        require(not executable or flags & 1, f"Address {address:#x} is not executable")
        return self.read(offset, size)

    def words(self, address, count):
        return struct.unpack("<" + "I" * count, self.virtual_bytes(address, count * 4, True))

    def debug_data(self):
        require(".gnu_debugdata" in self.sections, "Missing .gnu_debugdata")
        offset, size, kind = self.sections[".gnu_debugdata"]
        require(kind != 8, "Debug data must be file-backed")
        decoder = lzma.LZMADecompressor(format=lzma.FORMAT_XZ, memlimit=128 * 1024 * 1024)
        data = decoder.decompress(self.read(offset, size), max_length=32 * 1024 * 1024 + 1)
        require(len(data) <= 32 * 1024 * 1024 and decoder.eof and not decoder.unused_data,
                "Oversized, incomplete, or trailing XZ debug data")
        Elf64(data)
        return data

    def plt_imports(self):
        require(all(name in self.sections for name in (".dynsym", ".dynstr", ".rela.plt")),
                "Missing dynamic import tables")
        symbol_offset, symbol_size, _ = self.sections[".dynsym"]
        string_offset, string_size, _ = self.sections[".dynstr"]
        relocation_offset, relocation_size, _ = self.sections[".rela.plt"]
        require(symbol_size % 24 == 0 and relocation_size % 24 == 0,
                "Invalid ELF64 dynamic symbol or PLT relocation size")
        strings = self.read(string_offset, string_size)
        imports = {}
        for cursor in range(relocation_offset, relocation_offset + relocation_size, 24):
            address, info, _ = self.unpack("<QQq", cursor)
            index = info >> 32
            require(info & 0xffffffff == 1026 and index < symbol_size // 24,
                    "Expected AArch64 JUMP_SLOT relocation with a valid symbol")
            name_offset, symbol_info, _, section, _, _ = self.unpack("<IBBHQQ", symbol_offset + index * 24)
            require(name_offset < len(strings), "Invalid dynamic import name offset")
            end = strings.find(b"\x00", name_offset)
            require(end >= 0, "Unterminated dynamic import name")
            if section == 0 and symbol_info & 15 == 2:
                require(address not in imports, "Duplicate imported PLT relocation")
                imports[address] = strings[name_offset:end].decode("ascii", errors="strict")
        return imports


def read_symbols(llvm_nm, sample, dynamic=False):
    command = [str(llvm_nm), "--demangle", "--print-size", "--defined-only"]
    if dynamic:
        command.append("--dynamic")
    command.append(str(sample))
    result = subprocess.run(command, capture_output=True, text=True, encoding="utf-8",
                            errors="replace", timeout=60, check=False)
    require(result.returncode == 0, "llvm-nm failed: " + result.stderr.strip())
    symbols = {}
    for line in result.stdout.splitlines():
        match = re.fullmatch(r"([0-9a-fA-F]+)\s+([0-9a-fA-F]+)\s+[tTwW]\s+(.+)", line.strip())
        if match:
            address, size, name = match.groups()
            value = (int(address, 16), int(size, 16))
            symbols.setdefault(name, set()).add(value)
    require(symbols, "llvm-nm returned no defined function symbols")
    return symbols


def symbol(symbols, name):
    require(name in symbols, "Missing exact symbol: " + name)
    require(len(symbols[name]) == 1, "Ambiguous symbol: " + name)
    return next(iter(symbols[name]))


def signed(value, bits):
    return value - (1 << bits) if value & (1 << (bits - 1)) else value


def branch_target(address, word, link=False):
    require(word & 0xfc000000 == (0x94000000 if link else 0x14000000),
            f"Expected {'BL' if link else 'B'} at {address:#x}, found {word:08x}")
    return address + signed(word & 0x03ffffff, 26) * 4


def relative_address(address, word, register, page=False):
    require(word & 0x9f00001f == ((0x90000000 if page else 0x10000000) | register),
            f"Unexpected ADR{'P' if page else ''} at {address:#x}")
    delta = signed(((word >> 5) & 0x7ffff) << 2 | ((word >> 29) & 3), 21)
    return (address & ~0xfff) + delta * 4096 if page else address + delta


def dispatcher_table(elf, entry):
    words = elf.words(entry, 10)
    require(words[0] & 0xffc003ff == 0x51000008, "Dispatcher no longer subtracts opcode from w0")
    minimum = (words[0] >> 10) & 0xfff
    require(words[1] & 0xffc003ff == 0x7100011f, "Dispatcher index bound is not understood")
    maximum_index = (words[1] >> 10) & 0xfff
    require(words[2] & 0xff00001f == 0x54000008, "Dispatcher lacks expected unsigned bound branch")
    table = relative_address(entry + 12, words[3], 9, page=True)
    require(words[4] & 0xffc003ff == 0x91000129, "Unexpected dispatcher table offset instruction")
    table += (words[4] >> 10) & 0xfff
    moved_argument = words[5] == 0xaa0103e0
    cursor = 6 if moved_argument else 5
    base = relative_address(entry + cursor * 4, words[cursor], 10)
    require(words[cursor + 1:cursor + 4] == (0x7868792b, 0x8b0b094a, 0xd61f0140),
            "Dispatcher is not the expected halfword jump table")
    require(base == entry + (cursor + 4) * 4, "Unexpected dispatcher stub base")
    elf.virtual_bytes(table, (maximum_index + 1) * 2)
    return minimum, maximum_index, table, base, moved_argument


def opcode_target(elf, table_info, operation):
    minimum, maximum_index, table, base, moved_argument = table_info
    index = operation - minimum
    require(0 <= index <= maximum_index, f"Opcode {operation} is outside dispatcher table")
    displacement = struct.unpack("<H", elf.virtual_bytes(table + index * 2, 2))[0]
    stub = base + displacement * 4
    arguments = (() if moved_argument else (0xaa0103e0,)) + (0xaa0203e1, 0x2a0303e2, 0x2a0403e3)
    words = elf.words(stub, len(arguments) + 1)
    require(words[:-1] == arguments, f"Unexpected argument forwarding at stub {stub:#x}")
    target = branch_target(stub + len(arguments) * 4, words[-1])
    elf.virtual_bytes(target, 4, True)
    return stub, target


def direct_calls(elf, function):
    address, size = function
    require(size > 0 and size % 4 == 0, f"Invalid function size at {address:#x}")
    return [(address + i * 4, branch_target(address + i * 4, word, True))
            for i, word in enumerate(elf.words(address, size // 4))
            if word & 0xfc000000 == 0x94000000]


def import_at_plt(elf, imports, address):
    words = elf.words(address, 4)
    page = relative_address(address, words[0], 16, page=True)
    require(words[1] & 0xffc003ff == 0xf9400211 and words[3] == 0xd61f0220,
            f"Unexpected imported PLT load/branch at {address:#x}")
    offset = ((words[1] >> 10) & 0xfff) * 8
    require(words[2] == 0x91000210 | (offset << 10), f"Unexpected PLT GOT address at {address:#x}")
    require(page + offset in imports, f"PLT entry at {address:#x} does not target a function import")
    return imports[page + offset]


WIRE_PREFIX = "app_launcher::frb_generated::wire__crate__api__launcher_model__launcher_model_"
DISPATCHER_IMPL = "app_launcher::frb_generated::pde_ffi_dispatcher_primary_impl"
RELOAD = "<app_launcher::launcher::data::launcher_model_internal::LauncherModelInternal>::maybe_trigger_workspace_reload"
DECODE = " as app_launcher::frb_generated::SseDecode>::sse_decode"
ITEM_TYPE = "app_launcher::api::model::database_info::ItemInfo"
ITEM_DECODE = "<" + ITEM_TYPE + DECODE
VECTOR_DECODE = "<alloc::vec::Vec<" + ITEM_TYPE + ">" + DECODE
DESERIALIZER_END = "<flutter_rust_bridge::codec::sse::SseDeserializer>::end"
GRID_GETTER_PREFIX = "<app_launcher::launcher::utils::device_configs::DeviceConfigs>::get_cell_count_"
MOTION_SCENE = "<engine_platform::embedding::scene::PlatformScene<safe_mode::safe_launcher_ui::SafeLauncherUi> as hyper_os_flutter_backend::flutter_scene::FlutterScene>::on_motion_event"
MOTION_DELEGATE = "<engine_platform::embedding::scene::SceneDelegate>::on_motion_event"
MOTION_ACCESSIBILITY = "<engine_platform::plugin::accessibility::accessibility_plugin::AccessibilityPlugin>::on_motion_event"
MOTION_CANCEL = "<app_launcher::recents::gesture::gesture_input_monitor::GestureInputMonitor>::cancel_home_gesture_to_engine"
FOCUS_DELEGATE = "<engine_platform::embedding::window::WindowDelegate>::on_focus_changed"
KEY_SCENE = "<engine_platform::embedding::scene::PlatformScene<app_launcher::launcher::components::ui::launcher_ui::LauncherUi> as hyper_os_flutter_backend::flutter_scene::FlutterScene>::on_key_event"
KEY_DELEGATE = "<engine_platform::embedding::scene::SceneDelegate>::on_key_event"
BACK_HELPER = "<app_launcher::recents::gesture::gesture_input_back_helper::GestureInputBackHelper>::"
BACK_GESTURE = "<app_launcher::recents::back_panel::gesture_stub_view_window::GestureStubViewWindow>::handle_back_gesture"
PROLOGUE = (0xd10343ff, 0xa90b7bfd, 0xa90c4ff4, 0x9102c3fd)
GRID_GETTER_PROLOGUE = (0xd10083ff, 0xa9017bfd, 0x910043fd)
MOTION_PROLOGUE = (0xd103c3ff, 0xa90c7bfd, 0xf9006bf5, 0xa90e4ff4, 0x910303fd)
FOCUS_PROLOGUE = (0xd102c3ff, 0xa9057bfd, 0xf90033fb, 0xa90767fa,
                  0xa9085ff8, 0xa90957f6, 0xa90a4ff4, 0x910143fd)
KEY_BACK_PROLOGUE = (0xd10483ff, 0xa90c7bfd, 0xa90d6ffc, 0xa90e67fa,
                     0xa90f5ff8, 0xa91057f6, 0xa9114ff4, 0x910303fd)
BASELINES = {
    "7748": {"export": 0x9b750c, "dispatcher": 0x7ee954, "move": 831, "batch": 855,
             "reload": 0x7a35a4, "move_target": 0x87bb20, "batch_target": 0x89bca8,
             "grid_x": 0x6c5a74, "grid_y": 0x6c5b20,
             "grid_x_load": 0xb9458920, "grid_y_load": 0xb9458d20,
             "motion": 0xd37904, "motion_caller": 0x743a80, "motion_caller_v2": 0x743e40,
             "focus": 0x757b34, "key": 0x9736ec, "key_caller": 0x743138,
             "key_caller_v2": 0x743a74, "back": 0x720ffc, "back_call": 0x6fffd4},
    "8565": {"export": 0x9be128, "dispatcher": 0x7f5844, "move": 833, "batch": 857,
             "reload": 0x7aa050, "move_target": 0x881cb8, "batch_target": 0x8a1dd8,
             "grid_x": 0x6c9588, "grid_y": 0x6c9634,
             "grid_x_load": 0xb9486920, "grid_y_load": 0xb9486d20,
             "motion": 0xd40fd8, "motion_caller": 0x75df1c, "motion_caller_v2": 0x75e2dc,
             "focus": 0x772360, "key": 0x97a484, "key_caller": 0x75d5d4,
             "key_caller_v2": 0x75df10, "back": 0x725c54, "back_call": 0x7050c8},
}


def verify_lifecycle_contract(elf, symbols, baseline):
    focus_names = [name for name in symbols
                   if name.startswith("<hyper_os_ui::window::dyn_window_api::UiWindow_trait::UiWindow_MV<")
                   and "PlatformWindow<app_launcher::launcher::components::ui::launcher_ui::MainUiWindow>" in name
                   and name.endswith(">::on_window_focus_changed")]
    require(len(focus_names) == 1, "No unique MainUiWindow focus entry")
    focus, size = symbol(symbols, focus_names[0])
    require((focus, size) == (baseline["focus"], 0x7f4), "MainUiWindow focus entry differs from baseline")
    require(elf.words(focus, 8) == FOCUS_PROLOGUE, "MainUiWindow focus prologue differs from baseline")
    require(elf.words(focus + 0x24, 2) == (0x390013e1, 0x2a0103f3)
            and elf.words(focus + 0x30, 1) == (0xaa0003f4,),
            "MainUiWindow focus must preserve bool w1 and window x0")
    require(elf.words(focus + 0xb0, 2) == (0x91008280, 0x2a1303e1)
            and branch_target(focus + 0xb8, elf.words(focus + 0xb8, 1)[0], True)
            == symbol(symbols, FOCUS_DELEGATE)[0]
            and elf.words(focus + 0xbc, 1) == (0x36000893,),
            "MainUiWindow no longer forwards focus w1 or branches on its boolean value")
    require(elf.words(focus + 0x6d0, 8) == (
        0xa94a4ff4, 0xf94033fb, 0xa94957f6, 0xa9485ff8,
        0xa94767fa, 0xa9457bfd, 0x9102c3ff, 0xd65f03c0),
        "MainUiWindow focus return path changed")

    key, size = symbol(symbols, KEY_SCENE)
    require((key, size) == (baseline["key"], 0x5f4), "LauncherUi key entry differs from baseline")
    require(elf.words(key, 8) == KEY_BACK_PROLOGUE, "LauncherUi key prologue differs from baseline")
    require(elf.words(key + 0x24, 2) == (0xaa0103f3, 0xaa0003f5),
            "LauncherUi key must preserve event x1 and scene x0")
    key_callers = {}
    for suffix, size in (("", 0x2c), ("_v2", 0xc)):
        names = [name for name in symbols
                 if name.startswith("<hyper_os_ui::scene::ffi::FfiUi_trait::FfiUi_MV<")
                 and "PlatformScene<app_launcher::launcher::components::ui::launcher_ui::LauncherUi>" in name
                 and name.endswith(">::on_key_event" + suffix)]
        require(len(names) == 1, "No unique LauncherUi key caller" + suffix)
        caller, caller_size = symbol(symbols, names[0])
        require((caller, caller_size) == (baseline["key_caller" + suffix], size),
                "LauncherUi key caller differs from baseline")
        if suffix:
            require(elf.words(caller, 2) == (0xaa0203e1, 0x91008000)
                    and branch_target(caller + 8, elf.words(caller + 8, 1)[0]) == key,
                    "LauncherUi key v2 must tail-call the shared entry with event x2 in x1")
        else:
            require(elf.words(caller + 0xc, 3) == (0x91008000, 0xaa0203e1, 0xaa0203f3)
                    and branch_target(caller + 0x18, elf.words(caller + 0x18, 1)[0], True) == key,
                    "LauncherUi key caller must forward event x2 into shared entry x1")
        key_callers["on_key_event" + suffix] = hex(caller)
    imports = elf.plt_imports()
    key_imports = {}
    for name, offset, result in (("input_KeyEvent_getKeyCode", 0x184, (0x2a0003f5, 0xb90033e0)),
                                 ("input_KeyEvent_getAction", 0x194, (0x2a0003fc, 0xb90037e0))):
        call = key + offset
        target = branch_target(call, elf.words(call, 1)[0], True)
        require(elf.words(call - 4, 1) == (0xaa1303e0,)
                and import_at_plt(elf, imports, target) == name
                and elf.words(call + 4, 2) == result,
                "Key getter must receive event x0 and return an i32 in w0: " + name)
        key_imports[name] = {"call": hex(call), "plt": hex(target), "abi": "i32 (event*)"}
    require(elf.words(key + 0x258, 1) == (0x710012bf,), "LauncherUi no longer checks BACK key code 4")
    require(elf.words(key + 0x174, 1) == (0x52800020,)
            and branch_target(key + 0x178, elf.words(key + 0x178, 1)[0]) == key + 0x554,
            "LauncherUi handled-key path no longer returns true in w0")
    require(elf.words(key + 0x548, 2) == (0x910042c0, 0xaa1303e1)
            and branch_target(key + 0x550, elf.words(key + 0x550, 1)[0], True)
            == symbol(symbols, KEY_DELEGATE)[0]
            and elf.words(key + 0x554, 8) == (
                0xa9514ff4, 0xa95057f6, 0xa94f5ff8, 0xa94e67fa,
                0xa94d6ffc, 0xa94c7bfd, 0x910483ff, 0xd65f03c0),
            "LauncherUi no longer returns the SceneDelegate boolean unchanged")

    back, size = symbol(symbols, BACK_HELPER + "on_back_invoke")
    require((back, size) == (baseline["back"], 0x5c4), "Back invocation entry differs from baseline")
    require(elf.words(back, 8) == KEY_BACK_PROLOGUE
            and elf.words(back + 0x20, 1) == (0xaa0003fa,),
            "Back invocation prologue or helper x0 preservation changed")
    caller = symbol(symbols, BACK_GESTURE)
    calls = [call for call, target in direct_calls(elf, caller) if target == back]
    require(calls == [baseline["back_call"]]
            and elf.words(calls[0] - 4, 1) == (0xf9406660,),
            "Committed back gesture must load helper x0 from its window and call on_back_invoke")
    require(elf.words(back + 0xf4, 1) == (0xaa1a03e0,)
            and branch_target(back + 0xf8, elf.words(back + 0xf8, 1)[0], True)
            == symbol(symbols, BACK_HELPER + "get_on_back_invoked_callback")[0],
            "Back invocation no longer resolves its callback from helper x0")
    back_imports = {}
    for name, offset in (("IOnBackInvokedCallback_on_back_invoked", 0x124),
                         ("IOnBackInvokedCallback_drop", 0x41c)):
        call = back + offset
        target = branch_target(call, elf.words(call, 1)[0], True)
        require(import_at_plt(elf, imports, target) == name, "Back callback dispatch/cleanup changed: " + name)
        back_imports[name] = {"call": hex(call), "plt": hex(target)}
    require(elf.words(back + 0x538, 8) == (
        0xa9514ff4, 0xa95057f6, 0xa94f5ff8, 0xa94e67fa,
        0xa94d6ffc, 0xa94c7bfd, 0x910483ff, 0xd65f03c0),
        "Back invocation return path changed")
    return {
        "focus": {"address": hex(focus), "symbol": focus_names[0], "abi": "void (window*, bool)",
                  "focus_register": "w1", "prologue": [f"{word:08x}" for word in FOCUS_PROLOGUE]},
        "key": {"address": hex(key), "symbol": KEY_SCENE, "abi": "bool (scene*, event*)",
                "event_register": "x1", "prologue": [f"{word:08x}" for word in KEY_BACK_PROLOGUE],
                "callers": key_callers, "imports": key_imports},
        "back": {"address": hex(back), "symbol": BACK_HELPER + "on_back_invoke", "abi": "void (helper*)",
                 "prologue": [f"{word:08x}" for word in KEY_BACK_PROLOGUE], "caller": hex(calls[0]),
                 "imports": back_imports},
    }


def verify_motion_contract(elf, symbols, baseline):
    address, size = symbol(symbols, MOTION_SCENE)
    require((address, size) == (baseline["motion"], 0x1fc), "Shared scene motion entry differs from baseline")
    require(elf.words(address, 5) == MOTION_PROLOGUE, "Shared scene motion prologue differs from baseline")
    require(elf.words(address + 0x18, 2) == (0xaa0103f3, 0xaa0003f4),
            "Shared scene motion must preserve event x1 and scene x0")
    imports = elf.plt_imports()
    require(elf.words(address + 0x58, 1) == (0xaa1303e0,), "Motion action query no longer receives event x1")
    action_plt = branch_target(address + 0x5c, elf.words(address + 0x5c, 1)[0], True)
    require(import_at_plt(elf, imports, action_plt) == "input_MotionEvent_getActionMasked",
            "Shared scene no longer queries the event action")
    delegate = symbol(symbols, MOTION_DELEGATE)[0]
    require(elf.words(address + 0x198, 2) == (0x91004280, 0xaa1303e1)
            and branch_target(address + 0x1a0, elf.words(address + 0x1a0, 1)[0], True) == delegate,
            "Shared scene no longer forwards the event to SceneDelegate in x1")
    require(elf.words(address + 0x1a4, 5) == (0xa94e4ff4, 0xf9406bf5, 0xa94c7bfd, 0x9103c3ff, 0xd65f03c0),
            "Shared scene return path changed")
    accessibility = symbol(symbols, MOTION_ACCESSIBILITY)[0]
    require(elf.words(delegate + 0x18, 1) == (0xaa0103f4,)
            and elf.words(delegate + 0x44, 1) == (0xaa1403e2,)
            and branch_target(delegate + 0x54, elf.words(delegate + 0x54, 1)[0]) == accessibility,
            "SceneDelegate no longer forwards its event to AccessibilityPlugin")
    callers = {}
    for suffix, call_offset in (("", 0x1c), ("_v2", 0x24)):
        matches = [name for name in symbols
                   if name.startswith("<hyper_os_ui::scene::ffi::FfiUi_trait::FfiUi_MV<")
                   and "PlatformScene<app_launcher::launcher::components::ui::launcher_ui::LauncherUi>" in name
                   and name.endswith(">::on_motion_event" + suffix)]
        require(len(matches) == 1, "No unique LauncherUi motion caller" + suffix)
        caller = symbol(symbols, matches[0])[0]
        require(caller == baseline["motion_caller" + suffix], "LauncherUi motion caller differs from baseline")
        require(elf.words(caller + call_offset - 16, 4) == (0xaa0003f4, 0x91008000, 0xaa0203e1, 0xaa0203f3)
                and branch_target(caller + call_offset, elf.words(caller + call_offset, 1)[0], True) == address,
                "LauncherUi caller no longer forwards its event x2 into shared entry x1")
        callers["on_motion_event" + suffix] = {"address": hex(caller), "call": hex(caller + call_offset)}
    cancel = symbol(symbols, MOTION_CANCEL)[0]
    require(elf.words(cancel + 0x40, 2) == (0x52800061, 0xaa0003f6),
            "Motion clone/setAction call no longer forwards the clone result and i32 action")
    import_calls = {
        "input_MotionEvent_clone": (cancel + 0x3c, True, "void* (void*)"),
        "input_MotionEvent_setAction": (cancel + 0x48, True, "void (void*, i32)"),
        "input_MotionEvent_drop": (baseline["motion_caller"] + 0x54, False, "void (void*)"),
    }
    import_evidence = {}
    for name, (call, link, abi) in import_calls.items():
        target = branch_target(call, elf.words(call, 1)[0], link)
        require(import_at_plt(elf, imports, target) == name, "Missing or changed motion import: " + name)
        import_evidence[name] = {"call": hex(call), "plt": hex(target), "abi": abi}
    return {"address": hex(address), "symbol": MOTION_SCENE, "abi": "void (scene*, event*)",
            "event_register": "x1", "prologue": [f"{word:08x}" for word in MOTION_PROLOGUE],
            "callers": callers, "delegate": hex(delegate), "accessibility": hex(accessibility),
            "imports": import_evidence}


def verify(sample, llvm_nm, profile, batch_op=None, move_op=None):
    data = sample.read_bytes()
    elf = Elf64(data)
    debug = elf.debug_data()
    with tempfile.TemporaryDirectory(prefix="actiongrid-contract-") as directory:
        debug_path = Path(directory) / "launcher-debug.elf"
        debug_path.write_bytes(debug)
        symbols = read_symbols(llvm_nm, debug_path)
    exported = symbol(read_symbols(llvm_nm, sample, dynamic=True), "frb_pde_ffi_dispatcher_primary")
    baseline = BASELINES[profile]
    require(exported == (baseline["export"], 4), "Dispatcher export does not match selected baseline")
    dispatcher = branch_target(exported[0], elf.words(exported[0], 1)[0])
    require(dispatcher == baseline["dispatcher"] == symbol(symbols, DISPATCHER_IMPL)[0],
            "Dispatcher export does not branch to its exact implementation symbol")
    table = dispatcher_table(elf, dispatcher)
    operations = {}
    for kind, suffix, override in (("batch", "update_item_database_batch_impl", batch_op),
                                   ("move", "move_item_in_database_impl", move_op)):
        operation = baseline[kind] if override is None else override
        expected_name = WIRE_PREFIX + suffix
        expected_address = symbol(symbols, expected_name)[0]
        require(expected_address == baseline[kind + "_target"],
                f"{kind} symbol does not match selected baseline")
        stub, target = opcode_target(elf, table, operation)
        actual_names = [name for name, values in symbols.items() if any(value[0] == target for value in values)]
        require(target == expected_address,
                f"Opcode {operation} reaches {target:#x} ({'; '.join(actual_names) or 'unnamed'}); "
                f"expected {expected_name} at {expected_address:#x}")
        operations[kind] = {"opcode": operation, "stub": hex(stub), "target": hex(target),
                            "symbol": expected_name}
    reload_address, _ = symbol(symbols, RELOAD)
    require(reload_address == baseline["reload"], "Reload symbol does not match selected baseline")
    require(elf.words(reload_address, 4) == PROLOGUE, "Reload prologue differs from baseline")
    vector = symbol(symbols, VECTOR_DECODE)
    item = symbol(symbols, ITEM_DECODE)
    move_calls = direct_calls(elf, symbol(symbols, WIRE_PREFIX + "move_item_in_database_impl"))
    item_call_indices = [index for index, (_, target) in enumerate(move_calls) if target == item[0]]
    require(len(item_call_indices) == 1, "Single-item move must decode exactly one ItemInfo")
    argument_types = ["ItemInfo", "i64", "i32", "i32", "i32"]
    argument_names = ["item", "container", "screen", "cellX", "cellY"]
    argument_targets = [item[0]] + [symbol(symbols, "<" + kind + DECODE)[0]
                                    for kind in argument_types[1:]]
    decode_calls = move_calls[item_call_indices[0]:item_call_indices[0] + 6]
    require([target for _, target in decode_calls] == argument_targets + [symbol(symbols, DESERIALIZER_END)[0]],
            "Move wire must decode ItemInfo, i64 container, i32 screen, i32 cellX, i32 cellY before end")
    move_arguments = [{"name": name, "type": kind, "call": hex(call), "decoder": hex(target)}
                      for name, kind, (call, target) in zip(argument_names, argument_types, decode_calls)]
    batch_calls = direct_calls(elf, symbol(symbols, WIRE_PREFIX + "update_item_database_batch_impl"))
    require(vector[0] in [target for _, target in batch_calls], "Batch wrapper no longer decodes Vec<ItemInfo>")
    vector_calls = direct_calls(elf, vector)
    require(vector_calls and vector_calls[0][1] == symbol(symbols, "<i32" + DECODE)[0],
            "ItemInfo vector no longer starts with an i32 count")
    require(item[0] in [target for _, target in vector_calls], "Vector no longer calls the ItemInfo decoder")
    grid_getters = {}
    for axis in ("x", "y"):
        name = GRID_GETTER_PREFIX + axis
        address, size = symbol(symbols, name)
        require((address, size) == (baseline["grid_" + axis], 0xac),
                f"Grid {axis} getter does not match selected baseline")
        require(elf.words(address, 3) == GRID_GETTER_PROLOGUE,
                f"Grid {axis} getter prologue differs from baseline")
        require(elf.words(address + 0x40, 9) == (
            baseline["grid_" + axis + "_load"], 0x085f7d09, 0x7100053f,
            0x54000201, 0x0809fd1f, 0x35ffff89, 0xa9417bfd, 0x910083ff, 0xd65f03c0),
            f"Grid {axis} getter no longer returns the configured i32 through w0")
        grid_getters[axis] = {"address": hex(address), "symbol": name,
                              "abi": "i32 ()", "prologue": [f"{word:08x}" for word in GRID_GETTER_PROLOGUE]}
    motion = verify_motion_contract(elf, symbols, baseline)
    lifecycle = verify_lifecycle_contract(elf, symbols, baseline)
    return {"profile": profile, "sample": str(sample.resolve()),
            "sha256": hashlib.sha256(data).hexdigest(),
            "debug_sha256": hashlib.sha256(debug).hexdigest(),
            "dispatcher": hex(dispatcher), "table": hex(table[2]), "stub_base": hex(table[3]),
            "operations": operations, "reload": {"address": hex(reload_address), "symbol": RELOAD,
            "prologue": [f"{word:08x}" for word in PROLOGUE]},
            "decoder": {"vector": hex(vector[0]), "item": hex(item[0]), "count_type": "i32",
                        "move_arguments": move_arguments, "move_tail_bytes": 20,
                        "move_end_call": hex(decode_calls[-1][0])},
            "grid_getters": grid_getters,
            "motion": motion,
            "lifecycle": lifecycle,
            "scope": "offline ELF contract only; no target execution or runtime compatibility claim"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("sample", type=Path, help="libapp_launcher.so to inspect")
    parser.add_argument("--llvm-nm", required=True, type=Path, help="Existing llvm-nm executable")
    parser.add_argument("--profile", required=True, choices=BASELINES)
    parser.add_argument("--batch-op", type=int, help="Override expected batch opcode (also useful as a negative check)")
    parser.add_argument("--move-op", type=int, help="Override expected single-item move opcode")
    parser.add_argument("--json", action="store_true", help="Print the evidence as JSON")
    args = parser.parse_args()
    try:
        evidence = verify(args.sample, args.llvm_nm, args.profile, args.batch_op, args.move_op)
    except (ContractError, OSError, UnicodeError, lzma.LZMAError, subprocess.TimeoutExpired) as error:
        print("FAIL: " + str(error), file=sys.stderr)
        return 1
    if args.json:
        print(json.dumps(evidence, ensure_ascii=False, indent=2))
    else:
        print(f"PASS: {args.profile} offline launcher contract ({evidence['sha256']})")
        for kind, operation in evidence["operations"].items():
            print(f"  {kind}: op {operation['opcode']} -> {operation['target']} {operation['symbol']}")
        print(f"  reload: {evidence['reload']['address']}; prologue verified")
        print(f"  decoder: Vec<ItemInfo> {evidence['decoder']['vector']} -> ItemInfo {evidence['decoder']['item']}")
        print("  move wire: ItemInfo + i64 container + i32 screen + i32 cellX + i32 cellY (20-byte tail)")
        print(f"  grid getters: x {evidence['grid_getters']['x']['address']}, y {evidence['grid_getters']['y']['address']}; i32 () verified")
        print(f"  motion: {evidence['motion']['address']}; void (scene*, event*) with event x1; LauncherUi callers and clone/setAction/drop imports verified")
        print(f"  focus: {evidence['lifecycle']['focus']['address']}; void (window*, bool) with focus w1 verified")
        print(f"  key: {evidence['lifecycle']['key']['address']}; bool (scene*, event*) with event x1 and key getter imports verified")
        print(f"  back gesture: {evidence['lifecycle']['back']['address']}; void (helper*) with committed gesture caller and callback cleanup verified")
        print("  Offline evidence only; the launcher was not executed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
