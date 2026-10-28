#!/usr/bin/env python3
"""Ranks the bottlenecks of one pp-prof.sh run: pp-prof-report.py <run dir>.

Reads what pons-prof and proot recorded, turns counts into estimated seconds
with the costs measured during calibration, and prints the biggest first.
Estimates, not exact attributions: each line says what it multiplied.
"""
import pathlib
import sys


def read(path):
    p = pathlib.Path(path)
    return p.read_text(errors="replace").splitlines() if p.exists() else []


def calibration(run):
    cal = {}
    for line in read(run / "calibration.txt"):
        parts = line.split()
        if len(parts) >= 3:
            try:
                cal[(parts[0], parts[1])] = float(parts[2])
            except ValueError:
                pass
    return cal


def proot_stops(run):
    stops = {}
    for line in read(run / "proot.txt"):
        parts = line.split()
        if len(parts) >= 2 and parts[0] != "total":
            try:
                stops[parts[0]] = int(parts[1])
            except ValueError:
                pass
    return stops


def native_calls(run):
    calls = {}
    for line in read(run / "calls.txt"):
        parts = line.split(None, 1)
        if len(parts) == 2 and parts[0].isdigit():
            calls[parts[1].strip()] = calls.get(parts[1].strip(), 0) + int(parts[0])
    return calls


def threads_and_processes(run, hz):
    first, last, names = {}, {}, {}
    procs = {}
    for line in read(run / "samples.tsv"):
        f = line.split("\t")
        if f[0] == "T" and len(f) >= 7:
            tid, ticks = f[3], int(f[5]) + int(f[6])
            names[tid] = f[4]
            first.setdefault(tid, (float(f[1]), ticks))
            last[tid] = (float(f[1]), ticks, int(f[5]), int(f[6]))
        elif f[0] == "P" and len(f) >= 7:
            procs[f[2]] = [int(x) if x.isdigit() else 0 for x in f[3:7]]
    busy = []
    for tid, (t1, ticks1, user, system) in last.items():
        t0, ticks0 = first[tid]
        busy.append((ticks1 / hz, user / hz, system / hz, names[tid], tid))
    busy.sort(reverse=True)
    totals = [sum(p[i] for p in procs.values()) for i in range(4)] if procs else [0] * 4
    return busy, totals


def main():
    run = pathlib.Path(sys.argv[1])
    cal = calibration(run)
    times = (read(run / "times.txt") or ["0 0 0 0"])[0].split()
    wall, user, system = (float(x) for x in times[:3])
    hz = 100
    for line in read(run / "times.txt"):
        if line.startswith("HZ "):
            hz = int(line.split()[1])
    stops = proot_stops(run)
    calls = native_calls(run)
    busy, (minflt, majflt, vctx, nvctx) = threads_and_processes(run, hz)

    if not (user or system) and busy:
        # `times` saw nothing (the workload ran detached): use the sampled threads.
        user = sum(b[1] for b in busy)
        system = sum(b[2] for b in busy)
    stop_us = max(0.0, (cal.get(("proot", "stat"), 0) - cal.get(("fast", "stat"), 0)) / 1000)
    bridge_ns = max(0.0, cal.get(("box64", "fabs_direct"), 0) - cal.get(("native", "fabs_direct"), 0))
    total_stops = sum(stops.values())
    total_calls = sum(calls.values())

    print(f"== {run.name}")
    print("command:", (read(run / "command.txt") or ["?"])[0])
    print(f"wall {wall:.1f} s   cpu user {user:.1f} s + system {system:.1f} s"
          f"   ({(user + system) / wall * 100 if wall else 0:.0f}% of one core)")
    for line in read(run / "phases.txt"):
        print("phase", line)
    print(f"calibration: one proot stop ~{stop_us:.1f} us, one native call bridge ~{bridge_ns:.1f} ns")

    ranking = []
    if total_stops:
        cost = total_stops * stop_us / 1e6
        ranking.append((cost, f"proot stops: {total_stops} x {stop_us:.0f} us"))
    if total_calls:
        cost = total_calls * bridge_ns / 1e9
        ranking.append((cost, f"Box64 native-call bridge: {total_calls} calls x {bridge_ns:.0f} ns"))
    if system:
        ranking.append((system, "kernel time (system CPU, includes file I/O and proot stops)"))
    if majflt:
        ranking.append((majflt * 100e-6, f"major page faults: {majflt} x ~100 us (disk reads)"))
    if nvctx:
        ranking.append((nvctx * 5e-6, f"involuntary context switches: {nvctx} x ~5 us (CPU contention)"))
    ranking.sort(reverse=True)
    print("\n-- bottlenecks, biggest first (estimated seconds, % of wall)")
    for cost, what in ranking:
        print(f"{cost:8.2f} s  {cost / wall * 100 if wall else 0:5.1f}%  {what}")

    if stops:
        print("\n-- proot stops by syscall")
        for name, n in sorted(stops.items(), key=lambda kv: -kv[1])[:10]:
            print(f"{n:10d}  {name}")
    if calls:
        print("\n-- Box64 native calls by function")
        for name, n in sorted(calls.items(), key=lambda kv: -kv[1])[:12]:
            print(f"{n:10d}  {n / wall if wall else 0:10.0f}/s  {name}")
    if busy:
        print("\n-- busiest threads (CPU s: total = user + system)")
        for total, u, s, name, tid in busy[:8]:
            print(f"{total:7.2f} = {u:6.2f} + {s:6.2f}  {name} ({tid})")
    print(f"\nprocess counters: page faults {minflt} minor / {majflt} major,"
          f" context switches {vctx} voluntary / {nvctx} involuntary")

    temps = [line.split() for line in read(run / "thermals.txt") if len(line.split()) == 5]
    if temps:
        hot = lambda i: max(float(t[i]) for t in temps)
        print(f"device: CPU max {hot(2):.0f} C, GPU max {hot(3):.0f} C, skin max {hot(4):.0f} C,"
              f" thermal status max {max(t[1] for t in temps)}")


if __name__ == "__main__":
    main()
