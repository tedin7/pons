#!/usr/bin/env python3
"""Runs Box64's x86_64 regression tests (tests/CMakeLists.txt) on an arm64 Box64
under qemu and compares each output with its reference, like runTest.cmake.

Usage (in the pons-box64test image):
  run-tests.py <box64 binary> <box64 source dir> [extra env NAME=value ...]
Prints one line per test and a summary; exit status 1 if any test failed.
Timing is meaningless under qemu: this checks results only.
"""
import os
import re
import subprocess
import sys

box64, src = sys.argv[1], sys.argv[2]
extra = dict(kv.split("=", 1) for kv in sys.argv[3:])
tests = os.path.join(src, "tests")
qemu = ["qemu-aarch64", "-L", "/usr/aarch64-linux-gnu"]

passed = failed = 0
for line in open(os.path.join(tests, "CMakeLists.txt")):
    m = re.match(r"\s*box64_add_test\((\S+)\s+(\S+)(.*)\)\s*$", line)
    if not m or "32BIT" in m.group(3):
        continue
    name, num, rest = m.groups()
    ref = re.search(r"REF_NUM\s+(\S+)", rest)
    args2 = re.search(r"ARGS2\s+(\S+)", rest)
    envs = re.search(r'ENV\s+"([^"]*)"', rest)
    env = dict(os.environ, BOX64_LOG="0", BOX64_NOBANNER="1", BOX64_DYNACACHE="0")
    if envs:
        for kv in envs.group(1).split(";"):
            if "=" in kv:
                k, v = kv.split("=", 1)
                env[k] = v.replace("${CMAKE_SOURCE_DIR}", src)
    env.update(extra)
    cmd = qemu + [box64, os.path.join(tests, "test" + num)] + ([args2.group(1)] if args2 else [])
    reffile = os.path.join(tests, "ref%s.txt" % (ref.group(1) if ref else num))
    try:
        out = subprocess.run(cmd, env=env, cwd=tests, capture_output=True, timeout=600)
        ok = out.returncode == 0 and out.stdout == open(reffile, "rb").read()
        why = "" if ok else ("exit %d" % out.returncode if out.returncode else "output differs")
    except subprocess.TimeoutExpired:
        ok, why = False, "timeout"
    passed += ok
    failed += not ok
    print("%-4s %-28s %s" % ("ok" if ok else "FAIL", name, why), flush=True)
    if not ok and why == "output differs":
        got = out.stdout.decode(errors="replace").splitlines()
        want = open(reffile, errors="replace").read().splitlines()
        shown = 0
        for i, (g, w) in enumerate(zip(got, want)):
            if g != w and shown < 3:
                print("     line %d: got %r want %r" % (i + 1, g[:90], w[:90]))
                shown += 1
        if len(got) != len(want):
            print("     %d lines, reference has %d" % (len(got), len(want)))
print("passed %d, failed %d" % (passed, failed))
sys.exit(1 if failed else 0)
