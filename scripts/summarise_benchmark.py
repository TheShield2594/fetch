#!/usr/bin/env python3
"""Prints the medians from Macrobenchmark result files (*benchmarkData.json).

Usage: summarise_benchmark.py [directory]   (default: baselineprofile/build/outputs)
"""

from __future__ import annotations

import json
import sys
from pathlib import Path


def summarise(directory: Path) -> list[str]:
    """Returns one line per benchmark and metric: min, median and max."""
    lines = []
    for path in sorted(directory.rglob("*benchmarkData.json")):
        data = json.loads(path.read_text(encoding="utf-8"))
        # Results from an earlier run can still be in the directory; say which file each is from.
        lines.append(f"{path.relative_to(directory)}:")
        for benchmark in data.get("benchmarks", []):
            for metric, values in sorted(benchmark.get("metrics", {}).items()):
                lines.append(
                    f"  {benchmark.get('name', '?'):<32} {metric:<28} "
                    f"min {values.get('minimum', float('nan')):8.1f}  "
                    f"median {values.get('median', float('nan')):8.1f}  "
                    f"max {values.get('maximum', float('nan')):8.1f}"
                )
    return lines


def main(argv: list[str]) -> int:
    directory = Path(argv[1]) if len(argv) > 1 else Path("baselineprofile/build/outputs")
    lines = summarise(directory)
    if not lines:
        print(f"No benchmark results under {directory}.")
        return 1
    print("\n".join(lines))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
