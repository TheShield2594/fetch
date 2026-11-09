#!/usr/bin/env python3
"""Checks the committed baseline and start-up profiles.

Fails when the start-up profile is a copy of the baseline profile (so R8 gets no useful
dex layout), when either profile has no rules for Fetch's own hot paths, or when the
profiles were recorded on a different codebase. See docs/performance.md, section 2.
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_PROFILE_DIR = REPO_ROOT / "app/src/main/generated/baselineProfiles"

APP_PACKAGE = "com/fetch/tv"

# The start-up profile holds launch to the first Home frame only; the baseline profile adds
# onboarding, Home scrolling and a detail page. A start-up profile close to the baseline's
# size means the start-up run recorded more than launch.
MAX_STARTUP_TO_BASELINE_RATIO = 0.8

# Screens and layers BaselineProfileGenerator's journey goes through. A profile without
# them was not recorded on Fetch, or the journey stopped before Home.
BASELINE_REQUIRED_PACKAGES = (
    "com/fetch/tv/ui/screens/home",
    "com/fetch/tv/ui/screens/detail",
    "com/fetch/tv/ui/screens/setup",
    "com/fetch/tv/ui/navigation",
    "com/fetch/tv/ui/theme",
    "com/fetch/tv/data/repository",
    "com/fetch/tv/data/local",
    "com/fetch/tv/core/streams/ranking",
)
STARTUP_REQUIRED_PACKAGES = (
    "com/fetch/tv/ui/screens/home",
    "com/fetch/tv/ui/navigation",
    "com/fetch/tv/data/local",
)

# Share of Fetch rules allowed to name a package that has no source directory. Generated
# code lives next to its sources, so anything above this is a profile from another tree.
MAX_STALE_RULE_SHARE = 0.02

# A rule is optional flags (H, S, P), then a class descriptor, then optionally a method.
_RULE_CLASS = re.compile(r"^[HSP]*L([^;]+);")


def read_rules(path: Path) -> list[str]:
    """Returns the profile's rules, without blank lines and comments."""
    rules = []
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line and not line.startswith("#"):
            rules.append(line)
    return rules


def rule_package(rule: str) -> str | None:
    """Returns the package path of the class a rule names, e.g. com/fetch/tv/ui/theme."""
    match = _RULE_CLASS.match(rule)
    if not match:
        return None
    class_path = match.group(1)
    return class_path.rsplit("/", 1)[0] if "/" in class_path else ""


def app_rules(rules: list[str]) -> list[str]:
    """Returns the rules that name a class in Fetch's own package."""
    result = []
    for rule in rules:
        package = rule_package(rule)
        if package is not None and (package == APP_PACKAGE or package.startswith(APP_PACKAGE + "/")):
            result.append(rule)
    return result


def source_packages(repo_root: Path) -> set[str]:
    """Returns every package path that has a source directory in any module."""
    packages: set[str] = set()
    for source_root in repo_root.glob("*/src/*/*"):
        if source_root.name not in ("java", "kotlin") or not source_root.is_dir():
            continue
        app_root = source_root / APP_PACKAGE
        if not app_root.is_dir():
            continue
        packages.add(APP_PACKAGE)
        for directory in app_root.rglob("*"):
            if directory.is_dir():
                packages.add(directory.relative_to(source_root).as_posix())
    return packages


def missing_packages(rules: list[str], required: tuple[str, ...]) -> list[str]:
    """Returns the required packages that no rule covers, sub-packages included."""
    covered = {rule_package(rule) for rule in rules}
    covered.discard(None)
    return [
        package
        for package in required
        if not any(c == package or c.startswith(package + "/") for c in covered)
    ]


def check_profiles(
    baseline: list[str],
    startup: list[str],
    known_packages: set[str] | None = None,
) -> list[str]:
    """Returns the problems found; an empty list means the profiles are usable."""
    problems: list[str] = []

    if not baseline:
        problems.append("baseline-prof.txt has no rules.")
    if not startup:
        problems.append("startup-prof.txt has no rules.")
    if problems:
        return problems

    if baseline == startup:
        problems.append(
            "startup-prof.txt is identical to baseline-prof.txt. The start-up profile must "
            "hold launch to the first Home frame only."
        )
    elif len(startup) > len(baseline) * MAX_STARTUP_TO_BASELINE_RATIO:
        problems.append(
            f"startup-prof.txt has {len(startup)} rules against {len(baseline)} in "
            f"baseline-prof.txt; it should be under {MAX_STARTUP_TO_BASELINE_RATIO:.0%} of it. "
            "The start-up run is recording more than launch."
        )

    for name, rules, required in (
        ("baseline-prof.txt", baseline, BASELINE_REQUIRED_PACKAGES),
        ("startup-prof.txt", startup, STARTUP_REQUIRED_PACKAGES),
    ):
        fetch_rules = app_rules(rules)
        if not fetch_rules:
            problems.append(f"{name} has no rules for {APP_PACKAGE}.")
            continue
        missing = missing_packages(fetch_rules, required)
        if missing:
            problems.append(f"{name} has no rules for: {', '.join(missing)}.")
        if known_packages:
            stale = [rule for rule in fetch_rules if rule_package(rule) not in known_packages]
            if len(stale) > len(fetch_rules) * MAX_STALE_RULE_SHARE:
                stale_packages = sorted({rule_package(rule) or "" for rule in stale})
                shown = ", ".join(stale_packages[:5])
                more = f" and {len(stale_packages) - 5} more" if len(stale_packages) > 5 else ""
                problems.append(
                    f"{name} has {len(stale)} of {len(fetch_rules)} Fetch rules in packages "
                    f"that are not in the source tree ({shown}{more}). It was recorded on "
                    "different code."
                )

    return problems


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--dir",
        type=Path,
        default=DEFAULT_PROFILE_DIR,
        help="directory holding baseline-prof.txt and startup-prof.txt",
    )
    args = parser.parse_args(argv)

    paths = {name: args.dir / name for name in ("baseline-prof.txt", "startup-prof.txt")}
    absent = [str(path) for path in paths.values() if not path.is_file()]
    if absent:
        print(f"Missing profile: {', '.join(absent)}", file=sys.stderr)
        return 1

    baseline = read_rules(paths["baseline-prof.txt"])
    startup = read_rules(paths["startup-prof.txt"])
    problems = check_profiles(baseline, startup, source_packages(REPO_ROOT))

    print(
        f"baseline-prof.txt: {len(baseline)} rules, {len(app_rules(baseline))} for Fetch; "
        f"startup-prof.txt: {len(startup)} rules, {len(app_rules(startup))} for Fetch."
    )
    if problems:
        for problem in problems:
            print(f"ERROR: {problem}", file=sys.stderr)
        print(
            "Regenerate with the Baseline profile workflow or "
            "`scripts/perf_baseline.sh generate-profile` (docs/performance.md, section 2).",
            file=sys.stderr,
        )
        return 1
    print("Profiles look right.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
