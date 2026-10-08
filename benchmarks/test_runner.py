"""Exercise the smoke gate with deliberate regressions and incomplete reports."""
import copy
import unittest

from run import COMMON_OPERATIONS, JDK_OPERATIONS, compare, validate


def fixture():
    rows = []
    for target in ("jvm", "js"):
        for size in (100, 1024, 65536):
            for corpus in ("text", "random", "runs", "stored"):
                operations = COMMON_OPERATIONS | (JDK_OPERATIONS if target == "jvm" else set())
                if corpus == "stored":
                    operations = {"inflate", "jdk.inflate"} if target == "jvm" else {"inflate"}
                for operation in sorted(operations):
                    rows.append(dict(platform=target, operation=operation, corpus=corpus, bytes=size,
                                     crc32=42, iterations=100, cpu_ns_per_call=[1000.0] * 3,
                                     warmup_calls=20000, warmup_cpu_ns=250_000_000,
                                     mb_per_second=float(size)))
    return dict(schema=1, corpus_version=1, profile="smoke", minimum_ratio=0.1, results=rows)


class SmokeGateTest(unittest.TestCase):
    def test_passes_within_wide_tolerance_but_rejects_real_slowdown(self):
        baseline = fixture()
        current = copy.deepcopy(baseline)
        row = current["results"][0]
        row["cpu_ns_per_call"] = [10000.0] * 3
        row["mb_per_second"] /= 10
        self.assertAlmostEqual(0.1, compare(current, baseline))
        row["cpu_ns_per_call"] = [11000.0] * 3
        row["mb_per_second"] = row["bytes"] / 11
        with self.assertRaisesRegex(ValueError, "regression"):
            compare(current, baseline)

    def test_rejects_missing_node_and_duplicate_rows(self):
        report = fixture()
        report["results"] = [row for row in report["results"] if row["platform"] == "jvm"]
        with self.assertRaisesRegex(ValueError, "Incomplete"):
            validate(report)
        report = fixture()
        report["results"].append(report["results"][0])
        with self.assertRaisesRegex(ValueError, "duplicate"):
            validate(report)

    def test_rejects_invalid_cpu_measurements(self):
        for value in (0, -1, float("nan"), float("inf")):
            report = fixture()
            report["results"][0]["mb_per_second"] = value
            with self.assertRaisesRegex(ValueError, "Invalid measurement"):
                validate(report)

    def test_rejects_incomplete_warmup(self):
        for field in ("warmup_calls", "warmup_cpu_ns"):
            report = fixture()
            report["results"][0][field] = 0
            with self.assertRaisesRegex(ValueError, "Invalid measurement"):
                validate(report)

    def test_rejects_corpus_changes_on_either_runtime_or_baseline(self):
        baseline = fixture()
        report = copy.deepcopy(baseline)
        report["results"][0]["crc32"] = 123
        with self.assertRaisesRegex(ValueError, "corpora differ"):
            validate(report)
        for row in report["results"]:
            row["crc32"] = 123
        with self.assertRaisesRegex(ValueError, "Corpus changed"):
            compare(report, baseline)


if __name__ == "__main__":
    unittest.main()
