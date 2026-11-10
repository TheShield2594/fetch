from __future__ import annotations

import unittest

from check_baseline_profiles import (
    BASELINE_REQUIRED_PACKAGES,
    STARTUP_REQUIRED_PACKAGES,
    app_rules,
    check_profiles,
    rule_package,
)


def rules_for(packages: dict[str, int]) -> list[str]:
    return [
        f"HSPL{package}/Thing{i};->run()V" for package, count in packages.items() for i in range(count)
    ]


LIBRARY_RULES = [f"Landroidx/compose/runtime/Thing{i};" for i in range(500)]
STARTUP = rules_for(STARTUP_REQUIRED_PACKAGES)
BASELINE = rules_for(BASELINE_REQUIRED_PACKAGES) + LIBRARY_RULES


class RuleParsingTests(unittest.TestCase):
    def test_package_of_a_method_rule(self) -> None:
        self.assertEqual(
            rule_package("HSPLcom/fetch/tv/ui/theme/ThemeKt;->FetchTheme(Z)V"),
            "com/fetch/tv/ui/theme",
        )

    def test_package_of_a_class_rule(self) -> None:
        self.assertEqual(rule_package("Lcom/fetch/tv/MainActivity;"), "com/fetch/tv")

    def test_only_app_classes_count_as_app_rules(self) -> None:
        rules = ["Lcom/fetch/tv/MainActivity;", "Lcom/fetch/tvx/Other;", "Landroidx/Foo;"]
        self.assertEqual(app_rules(rules), ["Lcom/fetch/tv/MainActivity;"])


class CheckProfilesTests(unittest.TestCase):
    def test_distinct_covered_profiles_pass(self) -> None:
        self.assertEqual(check_profiles(BASELINE, STARTUP), [])

    def test_identical_profiles_fail(self) -> None:
        problems = check_profiles(BASELINE, list(BASELINE))
        self.assertTrue(any("identical" in problem for problem in problems))

    def test_startup_nearly_as_large_as_baseline_fails(self) -> None:
        problems = check_profiles(BASELINE, BASELINE[:-1])
        self.assertTrue(any("more than launch" in problem for problem in problems))

    def test_empty_profile_fails(self) -> None:
        self.assertTrue(check_profiles(BASELINE, []))

    def test_baseline_without_fetch_rules_fails(self) -> None:
        problems = check_profiles(LIBRARY_RULES * 2, STARTUP)
        self.assertTrue(any("baseline-prof.txt has no rules for com/fetch/tv" in p for p in problems))

    def test_missing_required_package_is_named(self) -> None:
        baseline = [rule for rule in BASELINE if "ui/screens/detail" not in rule]
        problems = check_profiles(baseline, STARTUP)
        self.assertTrue(any("com/fetch/tv/ui/screens/detail (0 rules" in problem for problem in problems))

    def test_a_handful_of_rules_does_not_count_as_covered(self) -> None:
        home = [rule for rule in BASELINE if "ui/screens/home" in rule]
        baseline = [rule for rule in BASELINE if rule not in set(home[8:])]
        problems = check_profiles(baseline, STARTUP)
        self.assertTrue(any("com/fetch/tv/ui/screens/home (8 rules" in problem for problem in problems))

    def test_sub_package_covers_its_parent(self) -> None:
        baseline = [
            rule.replace("ui/screens/home/Thing", "ui/screens/home/rows/Thing") for rule in BASELINE
        ]
        self.assertEqual(check_profiles(baseline, STARTUP), [])

    def test_rules_for_packages_missing_from_source_fail(self) -> None:
        known = set(BASELINE_REQUIRED_PACKAGES)
        baseline = BASELINE + rules_for({"com/fetch/tv/core/gone": 100})
        problems = check_profiles(baseline, STARTUP, known)
        self.assertTrue(any("not in the source tree" in problem for problem in problems))


if __name__ == "__main__":
    unittest.main()
