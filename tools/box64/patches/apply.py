#!/usr/bin/env python3
"""Applies Pinguplay's Box64 changes to a Box64 source tree: apply.py <box64-src>.

0001: (V)CVTSI2SD with a 32-bit source never rounds (every int32 fits exactly in
a double), so skip switching the Arm64 rounding mode around it. Writing FPCR is
serializing on Arm cores; measured on Snapdragon 8 Elite it cost ~11 ns per
conversion, inside loops as common as `x += f(i)`.
"""
import pathlib
import sys

src = pathlib.Path(sys.argv[1]) / "src/dynarec/arm64"

OLD_SET = """            if(BOX64ENV(dynarec_fastround)<2) {
                u8 = sse_setround(dyn, ninst, x3, x4, x5);
            }
            if(rex.w) {
                SCVTFDx(d1, ed);
            } else {
                SCVTFDw(d1, ed);
            }
            if(BOX64ENV(dynarec_fastround)<2) {
                x87_restoreround(dyn, ninst, u8);
            }"""
NEW_SET = """            // int32 -> double is always exact: only the 64-bit form can round.
            if(rex.w && BOX64ENV(dynarec_fastround)<2) {
                u8 = sse_setround(dyn, ninst, x3, x4, x5);
            }
            if(rex.w) {
                SCVTFDx(d1, ed);
            } else {
                SCVTFDw(d1, ed);
            }
            if(rex.w && BOX64ENV(dynarec_fastround)<2) {
                x87_restoreround(dyn, ninst, u8);
            }"""

for name, marker in (("dynarec_arm64_f20f.c", 'INST_NAME("CVTSI2SD Gx, Ed");'),
                     ("dynarec_arm64_avx_f2_0f.c", 'INST_NAME("VCVTSI2SD Gx, Vx, Ed");')):
    path = src / name
    code = path.read_text()
    start = code.find(marker)
    if start < 0:
        sys.exit(f"apply.py: {marker} not found in {name}")
    at = code.find(OLD_SET, start)
    if at < 0 or at - start > 400:
        sys.exit(f"apply.py: rounding block for {marker} not found in {name}")
    path.write_text(code[:at] + NEW_SET + code[at + len(OLD_SET):])

print("pinguplay box64 patches applied")
